package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Binder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * The `ISketchHost` stub the host mints per showing (arc 43 / K4) — **SN's second host-side stub on
 * an extension seam**, after [DocumentHostBinder], and built to its recipe exactly: the signature
 * was checked at discovery and re-checked at bind ([ExtensionBinder.hold]), and what is left to
 * enforce per call is that the caller is the uid we bound to and that the showing has not been
 * revoked. That is [gate], and it is **the first statement of every method** — a stranger must
 * never learn which calls exist from the exception it gets back.
 *
 * **Thin on purpose.** Everything worth getting right beyond uid gating — the read window, the ink
 * window, the ordered save accumulator and its caps — lives in [SketchHostSession], which has no
 * Android types and is pinned by JVM tests. This class is the Binder shell around it plus the
 * hooks into the open notebook.
 *
 * **The hooks are blocking, and that is deliberate.** They run on the arbitrary pooled **Binder
 * thread** the extension's call arrived on, never on Main, so an implementation is free to
 * `runBlocking` over the suspending DAO work the `.soil` needs. A Binder transaction cannot be
 * cancelled, so the extension's own timeout is the only clock either hook runs against.
 *
 * **Only marshalable exceptions leave.** `SecurityException` / `IllegalArgumentException` /
 * `IllegalStateException` (and `UnsupportedOperationException`, the J3 precedent) cross Binder as
 * themselves; anything else kills the transaction *silently* and the extension reads the empty
 * reply as success. So every hook invocation is funnelled: an unexpected `Throwable` becomes
 * `IllegalStateException(e.javaClass.simpleName)` — the class name and nothing else, because a
 * message here could carry a path. The two **typed** refusals ([SketchContract.SKETCH_TOO_LARGE],
 * [SketchContract.SKETCH_BAD_PNG]) are `IllegalStateException`s and therefore cross intact, which
 * is the whole point of them being that type.
 *
 * **Pixels are never logged.** Counts, byte totals and durations only.
 */
