package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The plumbing half of a sketch page's saves (arc 43 / K5): timers, threads and the binder. Every
 * *decision* it makes is [SketchSaveGovernor]'s and every *rule about pixels it could not deliver*
 * is [PendingPngPark]'s — this class owns only the parts that need Android, which is what keeps the
 * interesting logic testable.
 *
 * The shape, and why each piece is where it is:
 *
 * - **The copy is taken on Main, at the moment of the trigger.** `getPageRaster()` is a main-thread
 *   copy of the engine's page image, and the engine only ever touches that image on Main (the
 *   composite at pen-up, each batch of a rub, a load) — so a copy can never catch a half-written
 *   composite. Everything after that point works on an immutable bitmap.
 * - **The encode and the push run on IO, one at a time** (the [Mutex]). `saveSketchChunk` is a
 *   blocking Binder call and the host accumulates chunks per save — two overlapping streams would
 *   interleave on one accumulator and commit an image that was never drawn.
 * - **The bookkeeping runs back on Main**, so `dirty` and the in-flight flag are read and written
 *   from one thread only.
 * - **A failure never advances anything.** The bytes are parked in [SketchSession.pending], the
 *   retry is re-armed, and the page stays dirty. Only counts, durations and exception class names
 *   are logged — never a pixel, on any path.
 *
 * **The debounce goes through the pen-idle gate.** Three seconds after the last change, and then
 * only once the pen is off the glass: the copy is a page-sized `memcpy` on the main thread and the
 * encode is hundreds of milliseconds, neither of which belongs in the middle of a stroke. Nothing
 * rides on the timer alone — a page turn, `onPause`, Back and Show pages each flush what is
 * outstanding — so the debounce decides *when* a save happens while drawing goes on, never whether
 * one happens at all.
 *
 * The coroutine scope is deliberately **not** cancelled when the screen goes: a save armed by
 * `onPause` must land even though the Activity is on its way out, and the extension process outlives
 * the screen for exactly as long as the host holds its bind.
 */
