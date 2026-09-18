package com.symmetricalpalmtree.notesproutsn.ext.sketch

/**
 * The save state machine of a raster page — **pure Kotlin, no Android types at all** (arc 43 / K5,
 * the document editor's `AutosaveGovernor` recipe applied to pixels), so every rule that decides
 * whether a drawing is written can be pinned by a plain JUnit test instead of by a device walk.
 *
 * **One of these governs one raster.** A page is a graphite image and an ink image since arc 45
 * "Ink" (G3), each its own row and its own push, so [SketchSaver] holds one governor per raster and
 * asks each of them separately — which is what makes "a pencil scribble never re-encodes the ink"
 * true rather than aspirational. Nothing in this class knows that; it simply answers about the one
 * image it was given.
 *
 * The sketch's saves cross a process boundary (`ISketchHost.saveSketchChunk`), which makes three
 * things true at once and is exactly why the decision lives apart from the plumbing:
 *
 * - **A save can fail** — the host may have died, its binder may be revoked, its accumulator may
 *   refuse a chunk. A failure must leave the raster *dirty*, never quietly "saved", and its pixels
 *   parked ([PendingImagePark]): they have no other copy.
 * - **A save takes time.** A raster is encoded off the main thread and pushed in 512 KiB chunks,
 *   and the hand goes on drawing underneath it. The newest image wins, and it wins **after** the one
 *   in flight finishes — two overlapping chunk streams would interleave on the host's one
 *   accumulator and commit an image that was never drawn.
 * - **Most triggers have nothing to do.** A page turn, `onPause`, Back and Show pages all ask for a
 *   save; most of those are of an image that is already on disk, and re-encoding ~9.5 MB to write
 *   the same bytes again is the most expensive way to do nothing.
 *
 * **Dirty is a flag, not a comparison.** The editor's governor can ask "is this text what the host
 * holds"; a page of pixels cannot be compared without re-encoding it, which is the work the question
 * was meant to avoid. So the engine's own `onRasterChanged(layer, …)` is the truth ([markDirty]),
 * and the flag is cleared **when the copy is taken** rather than when the write lands — a mark arriving during
 * the encode re-dirties the raster and a second save follows it, where the other order would drop
 * it into the hole between a copy that predates it and a flag cleared after it.
 *
 * The governor never times anything. The debounce, the pen-idle gate and the retry delay are
 * [SketchSaver]'s, because they are Android and this is not.
 */
class SketchSaveGovernor {

    /** Whether this raster on the glass holds something the host has not been given. */
    var dirty: Boolean = false
        private set

    /** True while a push is between [request]/[flushRequest] and [onSaved]/[onFailed]/[onCopyFailed]. */
    var inFlight: Boolean = false
        private set

    /** The engine reported a change to this raster (a mark, a rub batch, an undo swap, a bake). */
    fun markDirty() {
        dirty = true
    }

    /** The page was just loaded from the host: what is on the glass is what is on disk. */
    fun markClean() {
        dirty = false
    }

    /**
     * An ordinary trigger — the debounce tick, a page turn, `onPause`, a retry. The answer says what
     * the caller should do next and nothing else; no work is started here.
     *
     * [SaveAction.Save] means **take the copy now**: `dirty` is cleared at this moment, because the
     * copy the caller is about to take is the state being cleared.
     */
    fun request(): SaveAction = when {
        // Newest wins, and it wins *after*: the in-flight push's own completion re-asks.
        inFlight -> SaveAction.Wait
        !dirty -> SaveAction.Idle
        else -> {
            dirty = false
            inFlight = true
            SaveAction.Save
        }
    }

    /**
     * A **leave** trigger: the final flush before Back, Show pages or `end()`.
     *
     * It ignores [inFlight] on purpose. The exclusion that matters is the push lock the caller holds
     * — a real mutex around the chunk stream — and a leave flush queued behind an in-flight push
     * lands after it, in order. Refusing here would mean leaving with the newest pixels unwritten
     * because an older push happened to still be in the air.
     */
    fun flushRequest(): SaveAction {
        if (!dirty) return SaveAction.Idle
        dirty = false
        inFlight = true
        return SaveAction.Save
    }

    /** The copy could not be taken (a page-sized allocation on a device short of exactly that). The
     *  raster stays dirty and the caller re-arms its retry; nothing was pushed. */
    fun onCopyFailed() {
        inFlight = false
        dirty = true
    }

    /**
     * The push landed. Answers [SaveAction.Save] when a mark arrived while it was in the air — the
     * caller starts that one now, after this one, never beside it.
     */
    fun onSaved(): SaveAction {
        inFlight = false
        return request()
    }

    /** The push failed. The raster is dirty again (its pixels are parked) and the caller re-arms
     *  the retry beat. */
    fun onFailed(): SaveAction {
        inFlight = false
        dirty = true
        return SaveAction.Retry
    }

    /** What a trigger should cause. */
    sealed interface SaveAction {
        /** Nothing to write. */
        data object Idle : SaveAction

        /** Take this raster's copy now and push it. */
        data object Save : SaveAction

        /** A push is in flight; this trigger is answered by that push's own completion. */
        data object Wait : SaveAction

        /** The push failed: the raster stays dirty, its pixels are parked, re-arm the retry. */
        data object Retry : SaveAction
    }
}