class SketchHostBinder(
    /** The bound extension's uid ([android.content.pm.PackageManager.getPackageUid]) — the only
     *  caller any method here answers. */
    private val extUid: Int,
    /** The showing's pure state machine; [revoke] clears it. */
    private val session: SketchHostSession,
    private val hooks: Hooks,
) : ISketchHost.Stub() {

    /**
     * The things this binder cannot do itself: read the open notebook's current page, move that
     * page, write a page's pixels, stage a page's bare ink, and — since K5b — insert a page, delete
     * one, say what else a page is carrying, and take one of those two back or put it back. All of
     * them are **blocking** and all of them run on a Binder thread (see the class doc).
     */
    interface Hooks {
        /**
         * The current target page's state, with its stored PNG loaded into [session]'s read window
         * ([SketchHostSession.setWindow]). The window and the state must be loaded together — the
         * contract says the reply is atomic with the pixels it describes, and `setWindow` is what
         * makes that true.
         */
        fun loadCurrent(session: SketchHostSession): SketchPageState

        /**
         * Move the target one page in [direction] ([SketchContract.PAGE_PREV] /
         * [SketchContract.PAGE_NEXT]) and load [session]'s window with that page's pixels. **At
         * either edge the SAME page is answered, unchanged** — a page turn is never an exception
         * and never a null the extension has to word; it compares `pageKey` and stays put.
         */
        fun requestPage(session: SketchHostSession, direction: Int): SketchPageState

        /** Persist a completed save. Empty bytes are a clear — the repository owns that rule — and
         *  the typed refusals come back out of here as themselves. */
        fun commit(commit: SketchHostSession.Commit)

        /**
         * "Bring in ink" (decision 8): stage the page [pageKey] names' **bare** strokes in
         * [session]'s ink window ([SketchHostSession.setInkWindow]) and answer the chunk count.
         * Zero is a legal answer and the only one for a page with no bare ink.
         */
        fun requestInk(session: SketchHostSession, pageKey: String): Int

        /**
         * K5b: insert a blank page next to **the face's target** on the side [direction] names, move
         * the target onto it, and load [session]'s window with it (empty — a page minted a moment
         * ago has no pixels). The insert is one entry on the **notebook's own** undo stack, so the
         * notebook's undo takes it back exactly as if the notebook had made it.
         */
        fun insertPage(session: SketchHostSession, direction: Int): SketchPageState

        /**
         * K5b: soft-delete the page [pageKey] names — which must be the face's current target — the
         * notebook's way (the page and every live descendant it owns: strokes, objects, document and
         * sketch), one entry on the **notebook's own** undo stack. Deleting the only page answers a
         * fresh blank page instead of an empty notebook. The target moves to the page the notebook
         * lands on and [session]'s window is loaded with it.
         */
        fun deletePage(session: SketchHostSession, pageKey: String): SketchPageState

        /**
         * K5b: what else the live page [pageKey] names is carrying — a bit set of
         * [SketchContract.PAGE_HAS_INK] and [SketchContract.PAGE_HAS_DOCUMENT], so the delete confirm
         * can name what goes with the page rather than warn about content that is not there. Touches
         * no window: it is a question, not a move.
         */
        fun pageContent(pageKey: String): Int

        /**
         * K5b: take back the page insert or delete [token] names — the **notebook's own** undo arm,
         * run from the face — and move that edit from the notebook's undo stack to its redo stack.
         * A token this showing does not know is an `IllegalArgumentException`. [session]'s window is
         * loaded with the page the notebook lands on.
         */
        fun undoPage(session: SketchHostSession, token: String): SketchPageState

        /** K5b: [undoPage]'s mirror — the notebook's own redo arm, and the edit goes back onto the
         *  notebook's undo stack. */
        fun redoPage(session: SketchHostSession, token: String): SketchPageState
    }

    /**
     * Flipped in the client's `finally`, alongside the unbind. `@Volatile` because the flip happens
     * on the caller's thread and the read happens on whichever Binder thread the extension's next
     * call lands on.
     */
    @Volatile
    private var revoked = false

    /** After this every method throws `SecurityException`, and the showing's state is dropped — a
     *  revoked binder must not leave a page of pixels sitting in the accumulator. */
    fun revoke() {
        revoked = true
        session.clear()
    }

    // ── The read direction ──────

    override fun current(): SketchPageState {
        gate()
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.loadCurrent(session) }
        Slog.d(TAG) {
            "current: page ${state.pageIndex + 1}/${state.pageCount}, ${state.sketchBytes} B " +
                "in ${state.sketchChunks} chunk(s) in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun requestPage(direction: Int): SketchPageState {
        gate()
        require(direction == SketchContract.PAGE_PREV || direction == SketchContract.PAGE_NEXT) {
            "unknown direction $direction"
        }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.requestPage(session, direction) }
        Slog.d(TAG) {
            "requestPage($direction): → page ${state.pageIndex + 1}/${state.pageCount}, " +
                "${state.sketchBytes} B in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun readSketchChunk(chunkIndex: Int): ByteArray {
        gate()
        // Pure session work — an index outside the window is an IllegalArgumentException from it,
        // which is already one of the three that cross intact.
        return session.readChunk(chunkIndex)
    }

    // ── The write direction ──────

    override fun saveSketchChunk(pageKey: String?, chunkIndex: Int, chunk: ByteArray?, last: Boolean) {
        gate()
        requireNotNull(pageKey) { "pageKey is null" }
        requireNotNull(chunk) { "chunk is null" }
        val commit = session.acceptChunk(pageKey, chunkIndex, chunk, last) ?: return
        val t0 = SystemClock.elapsedRealtime()
        hook { hooks.commit(commit) }
        Slog.d(TAG) {
            "saveSketchChunk: committed ${commit.png.size} B over ${chunkIndex + 1} chunk(s) " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
    }

    // ── "Bring in ink" ──────

    override fun requestInk(pageKey: String?): Int {
        gate()
        requireNotNull(pageKey) { "pageKey is null" }
        val t0 = SystemClock.elapsedRealtime()
        val chunks = hook { hooks.requestInk(session, pageKey) }
        Slog.d(TAG) { "requestInk: $chunks chunk(s) in ${SystemClock.elapsedRealtime() - t0} ms" }
        return chunks
    }

    override fun readInkChunk(chunkIndex: Int): MutableList<WireStroke> {
        gate()
        // An index outside the staged window is the session's IllegalArgumentException. The copy is
        // the AIDL's shape (a typed list out), not a defensive one.
        return ArrayList(session.readInkChunk(chunkIndex))
    }

    // ── Page structure (K5b) ──────

    override fun insertPage(direction: Int): SketchPageState {
        gate()
        require(direction == SketchContract.PAGE_PREV || direction == SketchContract.PAGE_NEXT) {
            "unknown direction $direction"
        }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.insertPage(session, direction) }
        Slog.d(TAG) {
            "insertPage($direction): → page ${state.pageIndex + 1}/${state.pageCount} " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun deletePage(pageKey: String?): SketchPageState {
        gate()
        requireNotNull(pageKey) { "pageKey is null" }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.deletePage(session, pageKey) }
        Slog.d(TAG) {
            "deletePage: → page ${state.pageIndex + 1}/${state.pageCount}, ${state.sketchBytes} B " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun pageContent(pageKey: String?): Int {
        gate()
        requireNotNull(pageKey) { "pageKey is null" }
        val bits = hook { hooks.pageContent(pageKey) }
        Slog.d(TAG) { "pageContent: $bits" }
        return bits
    }

    // A token is a host-minted word ("s7") and names nothing about what the person drew or wrote,
    // so it may be logged — the one string on this binder that may.
    override fun undoPage(token: String?): SketchPageState {
        gate()
        requireNotNull(token) { "token is null" }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.undoPage(session, token) }
        Slog.d(TAG) {
            "undoPage($token): → page ${state.pageIndex + 1}/${state.pageCount}, ${state.sketchBytes} B " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun redoPage(token: String?): SketchPageState {
        gate()
        requireNotNull(token) { "token is null" }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.redoPage(session, token) }
        Slog.d(TAG) {
            "redoPage($token): → page ${state.pageIndex + 1}/${state.pageCount}, ${state.sketchBytes} B " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    // ── The gate and the funnel ──────

    /** The first statement of every method: the caller is the extension we bound to, and the
     *  showing is still live. Anything else — another app, or anything at all after the revoke —
     *  gets the one marshalable refusal, with a message that says nothing about what is here. */
    private fun gate() {
        if (revoked || Binder.getCallingUid() != extUid) throw SecurityException("not the bound extension")
    }

    /**
     * Run a hook, letting only the marshalable set out. The replacement carries the failing class's
     * simple name and **nothing else**: a hook's own message could hold a file path, and this
     * message crosses a process boundary.
     */
    private inline fun <T> hook(block: () -> T): T =
        try {
            block()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(e.javaClass.simpleName)
        }

    private companion object {
        const val TAG = "SketchHostBinder"
    }
}
