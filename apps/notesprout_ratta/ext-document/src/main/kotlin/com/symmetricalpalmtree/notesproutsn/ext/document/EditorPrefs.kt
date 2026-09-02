package com.symmetricalpalmtree.notesproutsn.ext.document

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The editor's small per-device state — the text size, the proofread toggle, the user dictionary
 * and the caret memory — over the host's extension store (arc 19 / M5, on rows since arc 22 / X4).
 *
 * Three tables hold it, declared by [EditorSchema] and reached only through [EditorStore], which is
 * the one place in this module where SQL runs:
 *
 * ```sql
 * prefs (key, value)                   -- 'size', 'proofread' (absent = on)
 * word  (word, addedAt)                -- the user dictionary
 * caret (pageKey, offset, updatedAt)   -- where the writer left off, per page
 * ```
 *
 * **The extension writes nothing to disk itself, ever.** This is the host's store, minted per bind
 * and revoked with the unbind, so everything here goes through the binder parked in
 * [EditorSession] — and the binder is **fetched per call**, never cached: the host can restart
 * underneath a live screen and lend a new one, and a held reference would be a binder that throws
 * on every call for the rest of the showing.
 *
 * **Every method blocks on Binder I/O — call only from `Dispatchers.IO` or another background
 * thread.** None of them may be called from Main; [rememberCaretAsync] is the one exception, and it
 * is a hand-off to a background lane rather than a method that does the work.
 *
 * **Every exception means "store unavailable"** (the host's rule, `ScratchStore`'s idiom): a read
 * returns its default and a write is skipped, silently. This state is comfort, not content — losing
 * it costs a scroll and a tap, and no failure of it may ever surface as a problem the writer has to
 * deal with. That is this object's whole job: [EditorStore] lets failures through, and this decides
 * what they mean. Nothing here logs: the values are small, but the keys name pages and the words
 * are the writer's own vocabulary.
 *
 * The `prefs` table's name and columns are [com.symmetricalpalmtree.notesproutsn.extension.DocumentContract]'s,
 * because that one table is read by the **host** as well (Document-PDF export's text size).
 */
object EditorPrefs {

    /** What the editor opens at before anything has been chosen. */
    const val DEFAULT_TEXT_SIZE = 16f

    /**
     * Preview reads a little larger than the source it came from: source is monospace Markdown
     * where columns carry meaning, preview is prose meant to be read.
     */
    const val PREVIEW_BUMP = 2f

    /** The offered sizes, smallest first — the label to show and the sp it means. */
    val SIZES: List<Pair<Int, Float>> = listOf(
        R.string.text_size_small to 14f,
        R.string.text_size_medium to 16f,
        R.string.text_size_large to 18f,
        R.string.text_size_larger to 21f,
        R.string.text_size_largest to 25f,
    )

    /** For the fire-and-forget caret handover, which must land even after `finish()` has started —
     *  so it cannot ride the screen's lifecycle scope. Parallelism 1 (M11): two launches running
     *  concurrently could store a stale caret over the leave-path's newer one, and a single lane
     *  runs them in launch order. Order is the reason, not exclusion — a statement is correct
     *  whoever else is writing. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // ── Text size ─────────────────────────────────────────────────────────────

    /** The stored size, or [DEFAULT_TEXT_SIZE]. **Blocking — never on Main.** */
    fun textSize(): Float = try {
        store()?.textSize() ?: DEFAULT_TEXT_SIZE
    } catch (e: Exception) {
        DEFAULT_TEXT_SIZE
    }

    /** Remember [sp] for next time. **Blocking — never on Main.** */
    fun saveTextSize(sp: Float) {
        try {
            store()?.saveTextSize(sp)
        } catch (e: Exception) {
            // Unavailable: the size still applies to this showing, it just will not outlive it.
        }
    }

    // ── Proofread (arc 19 / M10) ──────────────────────────────────────────────

    /** Whether proofread is on. Absent = on — the feature defaults on. **Blocking — never on
     *  Main.** Unavailable reads answer `true`: the default must not flip because the store
     *  hiccuped, and a wrongly-on proofread costs heap while a wrongly-off one silently
     *  removes a feature. */
    fun proofreadEnabled(): Boolean = try {
        store()?.proofreadEnabled() ?: true
    } catch (e: Exception) {
        true
    }

    /** Remember the proofread toggle. **Blocking — never on Main.** */
    fun saveProofreadEnabled(on: Boolean) {
        try {
            store()?.saveProofreadEnabled(on)
        } catch (e: Exception) {
            // Unavailable: the choice still applies to this showing.
        }
    }

    // ── The user dictionary (arc 19 / M10) ────────────────────────────────────

    /** The stored user dictionary, oldest first — empty when unavailable. Words are the
     *  normalized form. **Blocking — never on Main.** */
    fun userWords(): LinkedHashSet<String> = try {
        store()?.userWords() ?: LinkedHashSet()
    } catch (e: Exception) {
        LinkedHashSet()
    }

    /** Add [word] (already normalized) to the user dictionary. Re-adding is not an error and
     *  does not move the word. Answers whether the word is stored — the caller's in-memory
     *  mirror updates regardless (the session should honor the vouch even if it will not
     *  outlive it). **Blocking — never on Main.** */
    fun addUserWord(word: String): Boolean = try {
        store()?.addUserWord(word) ?: false
    } catch (e: Exception) {
        false
    }

    /** Remove [word] from the user dictionary — a hard drop, effective immediately.
     *  **Blocking — never on Main.** */
    fun removeUserWord(word: String) {
        try {
            store()?.removeUserWord(word)
        } catch (e: Exception) {
            // Unavailable: the removal holds for this showing via the caller's mirror.
        }
    }

    // ── Where the writer left off ─────────────────────────────────────────────

    /** The caret last seen on [pageKey]'s document, or 0 — the top — when we have never seen it.
     *  **Blocking — never on Main.** */
    fun caret(pageKey: String): Int = try {
        store()?.caret(pageKey) ?: 0
    } catch (e: Exception) {
        0
    }

    /** Record where the caret is on [pageKey]. **Blocking — never on Main.** */
    fun rememberCaret(pageKey: String, offset: Int) {
        try {
            store()?.rememberCaret(pageKey, offset)
        } catch (e: Exception) {
            // Unavailable: the next open lands at the top, which is where it lands anyway when
            // nothing is known.
        }
    }

    /** [rememberCaret] from Main — fire and forget, on the single-lane scope so the leave path's
     *  newer caret is never overtaken by an earlier launch. */
    fun rememberCaretAsync(pageKey: String, offset: Int) {
        if (pageKey.isEmpty()) return
        scope.launch { rememberCaret(pageKey, offset) }
    }

    // ── The store for this call ───────────────────────────────────────────────

    /** The showing's store, wrapped — or null when there is no showing. **Fetched per call**: a
     *  host restart lends a new binder, and a cached one would throw for the rest of the showing. */
    private fun store(): EditorStore? = EditorSession.store?.let { EditorStore(it) }
}