class SketchSaver(
    /** Copies the page off the surface — `paper.getPageRaster()`. **Main thread only**; null is a
     *  blank page, which saves as an empty array (the wire form of "no sketch here"). */
    private val copyPage: () -> Bitmap?,
    /** Suspends until the pen is off the glass — `paper.awaitPenIdle()`. **Main thread only.** */
    private val awaitPenIdle: suspend () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushLock = Mutex()
    private val governor = SketchSaveGovernor()

    /** The page every save goes to, set at each load. Null before the first one — and a save with
     *  no page is not a save that goes to the wrong place, it is no save at all. */
    @Volatile
    var pageKey: String? = null

    /** Set once the screen is leaving: the timers stay down and no new debounce may arm. */
    private var leaving = false

    private var debounce: Job? = null
    private var retry: Job? = null

    /** Whether the page on the glass holds something the host has not been given. */
    val isDirty: Boolean get() = governor.dirty

    // ── What the screen says ──────────────────────────────────────────────────

    /** The engine reported a change to the page image. */
    fun markDirty() = governor.markDirty()

    /** A page was just loaded: what is on the glass is what is on disk. */
    fun markClean() = governor.markClean()

    /** Restart the idle timer; a burst of marks coalesces into one write. */
    fun schedule() {
        if (leaving) return
        debounce?.cancel()
        debounce = scope.launch(Dispatchers.Main) {
            delay(SAVE_DEBOUNCE_MS)
            awaitPenIdle()
            saveNow()
        }
    }

    /** Drop both timers — the screen is gone, or a save is happening right now instead. */
    fun cancelTimers() {
        debounce?.cancel(); debounce = null
        retry?.cancel(); retry = null
    }

    /**
     * A save trigger: the debounce, `onPause`, a retry, a reconnect. Snapshots the page and asks the
     * governor what to do with it. **Main thread only.**
     */
    fun saveNow() {
        if (leaving) return
        cancelTimers()
        val key = pageKey ?: return
        if (governor.request() !is SketchSaveGovernor.SaveAction.Save) return
        startPush(key)
    }

    /**
     * **Main thread**, and only ever after the governor has answered [SketchSaveGovernor.SaveAction.Save]
     * — the flag is already cleared and the push already claimed, so this takes the copy and goes.
     * A mark that arrives while it is in the air is answered by [SketchSaveGovernor.onSaved], which
     * hands back another `Save` and lands here again: after the push in flight, never beside it.
     */
    private fun startPush(key: String) {
        val copy = takeCopy() ?: run { armRetry(); return }
        scope.launch {
            val error = pushCopy(key, copy.bitmap)
            withContext(Dispatchers.Main) {
                if (error == null) {
                    if (governor.onSaved() is SketchSaveGovernor.SaveAction.Save) {
                        pageKey?.let { startPush(it) }
                    }
                } else {
                    governor.onFailed()
                    armRetry()
                }
            }
        }
    }

    /**
     * The leave flush — Back, Show pages, `end()`. Awaits anything in flight (the push lock is the
     * queue, and it is FIFO, so this lands **after** an older push rather than beside it) and then
     * pushes the page if it is dirty.
     *
     * Returns **true** when nothing is owed. A false is a drawing that has no other copy, and the
     * screen says so with a two-button dialog rather than leaving quietly.
     */
    suspend fun flushForExit(): Boolean {
        leaving = true
        return flushAndAwait()
    }

    /**
     * The same flush **without** declaring the screen gone — what a page turn and an undo replay's
     * walk take before they leave a page behind. The host's read window is about to move, so this
     * page's pixels have to be on disk first; but the screen is staying, so the debounce may arm
     * again on the page that arrives.
     */
    suspend fun flushAndAwait(): Boolean {
        cancelTimers()
        val key = pageKey ?: return true
        // `owed` is what tells a page with nothing to write apart from one whose copy could not be
        // taken: both answer no bitmap, and only the second is a drawing at risk.
        var owed = false
        val copy = withContext(Dispatchers.Main + NonCancellable) {
            if (governor.flushRequest() !is SketchSaveGovernor.SaveAction.Save) return@withContext null
            takeCopy().also { if (it == null) owed = true }
        } ?: return !owed
        val error = withContext(NonCancellable) { pushCopy(key, copy.bitmap) }   // pushCopy hops to IO itself
        withContext(Dispatchers.Main + NonCancellable) {
            if (error == null) governor.onSaved() else governor.onFailed()
        }
        return error == null
    }

    /** Re-offer whatever is parked, under its own key — a host reconnect, or a retry beat. **Main
     *  thread.** A push that fails again re-parks; nothing is lost here. */
    fun retryParked() {
        val parked = SketchSession.pending.take() ?: return
        scope.launch {
            val error = pushPng(parked.pageKey, parked.png)
            if (error != null) Slog.d(TAG) { "parked save still could not be pushed: ${error.javaClass.simpleName}" }
        }
    }

    // ── The teardown hooks (Binder thread) ───────────────────────────────────

    /**
     * [SketchSession.FlushHook.flushBlocking]'s body: the service's `end()` on a **Binder thread**,
     * blocking until the page has landed or been parked. Never throws.
     *
     * `runBlocking` here is correct and not the forbidden kind: this is a pooled Binder thread, not
     * Main, and the host's `end()` call returning is precisely what tells it the flush is done.
     */
    fun flushBlocking() {
        try {
            runBlocking { flushForExit() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "teardown flush failed: ${t.javaClass.simpleName}")
        }
    }

    /** [SketchSession.FlushHook.pushBlocking]'s body: exactly these bytes, through the same lock
     *  every other save takes. Throws on failure — the caller decides. */
    fun pushBlocking(pageKey: String, png: ByteArray) {
        runBlocking { pushLock.withLock { pushChunks(pageKey, png) } }
    }

    // ── The push ──────────────────────────────────────────────────────────────

    /** The copy, taken on Main. A null bitmap is a blank page, which is a save like any other. */
    private class Snapshot(val bitmap: Bitmap?)

    /** **Main thread.** The page copy, or null when it could not be taken (a page-sized allocation
     *  on a device short of exactly that — the page stays dirty and the caller retries). */
    private fun takeCopy(): Snapshot? = try {
        Snapshot(copyPage())
    } catch (t: Throwable) {
        governor.onCopyFailed()
        Log.e(TAG, "the page could not be copied for saving; it stays dirty and will be tried again", t)
        null
    }

    /**
     * Encode [copy] and push it; recycles the copy whatever happens. Returns the failure or null.
     *
     * **It puts itself on IO** rather than trusting its caller's dispatcher. Two of the three
     * callers already run there, but the leave flush ([flushAndAwait]) is awaited from the screen's
     * own Main-dispatched page-op lock — and a page encode plus a chunked Binder push on the main
     * thread is the ANR this whole class exists to avoid.
     */
    private suspend fun pushCopy(key: String, copy: Bitmap?): Throwable? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val png = try {
            RasterImage.encode(copy)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "the page could not be encoded; it stays dirty", t)
            return@withContext t
        } finally {
            copy?.recycle()
        }
        val encoded = SystemClock.elapsedRealtime() - t0
        val error = pushPng(key, png)
        Slog.d(TAG) {
            "save: ${png.size} B encoded in $encoded ms, pushed in " +
                "${SystemClock.elapsedRealtime() - t0 - encoded} ms${if (error == null) "" else " — FAILED"}"
        }
        error
    }

    /** Push [png] under the lock, parking it on failure and clearing its park on success. Never on
     *  Main — the chunk stream is blocking Binder calls. */
    private suspend fun pushPng(key: String, png: ByteArray): Throwable? = try {
        pushLock.withLock { pushChunks(key, png) }
        SketchSession.pending.clear(key)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // The pixels have no other copy: they are held for `end()` to re-push while the host binder
        // is still valid. A displaced park is two failures in a row and is worth a line.
        SketchSession.pending.park(key, png)?.let {
            Log.w(TAG, "a parked sketch for another page was displaced and dropped (${png.size} B held now)")
        }
        Log.w(TAG, "sketch save failed: ${t.javaClass.simpleName} (${png.size} B parked)")
        t
    }

    /** Blocking Binder calls — never from Main. */
    private fun pushChunks(key: String, png: ByteArray) {
        val host = SketchSession.host ?: throw IllegalStateException("no showing")
        SketchPush.push(host, key, png)
    }

    /** **Main thread.** Re-arm the retry beat after a failure. */
    private fun armRetry() {
        if (leaving) return
        retry?.cancel()
        retry = scope.launch(Dispatchers.Main) {
            delay(RETRY_DELAY_MS)
            saveNow()
        }
    }

    companion object {
        private const val TAG = "SketchSaver"

        /** Quiet time before a page is written — Paintsprout's three seconds, and its reason: a
         *  rubbing sweep reports a change dozens of times a second and a page encode is not cheap. */
        const val SAVE_DEBOUNCE_MS = 3_000L

        /** A failed push waits this long before trying again — the usual cause is a host that is
         *  restarting, and its `.soil` open is asynchronous. */
        const val RETRY_DELAY_MS = 2_000L
    }
}
