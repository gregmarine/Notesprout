package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Binder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * The `IDocumentHost` stub the host mints per showing (arc 19 / M3) — **SN's first host-side stub
 * on an extension seam**. Every other binder in this app is one the host *calls*; this one the
 * extension calls, which is why it carries the same trust discipline an extension's own service
 * does, mirrored: the signature was checked at discovery and re-checked at bind
 * ([ExtensionBinder.hold]), and what is left to enforce per call is that the caller is the uid we
 * bound to and that the showing has not been revoked. That is [gate], and it is the first statement
 * of every method — the [com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder]
 * recipe, in the other direction.
 *
 * **Thin on purpose.** Everything worth getting right beyond uid gating — the read window, the
 * ordered save accumulator, the target-key guard, the caps and the parked watermark — lives in
 * [DocumentHostSession], which has no Android types and is pinned by JVM tests. This class is the
 * Binder shell around it plus two hooks into the open notebook.
 *
 * **The hooks are blocking, and that is deliberate.** [Hooks.loadCurrent] and [Hooks.commit] run on
 * the arbitrary pooled **Binder thread** the extension's call arrived on, never on Main, so an
 * implementation is free to `runBlocking` over the suspending DAO work the `.soil` needs. A Binder
 * transaction cannot be cancelled, so the extension's own timeout is the only clock either hook
 * runs against.
 *
 * **Only marshalable exceptions leave.** `SecurityException` / `IllegalArgumentException` /
 * `IllegalStateException` (and `UnsupportedOperationException`, the J3 precedent — it marshals
 * intact too, and is what a not-yet-landed phase answers) cross Binder as themselves; anything else
 * kills the transaction *silently* and the extension reads the empty reply as success. So every
 * hook invocation is funnelled: an unexpected `Throwable` becomes
 * `IllegalStateException(e.javaClass.simpleName)` — the class name and nothing else, because a
 * message here could carry a path or a fragment of the user's document.
 *
 * Document text is never logged. Counts and durations only.
 */
class DocumentHostBinder(
    /** The bound extension's uid ([android.content.pm.PackageManager.getPackageUid]) — the only
     *  caller any method here answers. */
    private val extUid: Int,
    /** The showing's pure state machine; [revoke] clears it. */
    private val session: DocumentHostSession,
    private val hooks: Hooks,
) : IDocumentHost.Stub() {

    /**
     * The two things this binder cannot do itself: read the open notebook, and write to it. Both are
     * **blocking** and both run on a Binder thread (see the class doc) — an implementation over
     * suspending DAOs uses `runBlocking`, which is safe here and forbidden everywhere the UI thread
     * can reach.
     */
    interface Hooks {
        /**
         * Read the current target's document and staleness, load [session]'s read window with the
         * text ([DocumentHostSession.setWindow]) and answer the state describing it. The window and
         * the state must be loaded together — the contract says the reply is atomic with the text
         * it describes, and `setWindow` is what makes that true.
         */
        fun loadCurrent(session: DocumentHostSession): DocumentPageState

        /** Persist a completed save. Blank text is a delete — the repository owns that rule. */
        fun commit(commit: DocumentHostSession.Commit)
    }

    /**
     * Flipped in the client's `finally`, alongside the unbind and the store binder's own revoke.
     * `@Volatile` because the flip happens on the caller's thread and the read happens on whichever
     * Binder thread the extension's next call lands on.
     */
    @Volatile
    private var revoked = false

    /** After this every method throws `SecurityException`, and the showing's state is dropped —
     *  a revoked binder must not leave a document's text sitting in the accumulator. */
    fun revoke() {
        revoked = true
        session.clear()
    }

    // ── M3's four: the ones the seam is built on ──────

    override fun current(): DocumentPageState {
        gate()
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.loadCurrent(session) }
        Slog.d(TAG) {
            "current: ${state.textChars} chars in ${state.textChunks} chunk(s), source=${state.source} " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun readChunk(chunkIndex: Int): String {
        gate()
        // Pure session work — an index outside the window is an IllegalArgumentException from it,
        // which is already one of the three that cross intact.
        return session.readChunk(chunkIndex)
    }

    override fun saveChunk(
        pageKey: String?,
        chunkIndex: Int,
        chunk: String?,
        last: Boolean,
        drafted: Boolean,
    ) {
        gate()
        requireNotNull(pageKey) { "pageKey is null" }
        requireNotNull(chunk) { "chunk is null" }
        val commit = session.acceptChunk(pageKey, chunkIndex, chunk, last, drafted) ?: return
        val t0 = SystemClock.elapsedRealtime()
        hook { hooks.commit(commit) }
        Slog.d(TAG) {
            "saveChunk: committed ${commit.text.length} chars over ${chunkIndex + 1} chunk(s)" +
                "${if (commit.draftWatermark != null) " (drafted)" else ""} in ${SystemClock.elapsedRealtime() - t0} ms"
        }
    }

    // ── The calls whose phases have not landed ──────
    // `UnsupportedOperationException` marshals across Binder intact (the arc-11 / J3 precedent), so
    // an extension built ahead of the host gets an honest refusal naming the phase rather than a
    // silently-killed transaction it would read as success. Each one is still gated first: a
    // stranger must never learn which calls exist by the exception it gets back.

    override fun requestPage(direction: Int): DocumentPageState {
        gate()
        throw UnsupportedOperationException("requestPage lands in M6")
    }

    override fun requestScope(scope: Int): DocumentPageState {
        gate()
        throw UnsupportedOperationException("requestScope lands in M7")
    }

    override fun requestSeed(mode: Int): DocumentPageState {
        gate()
        throw UnsupportedOperationException("requestSeed lands in M6")
    }

    override fun requestMerge(mode: Int): DocumentPageState {
        gate()
        throw UnsupportedOperationException("requestMerge lands in M7")
    }

    override fun cancelRequest() {
        gate()
        throw UnsupportedOperationException("cancelRequest lands in M7")
    }

    override fun renameNotebook(name: String?) {
        gate()
        throw UnsupportedOperationException("renameNotebook lands in M8")
    }

    override fun closeNotebook(mode: Int) {
        gate()
        throw UnsupportedOperationException("closeNotebook lands in M8")
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
     * simple name and **nothing else**: a hook's own message could hold a file path or a slice of
     * the user's document, and this message crosses a process boundary.
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
        const val TAG = "DocumentHostBinder"
    }
}
