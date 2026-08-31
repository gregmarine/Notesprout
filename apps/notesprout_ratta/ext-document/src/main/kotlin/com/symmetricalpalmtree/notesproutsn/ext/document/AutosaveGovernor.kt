package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * The autosave state machine — **pure Kotlin, no Android types at all**, so every rule that decides
 * whether a document is written can be pinned by a plain JUnit test instead of by a device walk.
 *
 * The editor's saves cross a process boundary (`IDocumentHost.saveChunk`), which makes three things
 * true at once and is exactly why the decision lives apart from the plumbing:
 *
 * - **A save can fail** — the host may have died, its binder may be revoked, its accumulator may
 *   refuse a chunk. A failure must leave the buffer *dirty*, never quietly "saved".
 * - **A save takes time.** Typing continues underneath it, so a newer snapshot may arrive while one
 *   is in flight. The newer one wins, and it wins **after** the one in flight finishes — two
 *   overlapping chunk pushes on one accumulator would interleave and corrupt the document.
 * - **Most triggers have nothing to do.** Every mode switch, `onPause` and Done asks for a save;
 *   the overwhelming majority of those are of text that is already on disk. [savedText] is what
 *   makes those free (the host drops unchanged writes too — this is the belt to that braces).
 *
 * The governor never times anything. Debounce, retry delay and the main-thread hop are the caller's,
 * because they are Android and this is not.
 */
class AutosaveGovernor {

    /** The last text a push is known to have *landed*. Null until the first success (a document
     *  pulled from the host counts — see [markLoaded] — because that text is by definition what the
     *  host already holds). */
    var savedText: String? = null
        private set

    /** True while a push is between [request] and [onSaved]/[onFailed]. */
    var isPushing: Boolean = false
        private set

    /** A snapshot that arrived while a push was in flight, waiting its turn. */
    private var queued: String? = null

    /** The text the host handed over at load: already saved, by definition. */
    fun markLoaded(text: String) {
        savedText = text
    }

    /**
     * Whether [text] differs from what the host is known to hold. This is the *whole* definition of
     * dirty here — no separate flag to fall out of step with the buffer.
     */
    fun isDirty(text: String): Boolean = text != savedText

    /**
     * A trigger fired with [text] as the buffer's snapshot. The answer says what the caller should
     * do next and nothing else — no work is started here.
     */
    fun request(text: String): SaveAction {
        if (isPushing) {
            // ALWAYS queue the newest snapshot while a push is in flight — dirty or not. This
            // snapshot supersedes anything queued, and its dirtiness cannot be judged yet:
            // [savedText] is about to become the in-flight push's text, against which a
            // "clean-looking" snapshot (an undo back to the last save) is dirty again. Judging
            // here left an undone snapshot queued and pushed it over the undo (the M11 find).
            queued = text
            return SaveAction.Wait
        }
        if (!isDirty(text)) {
            queued = null   // nothing in flight, so any leftover queue is stale by definition
            return SaveAction.Idle
        }
        isPushing = true
        return SaveAction.Push(text)
    }

    /**
     * A **Bring in's** trigger (M6): push [text] even when it matches [savedText]. og's rule — both
     * Bring in choices re-anchor `srcUpdatedAt` to the state just recognized, *even when the draft
     * came out identical*, because the re-anchoring is the whole act. The ordinary [request] would
     * drop the unchanged text and the parked watermark would never be consumed: the strip would
     * claim "drafted from this page" while the row still carried the old anchor.
     *
     * A push already in flight queues it like any other; the queued copy can still be dropped by
     * [onSaved]'s unchanged check — an accepted edge (two overlapping Bring ins), the next one
     * re-parks and re-anchors.
     */
    fun requestDraft(text: String): SaveAction {
        if (isPushing) {
            queued = text
            return SaveAction.Wait
        }
        isPushing = true
        return SaveAction.Push(text)
    }

    /**
     * The push of [text] landed. [savedText] advances **only here** — a push that threw leaves it
     * where it was, which is what keeps the next debounce writing the same words again.
     */
    fun onSaved(text: String): SaveAction {
        savedText = text
        isPushing = false
        val next = queued
        queued = null
        return if (next != null) request(next) else SaveAction.Idle
    }

    /**
     * The push failed. The buffer stays dirty and the caller re-arms its retry timer; the queue is
     * dropped because the retry will snapshot the live buffer again, which is never older.
     */
    fun onFailed(): SaveAction {
        isPushing = false
        queued = null
        return SaveAction.Retry
    }

    /**
     * Drop a queued snapshot without pushing it, and stop believing a push is in flight (M6).
     *
     * The one caller is the outgoing push of a **page flip**: anything queued behind it is the
     * *outgoing* page's words, and the target is about to become another page's, so those words can
     * never be written now — but leaving [isPushing] set would make every later save queue behind a
     * push that will never finish. [savedText] is untouched: what landed, landed.
     */
    fun abandonQueue() {
        isPushing = false
        queued = null
    }

    /**
     * The reconnect rule, as a decision: the host restarted, the editor asked it what it is showing
     * now, and got back [currentKey]. Flush only when that is still **this** editor's target and the
     * buffer has something the host has not got.
     *
     * A mismatch is not an error and not a reason to write: page keys are globally unique, so a
     * different key means a different document — pushing this buffer there would be corruption.
     */
    fun shouldFlushOnReconnect(currentKey: String, targetKey: String?, text: String): Boolean =
        targetKey != null && currentKey == targetKey && isDirty(text)

    /** What a trigger should cause. */
    sealed interface SaveAction {
        /** Nothing to write (or nothing new to write). */
        data object Idle : SaveAction

        /** Start pushing this snapshot now. */
        data class Push(val text: String) : SaveAction

        /** A push is in flight; this snapshot is queued behind it and will go when it finishes. */
        data object Wait : SaveAction

        /** The push failed: keep the buffer dirty, park it, and re-arm the retry. */
        data object Retry : SaveAction
    }
}
