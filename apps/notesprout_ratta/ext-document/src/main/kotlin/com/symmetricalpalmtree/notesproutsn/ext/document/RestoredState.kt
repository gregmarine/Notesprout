package com.symmetricalpalmtree.notesproutsn.ext.document

import android.os.Bundle

/**
 * What a recreated screen wakes up holding, and **the mode-routing guard's editor half** (arc 19 /
 * M7).
 *
 * The host's half of that guard is structural: every `saveChunk` names its target, and one whose key
 * is not the current target's is refused. This is the other end of the same rope. A screen that
 * Android rebuilt is holding a buffer, a caret and possibly an unstored draft claim belonging to
 * *some* target — and after M7 the two targets in play are a page's document and the notebook's,
 * which are different rows behind different keys. A process death between a scope switch and its
 * first save is exactly when the bundle and the freshly loaded target disagree.
 *
 * So the bundle carries the key it was written under, and [bind] drops **all three** — buffer, caret
 * and claim — the moment it does not match ([ScopeRules.restoredBufferApplies]). Not two of them:
 * a caret from the wrong document is a scroll position into text that is not there, and a claim from
 * the wrong document would stamp a watermark this one never earned. The cost of dropping is at worst
 * one debounce of typing; the cost of not dropping is notebook text stored on a page.
 *
 * [previewing] deliberately **survives** a mismatch: which of the two surfaces was up is a fact
 * about the reader, not about the document.
 *
 * **Nothing here logs, ever** — this class exists to hold document text, and document text is never
 * logged on either side of this seam. The one drop message the Activity writes names no content.
 */
internal class RestoredState(bundle: Bundle?) {

    private val key: String? = bundle?.getString(STATE_KEY)
    private var text: String? = bundle?.getString(STATE_TEXT)
    private var caretValue: Int = bundle?.getInt(STATE_CARET, NO_CARET) ?: NO_CARET
    private var draftPendingValue: Boolean = bundle?.getBoolean(STATE_DRAFT_PENDING) == true

    /** Which surface was up. Not part of the document, so not part of the guard. */
    val previewing: Boolean = bundle?.getBoolean(STATE_PREVIEWING) == true

    /** The bundle's caret, or [NO_CARET] for "there was no bundle" — after [bind], also for "the
     *  bundle was another target's". The distinction matters: 0 is a real caret (the top of the
     *  document) and would otherwise beat the remembered one on every recreation. */
    val caret: Int get() = caretValue

    /** A recreated screen may be holding an unstored seed: the claim has to come back with it, or
     *  the save that lands those words would be an ordinary one and the provenance would be lost. */
    val draftPending: Boolean get() = draftPendingValue

    /**
     * Bind to the target the load actually landed on. Returns whether the bundle was kept; a `false`
     * means the three document-shaped fields have just been thrown away and the screen should open
     * exactly as it would with no bundle at all — text from the host's window, caret from the store.
     *
     * Called **before** the caret lookup, so a dropped caret still gets the stored one rather than
     * the top of the document.
     */
    fun bind(loadedKey: String): Boolean {
        if (ScopeRules.restoredBufferApplies(key, loadedKey)) return true
        text = null
        caretValue = NO_CARET
        draftPendingValue = false
        return false
    }

    /** The saved buffer, or null. **Consumed** — a second call answers null, so a later reload
     *  cannot resurrect words the writer has moved past. */
    fun takeText(): String? = text.also { text = null }

    companion object {

        /** "The bundle had no caret" — see [caret]. */
        const val NO_CARET = -1

        private const val STATE_TEXT = "doc_text"
        private const val STATE_CARET = "doc_caret"
        private const val STATE_PREVIEWING = "doc_previewing"
        private const val STATE_DRAFT_PENDING = "doc_draft_pending"

        /** The target the rest of this bundle belongs to — the guard's whole basis (M7). */
        private const val STATE_KEY = "doc_key"

        /**
         * Write the bundle, keys in one place with the reads. [text] is null when nothing should
         * ride: the buffer travels only while it is small enough to survive the trip, because a
         * `Bundle` goes home through a Binder transaction and one over the ~1 MB budget takes the
         * whole process down with `TransactionTooLargeException`. Above that cap the unsaved tail is
         * at worst one debounce — and the park and the teardown flush are both still behind it.
         */
        fun save(
            outState: Bundle,
            key: String?,
            text: String?,
            caret: Int,
            previewing: Boolean,
            draftPending: Boolean,
        ) {
            outState.putBoolean(STATE_PREVIEWING, previewing)
            outState.putBoolean(STATE_DRAFT_PENDING, draftPending)
            outState.putInt(STATE_CARET, caret)
            if (key != null) outState.putString(STATE_KEY, key)
            if (text != null) outState.putString(STATE_TEXT, text)
        }
    }
}
