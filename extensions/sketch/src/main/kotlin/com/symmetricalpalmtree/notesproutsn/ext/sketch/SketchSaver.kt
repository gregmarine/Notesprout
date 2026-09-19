package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.RasterLayer
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
 * The plumbing half of a sketch page's saves (arc 43 / K5; **per raster since arc 45 "Ink" / G3**):
 * timers, threads and the binder. Every *decision* it makes is [SketchSaveGovernor]'s and every
 * *rule about pixels it could not deliver* is [PendingImagePark]'s — this class owns only the parts
 * that need Android, which is what keeps the interesting logic testable.
 *
 * **A page is two rasters and they are saved apart.** Graphite and ink are their own rows in the
 * `.soil`, their own windows and accumulators on the seam, and so their own dirty flag, their own
 * copy, their own encode and their own push here — one [SketchSaveGovernor] each, walked in
 * [SketchLayers.all]'s order, graphite first. That separation is the point of the arc's data model
 * at save time: **a pencil scribble never re-encodes the ink raster**, which on a page with a lot
 * of ink is the difference between a debounced save costing one page encode and costing two.
 *
 * The rest of the shape, and why each piece is where it is:
 *
 * - **The copy is taken on Main, at the moment of the trigger.** `getPageRaster(layer)` is a
 *   main-thread copy of one of the engine's page images, and the engine only ever touches those
 *   images on Main (the composite at pen-up, each batch of a rub, a load) — so a copy can never
 *   catch a half-written composite. Everything after that point works on an immutable bitmap.
 * - **The encode and the push run on IO, one at a time** — *one* [Mutex] for both rasters, not one
 *   each. `saveSketchChunk` is a blocking Binder call and the host accumulates chunks per save;
 *   the lock is FIFO, so the two rasters of one page go over the wire **one after the other**,
 *   never beside each other, and two overlapping streams can never interleave.
 * - **The bookkeeping runs back on Main**, so each governor's `dirty` and in-flight flag are read
 *   and written from one thread only, and "newest wins, after" holds per raster.
 * - **A failure never advances anything.** The bytes are parked in [SketchSession.pending] under
 *   their page *and their layer*, the retry is re-armed, and that raster stays dirty — the other
 *   one is unaffected. Only counts, durations and exception class names are logged — never a
 *   pixel, on any path.
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
    /** Copies one raster off the surface — `paper.getPageRaster(layer)`. **Main thread only**; null
     *  is a blank raster, which saves as an empty array (the wire form of "no such raster here"). */
    private val copyPage: (RasterLayer) -> Bitmap?,
    /** Suspends until the pen is off the glass — `paper.awaitPenIdle()`. **Main thread only.** */
    private val awaitPenIdle: suspend () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** **One** lock for both rasters: the exclusion that matters is the host's single accumulator
     *  per save, and a FIFO queue is what makes two rasters of a page land in order. */
    private val pushLock = Mutex()

    /** One governor per raster — same class, same rules, asked separately. */
    private val governors: Map<RasterLayer, SketchSaveGovernor> =
        SketchLayers.all.associateWith { SketchSaveGovernor() }

    /** The page every save goes to, set at each load. Null before the first one — and a save with
     *  no page is not a save that goes to the wrong place, it is no save at all. */
    @Volatile
    var pageKey: String? = null

    /** Set once the screen is leaving: the timers stay down and no new debounce may arm. */
    private var leaving = false

    private var debounce: Job? = null
    private var retry: Job? = null

    /** Whether **either** raster on the glass holds something the host has not been given. */
    val isDirty: Boolean get() = governors.values.any { it.dirty }

    // ── What the screen says ──────────────────────────────────────────────────

    /** The engine reported a change to one of the page's rasters. */
    fun markDirty(layer: RasterLayer) = governor(layer).markDirty()

    /** A page was just loaded: what is on the glass is what is on disk — **both** rasters. */
    fun markClean() {
        for (g in governors.values) g.markClean()
    }

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
     * A save trigger: the debounce, `onPause`, a retry, a reconnect. Asks **each** governor what to
     * do with its own raster and starts a push for every one that answers `Save`; the rasters
     * nothing touched cost nothing. **Main thread only.**
     */
    fun saveNow() {
        if (leaving) return
        cancelTimers()
        val key = pageKey ?: return
        for (layer in SketchLayers.all) {
            if (governor(layer).request() is SketchSaveGovernor.SaveAction.Save) startPush(key, layer)
        }
    }

    /**
     * **Main thread**, and only ever after [layer]'s governor has answered
     * [SketchSaveGovernor.SaveAction.Save] — that raster's flag is already cleared and its push
     * already claimed, so this takes the copy and goes. A mark that arrives while it is in the air
     * is answered by [SketchSaveGovernor.onSaved], which hands back another `Save` and lands here
     * again: after the push in flight, never beside it. Two rasters may be in the air at once as
     * far as the bookkeeping goes; the push lock still puts them on the wire one after the other.
     */
    private fun startPush(key: String, layer: RasterLayer) {
        val copy = takeCopy(layer) ?: run { armRetry(); return }
        scope.launch {
            val error = pushCopy(key, layer, copy.bitmap)
            withContext(Dispatchers.Main) {
                if (error == null) {
                    if (governor(layer).onSaved() is SketchSaveGovernor.SaveAction.Save) {
                        pageKey?.let { startPush(it, layer) }
                    }
                } else {
                    governor(layer).onFailed()
                    armRetry()
                }
            }
        }
    }

    /**
     * The leave flush — Back, Show pages, `end()`. Awaits anything in flight (the push lock is the
     * queue, and it is FIFO, so this lands **after** an older push rather than beside it) and then
     * pushes whichever rasters are dirty.
     *
     * Returns **true** when nothing is owed **on either raster**. A false is a drawing that has no
     * other copy, and the screen says so with a two-button dialog rather than leaving quietly.
     */
    suspend fun flushForExit(): Boolean {
        leaving = true
        return flushAndAwait()
    }

    /**
     * The same flush **without** declaring the screen gone — what a page turn and an undo replay's
     * walk take before they leave a page behind. The host's read windows are about to move, so this
     * page's pixels have to be on disk first; but the screen is staying, so the debounce may arm
     * again on the page that arrives.
     *
     * Walks the rasters in [SketchLayers.all]'s order and pushes each one that is owed, in turn —
     * sequentially, because the push lock would serialise them anyway and because a flush answers
     * one boolean about the whole page.
     */
    suspend fun flushAndAwait(): Boolean {
        cancelTimers()
        val key = pageKey ?: return true
        var clean = true
        for (layer in SketchLayers.all) {
            // `owed` is what tells a raster with nothing to write apart from one whose copy could
            // not be taken: both answer no bitmap, and only the second is a drawing at risk.
            var owed = false
            val copy = withContext(Dispatchers.Main + NonCancellable) {
                if (governor(layer).flushRequest() !is SketchSaveGovernor.SaveAction.Save) return@withContext null
                takeCopy(layer).also { if (it == null) owed = true }
            }
            if (copy == null) {
                if (owed) clean = false
                continue
            }
            val error = withContext(NonCancellable) { pushCopy(key, layer, copy.bitmap) }   // pushCopy hops to IO itself
            withContext(Dispatchers.Main + NonCancellable) {
                if (error == null) governor(layer).onSaved() else governor(layer).onFailed()
            }
            if (error != null) clean = false
        }
        return clean
    }

    /**
     * Re-offer **everything** that is parked, each under its own page key and its own raster — a
     * host reconnect, or a retry beat. **Main thread.** A push that fails again re-parks; nothing is
     * lost here.
     *
     * It drains rather than taking one: after a host restart a page may owe both of its rasters,
     * and a retry that took only the graphite would leave the ink sitting in its slot until some
     * later failure displaced it.
     */
    fun retryParked() {
        while (true) {
            val parked = SketchSession.pending.take() ?: return
            scope.launch {
                val error = pushBytes(parked.pageKey, parked.layer, parked.bytes)
                if (error != null) {
                    Slog.d(TAG) { "a parked ${name(parked.layer)} raster still could not be pushed: ${error.javaClass.simpleName}" }
                }
            }
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

    /** [SketchSession.FlushHook.pushBlocking]'s body: exactly these bytes, onto exactly that
     *  raster, through the same lock every other save takes. Throws on failure — the caller
     *  decides. */
    fun pushBlocking(pageKey: String, layer: RasterLayer, bytes: ByteArray) {
        runBlocking { pushLock.withLock { pushChunks(pageKey, layer, bytes) } }
    }

    // ── The push ──────────────────────────────────────────────────────────────

    /** The copy, taken on Main. A null bitmap is a blank raster, which is a save like any other. */
    private class Snapshot(val bitmap: Bitmap?)

    /** **Main thread.** One raster's copy, or null when it could not be taken (a page-sized
     *  allocation on a device short of exactly that — that raster stays dirty and the caller
     *  retries). */
    private fun takeCopy(layer: RasterLayer): Snapshot? = try {
        Snapshot(copyPage(layer))
    } catch (t: Throwable) {
        governor(layer).onCopyFailed()
        Log.e(TAG, "the ${name(layer)} raster could not be copied for saving; it stays dirty and will be tried again", t)
        null
    }

    /**
     * Encode [copy] and push it as [layer]; recycles the copy whatever happens. Returns the failure
     * or null.
     *
     * **It puts itself on IO** rather than trusting its caller's dispatcher. Two of the three
     * callers already run there, but the leave flush ([flushAndAwait]) is awaited from the screen's
     * own Main-dispatched page-op lock — and a page encode plus a chunked Binder push on the main
     * thread is the ANR this whole class exists to avoid.
     */
    private suspend fun pushCopy(key: String, layer: RasterLayer, copy: Bitmap?): Throwable? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val bytes = try {
            RasterImage.encode(copy)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "the ${name(layer)} raster could not be encoded; it stays dirty", t)
            return@withContext t
        } finally {
            copy?.recycle()
        }
        val encoded = SystemClock.elapsedRealtime() - t0
        val error = pushBytes(key, layer, bytes)
        Slog.d(TAG) {
            "save (${name(layer)}): ${bytes.size} B encoded in $encoded ms, pushed in " +
                "${SystemClock.elapsedRealtime() - t0 - encoded} ms${if (error == null) "" else " — FAILED"}"
        }
        error
    }

    /** Push [bytes] as [layer] under the lock, parking them on failure and clearing that layer's
     *  park on success. Never on Main — the chunk stream is blocking Binder calls. */
    private suspend fun pushBytes(key: String, layer: RasterLayer, bytes: ByteArray): Throwable? = try {
        pushLock.withLock { pushChunks(key, layer, bytes) }
        SketchSession.pending.clear(key, layer)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // The pixels have no other copy: they are held for `end()` to re-push while the host binder
        // is still valid. A displaced park is two failures in a row on the same raster and is worth
        // a line.
        SketchSession.pending.park(key, layer, bytes)?.let {
            Log.w(TAG, "a parked ${name(layer)} raster for another page was displaced and dropped (${bytes.size} B held now)")
        }
        Log.w(TAG, "sketch save failed on the ${name(layer)} raster: ${t.javaClass.simpleName} (${bytes.size} B parked)")
        t
    }

    /** Blocking Binder calls — never from Main. */
    private fun pushChunks(key: String, layer: RasterLayer, bytes: ByteArray) {
        val host = SketchSession.host ?: throw IllegalStateException("no showing")
        SketchPush.push(host, key, layer, bytes)
    }

    /** **Main thread.** Re-arm the retry beat after a failure. It is one beat for the whole page —
     *  [saveNow] re-asks every governor, so whichever rasters are still owed go then. */
    private fun armRetry() {
        if (leaving) return
        retry?.cancel()
        retry = scope.launch(Dispatchers.Main) {
            delay(RETRY_DELAY_MS)
            saveNow()
        }
    }

    /** The governor of [layer] — the map is built from [SketchLayers.all], so it is total. */
    private fun governor(layer: RasterLayer): SketchSaveGovernor =
        governors[layer] ?: error("no governor for $layer")

    companion object {
        private const val TAG = "SketchSaver"

        /** Quiet time before a page is written — Paintsprout's three seconds, and its reason: a
         *  rubbing sweep reports a change dozens of times a second and a page encode is not cheap. */
        const val SAVE_DEBOUNCE_MS = 3_000L

        /** A failed push waits this long before trying again — the usual cause is a host that is
         *  restarting, and its `.soil` open is asynchronous. */
        const val RETRY_DELAY_MS = 2_000L

        /** A raster's name for a log line — a word, never a pixel. */
        private fun name(layer: RasterLayer): String = if (layer == RasterLayer.INK) "ink" else "graphite"
    }
}
