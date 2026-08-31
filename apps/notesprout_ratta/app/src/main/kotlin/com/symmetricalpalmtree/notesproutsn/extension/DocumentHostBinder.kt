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

        /**
         * M6: move the target to the neighbouring page and load [session]'s window with its
         * document — or its freshly recognized seed (`seeded = true`, watermark parked). null =
         * no page in that direction or the load failed; **the target must not have moved** and
         * the window must be exactly what it was.
         */
        fun requestPage(session: DocumentHostSession, direction: Int): DocumentPageState?

        /**
         * M6: recognize the current target page and load [session]'s window with the result
         * (`seeded = true`), parking the watermark read before recognition. The document row is
         * not touched. Recognition unavailable throws `IllegalStateException` carrying exactly
         * [DocumentContract.SEED_UNAVAILABLE].
         */
        fun requestSeed(session: DocumentHostSession, mode: Int): DocumentPageState

        /**
         * M7: switch the target between the page document and the notebook document — a flip's
         * contract ([requestPage]'s null safety net) with the first-toggle auto-merge inside.
         * null = nothing moved (failure, a cancelled merge, or the scope already current).
         */
        fun requestScope(session: DocumentHostSession, scope: Int): DocumentPageState?

        /**
         * M7: the notebook-wide merge into the window (`seeded = true`, notebook watermark
         * parked; document row untouched) — [requestSeed]'s throw-not-null asymmetry. A cancel
         * is `IllegalStateException` carrying exactly [DocumentContract.MERGE_CANCELLED]; a merge
         * with nothing to give answers an empty un-seeded window.
         */
        fun requestMerge(session: DocumentHostSession, mode: Int): DocumentPageState

        /** M7: the editor's Cancel — flag the in-flight merge to abandon between pages. */
        fun cancelRequest()

        /**
         * M8, text documents only: rename the notebook from the edited title. The implementation
         * validates (the name rules, sibling collisions, and that this notebook IS a text
         * document) and refuses with `IllegalArgumentException` — its message is shown as the
         * refusal reason, so it must never carry a path or document text.
         */
        fun renameNotebook(name: String)

        /**
         * M8, text documents only: record how the showing should end —
         * [DocumentContract.CLOSE_SHOW_PAGES] / [DocumentContract.CLOSE_TO_LIBRARY]. Advisory
         * state the host reads when the result lands; the editor still finishes normally.
         * Refused with `IllegalArgumentException` when the notebook is not a text document.
         */
        fun closeNotebook(mode: Int)
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

    // ── M6's two (flips, Bring in), M7's three (scope, merge, cancel), M8's two (rename,
    //    close). Every one is gated first: a stranger must never learn which calls exist by
    //    the exception it gets back.

    override fun requestPage(direction: Int): DocumentPageState? {
        gate()
        require(direction == DocumentContract.PAGE_PREV || direction == DocumentContract.PAGE_NEXT) {
            "unknown direction $direction"
        }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.requestPage(session, direction) }
        Slog.d(TAG) {
            if (state == null) "requestPage($direction): no page / load failed " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
            else "requestPage($direction): → page ${state.pageIndex + 1}/${state.pageCount}, " +
                "${state.textChars} chars${if (state.seeded) " (seeded)" else ""} " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun requestScope(scope: Int): DocumentPageState? {
        gate()
        require(scope == DocumentContract.SCOPE_PAGE || scope == DocumentContract.SCOPE_NOTEBOOK) {
            "unknown scope $scope"
        }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.requestScope(session, scope) }
        Slog.d(TAG) {
            if (state == null) "requestScope($scope): stayed " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
            else "requestScope($scope): ${state.textChars} chars" +
                "${if (state.seeded) " (seeded)" else ""} in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun requestSeed(mode: Int): DocumentPageState {
        gate()
        require(mode == DocumentContract.BRING_REPLACE || mode == DocumentContract.BRING_APPEND) {
            "unknown mode $mode"
        }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.requestSeed(session, mode) }
        Slog.d(TAG) {
            "requestSeed($mode): ${state.textChars} chars in ${state.textChunks} chunk(s) " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun requestMerge(mode: Int): DocumentPageState {
        gate()
        require(mode == DocumentContract.BRING_REPLACE || mode == DocumentContract.BRING_APPEND) {
            "unknown mode $mode"
        }
        val t0 = SystemClock.elapsedRealtime()
        val state = hook { hooks.requestMerge(session, mode) }
        Slog.d(TAG) {
            "requestMerge($mode): ${state.textChars} chars in ${state.textChunks} chunk(s) " +
                "in ${SystemClock.elapsedRealtime() - t0} ms"
        }
        return state
    }

    override fun cancelRequest() {
        gate()
        hook { hooks.cancelRequest() }
        Slog.d(TAG) { "cancelRequest" }
    }

    override fun renameNotebook(name: String?) {
        gate()
        requireNotNull(name) { "name is null" }
        require(name.length <= DocumentContract.MAX_TITLE_CHARS) {
            "name over ${DocumentContract.MAX_TITLE_CHARS} chars"
        }
        val t0 = SystemClock.elapsedRealtime()
        hook { hooks.renameNotebook(name) }
        // The name is user content — length only, never the text.
        Slog.d(TAG) { "renameNotebook: ${name.length} chars in ${SystemClock.elapsedRealtime() - t0} ms" }
    }

    override fun closeNotebook(mode: Int) {
        gate()
        require(
            mode == DocumentContract.CLOSE_SHOW_PAGES || mode == DocumentContract.CLOSE_TO_LIBRARY,
        ) { "unknown close mode $mode" }
        hook { hooks.closeNotebook(mode) }
        Slog.d(TAG) { "closeNotebook: mode=$mode" }
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
