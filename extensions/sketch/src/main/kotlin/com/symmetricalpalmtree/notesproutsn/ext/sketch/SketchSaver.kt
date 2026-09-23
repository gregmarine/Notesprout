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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred

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
 * - **A page turn awaits the copy and nothing else** ([flushForTurn], 2026-09-19). The encode is
 *   the expensive half — 0.5–3.5 s for a real pencil page on the Nomad at `WEBP_EFFORT` 100 — and
 *   a turn that awaited it made a flip straight after drawing take seconds while a flip after a
 *   pause was instant. The copy freezes the pixels; nothing the hand does afterwards can change
 *   what will be written, so the encode and the push go on in the background while the next page
 *   loads. The **leave** flushes ([flushForExit], [flushBlocking]) still await the push itself:
 *   the host reads the row the moment they return.
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
 * - **A push is recorded under the page key its copy was taken on** ([PushTracker]), so a later
 *   *read* of that page can wait for it. A read of any other page never waits — see [awaitPushes].
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

    /** Which pages still have a push in the air, keyed by the page the copy was taken on — what
     *  lets a read of a page wait for its own write and for no other (2026-09-19). */
    private val pushes = PushTracker()

    /** The page every save goes to, set at each load. Null before the first one — and a save with
     *  no page is not a save that goes to the wrong place, it is no save at all. */
    @Volatile
    var pageKey: String? = null

    /** Set once the screen is leaving: the timers stay down, no new debounce may arm, and no
     *  completing push may start another one (which is what makes [PushTracker.awaitAll]
     *  terminate). `@Volatile` because [flushForExit] can be entered from the **Binder thread**
     *  `end()` arrives on while the completions that read it run on Main. */
    @Volatile
    private var leaving = false

    private var debounce: Job? = null
    private var retry: Job? = null

    /** When the page first became dirty since its last copy (uptime ms), or 0 — the deadline's
     *  anchor ([SketchSaveCadence]). Main thread. */
    private var dirtySince = 0L

    /** A save past its deadline waiting for the next pen lift — completed by [notePenLifted]. */
    private var penLift: CompletableDeferred<Unit>? = null

    /** Whether **either** raster on the glass holds something the host has not been given. */
    val isDirty: Boolean get() = governors.values.any { it.dirty }

    // ── What the screen says ──────────────────────────────────────────────────

    /** The engine reported a change to one of the page's rasters. */
    fun markDirty(layer: RasterLayer) = governor(layer).markDirty()

    /** A page was just loaded: what is on the glass is what is on disk — **both** rasters. */
    fun markClean() {
        for (g in governors.values) g.markClean()
        dirtySince = 0L
    }

    /** The pen left the paper — a save past its deadline copies here, between strokes. */
    fun notePenLifted() {
        penLift?.complete(Unit)
    }

    /**
     * Restart the idle timer; a burst of marks coalesces into one write — **up to the deadline**
     * ([SketchSaveCadence], arc 50 walk 4): from the first unsaved change the page has
     * [SketchSaveCadence.MAX_DIRTY_MS] before a save goes ahead whether or not the hand has paused.
     * Before it, the debounce and the pen-idle gate as always; past it, the gate is bounded — the
     * pen going idle, else the next pen lift, else the copy regardless.
     */
    fun schedule() {
        if (leaving) return
        val now = SystemClock.uptimeMillis()
        if (dirtySince == 0L) dirtySince = now
        val since = dirtySince
        debounce?.cancel()
        debounce = scope.launch(Dispatchers.Main) {
            delay(SketchSaveCadence.debounceWait(since, now))
            if (!SketchSaveCadence.pastDeadline(since, SystemClock.uptimeMillis())) {
                awaitPenIdle()
            } else {
                val idle = withTimeoutOrNull(SketchSaveCadence.IDLE_LIMIT_MS) { awaitPenIdle() }
                if (idle == null) {
                    val lift = CompletableDeferred<Unit>().also { penLift = it }
                    val lifted = withTimeoutOrNull(SketchSaveCadence.LIFT_LIMIT_MS) { lift.await() }
                    penLift = null
                    Slog.d(TAG) { "save past its deadline: ${if (lifted == null) "copying under the pen" else "copying at the pen lift"}" }
                }
            }
            saveNow()
        }
    }

    /** Drop both timers — the screen is gone, or a save is happening right now instead. */
    fun cancelTimers() {
        debounce?.cancel(); debounce = null
        retry?.cancel(); retry = null
        penLift = null
    }

    /**
     * A save trigger: the debounce, `onPause`, a retry, a reconnect. Asks **each** governor what to
     * do with its own raster and starts a push for every one that answers `Save`; the rasters
     * nothing touched cost nothing. **Main thread only.**
     */
    fun saveNow() {
        if (leaving) return
        cancelTimers()
        dirtySince = 0L
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
        launchPush(key, layer, copy)
    }

    /**
     * **Main thread.** Send [copy] on its way as [layer]'s image of the page [key] names, and record
     * the job under **that** key so a later read of that page can wait for it ([awaitPushes]) — the
     * key the copy was taken under is the key it is pushed under, whatever page the screen has moved
     * to by the time it lands.
     */
    private fun launchPush(key: String, layer: RasterLayer, copy: Snapshot) {
        val job = scope.launch {
            val error = pushCopy(key, layer, copy.bitmap, copy.copyMs)
            withContext(Dispatchers.Main) { finishPush(key, layer, error) }
        }
        pushes.track(key, job)
    }

    /**
     * **Main thread.** One push's bookkeeping, shared by the debounced save and the turn flush's
     * background push.
     *
     * The governors are about **the glass**, not about a page: by the time a background push lands
     * the screen may be on the next page, and that is exactly right — a mark that arrived during the
     * encode belongs to whatever page is showing now, so the follow-up save goes to [pageKey] and
     * not to [key]. Two consequences worth saying out loud:
     *
     * - **While leaving, a follow-up is not started but is not dropped either.** The governor's flag
     *   is put straight back (`onCopyFailed` is "still owed, nothing in flight"), so the leave
     *   flush's own `flushRequest` still finds it. Starting one here would be a push appearing
     *   *after* [PushTracker.awaitAll] had drained the last one.
     * - **A background push that fails after a turn marks the page now showing dirty.** It costs one
     *   redundant save of bytes the host already has, and it is the conservative side of the trade:
     *   the alternative is a governor that has to know which page each push was for.
     */
    private fun finishPush(key: String, layer: RasterLayer, error: Throwable?) {
        if (error != null) {
            governor(layer).onFailed()
            armRetry()
            return
        }
        if (governor(layer).onSaved() !is SketchSaveGovernor.SaveAction.Save) return
        val next = pageKey
        if (leaving || next == null) governor(layer).onCopyFailed() else startPush(next, layer)
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
        // Everything a turn left running, whatever page it was for: the binder is revoked the
        // moment we hand the pipeline back, and a push still encoding would arrive at a dead one.
        // `leaving` is already true, so nothing can start another and this drain terminates.
        pushes.awaitAll()
        return flushAndAwait()
    }

    /**
     * The same flush **without** declaring the screen gone — what a page insert, a page delete and a
     * structural replay take before they leave a page behind, and what every leave flush ends with.
     * The host's read windows are about to move, so this page's pixels have to be on disk first;
     * but the screen is staying, so the debounce may arm again on the page that arrives.
     *
     * **A page turn takes [flushForTurn] instead** (2026-09-19): a turn is the one flush point where
     * nothing reads a row immediately afterwards, so it need not pay the encode. The three that stay
     * here each have a reason to: an insert and a delete are about to change the notebook's own page
     * set, and a delete in particular must have written whatever its undo will bring back.
     *
     * Walks the rasters in [SketchLayers.all]'s order and pushes each one that is owed, in turn —
     * sequentially, because the push lock would serialise them anyway and because a flush answers
     * one boolean about the whole page.
     */
    suspend fun flushAndAwait(): Boolean {
        cancelTimers()
        val key = pageKey ?: return true
        // A turn may have left this page's own push in the air. Waiting here is what keeps "when
        // this returns, this page is on disk" true; it is a no-op on every other path.
        pushes.await(key)
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
            // pushCopy hops to IO itself
            val error = withContext(NonCancellable) { pushCopy(key, layer, copy.bitmap, copy.copyMs) }
            withContext(Dispatchers.Main + NonCancellable) {
                if (error == null) governor(layer).onSaved() else governor(layer).onFailed()
            }
            if (error != null) clean = false
        }
        return clean
    }

    /**
     * **The page turn's flush** (2026-09-19, the user's decision after his own Nomad finding: a flip
     * straight after drawing took "a few seconds", a flip after a pause was instant).
     *
     * It awaits **only the Main-thread pixel copy** of each owed raster — `flushRequest()` and
     * `getPageRaster` — and then lets the encode and the push run on IO, under the same one push
     * lock, while the next page loads. The measured halves on the Nomad say why: the copy is a
     * page-sized `memcpy` of a few milliseconds, the WebP encode at `WEBP_EFFORT` 100 is 0.5–3.5 s
     * on a real pencil page (2 792 / 3 543 / 3 310 ms on three ~1 MB pages), and the push is tens
     * of milliseconds. The copy is the half that has to happen before the paper changes; the encode
     * only has to happen before the row is read.
     *
     * **What makes that safe is [awaitPushes].** The copy freezes the pixels, so what will be
     * written is already decided; the one thing still untrue is that the page's *row* holds them. So
     * every read of a page's raster — which on this screen means `loadPage`, the only place the face
     * reads a raster back through the host — waits for that page's own pushes first, and for no
     * other page's. Everything else is unchanged: the copy is taken under [pageKey] and pushed under
     * the same key, a failure parks and re-arms exactly as before, and the leave flushes still await
     * the push itself.
     *
     * Returns **true** when nothing was owed that could not be *copied*. A false here is narrower
     * than [flushAndAwait]'s: the copy was refused (a page-sized allocation on a device short of
     * exactly that), the raster stays dirty and the retry is armed. A push that fails later parks
     * its pixels as always and the screen hears about it at the next flush or at `end()`.
     */
    suspend fun flushForTurn(): Boolean {
        cancelTimers()
        val key = pageKey ?: return true
        var copied = true
        withContext(Dispatchers.Main + NonCancellable) {
            for (layer in SketchLayers.all) {
                if (governor(layer).flushRequest() !is SketchSaveGovernor.SaveAction.Save) continue
                val copy = takeCopy(layer)
                if (copy == null) {
                    copied = false
                    armRetry()
                    continue
                }
                launchPush(key, layer, copy)
            }
        }
        return copied
    }

    /**
     * Suspend until every background push taken for the page [key] names has finished — landed, or
     * failed and parked. **A page with nothing in the air returns at once, and a different page's
     * push is never waited for**: that is the whole shape of [flushForTurn]'s safety, and the reason
     * turning away from a page just drawn on is free while turning *back* to it is not.
     *
     * Called by the screen before it reads that page's rasters back through the host.
     */
    suspend fun awaitPushes(key: String) = pushes.await(key)

    /** Whether the page [key] names still has a push in the air — the cheap question a page load
     *  asks before deciding whether it must wait and re-ask the host for its state. */
    fun isPushPending(key: String): Boolean = pushes.isPending(key)

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

    /** The copy, taken on Main, and how long taking it cost. A null bitmap is a blank raster, which
     *  is a save like any other; [copyMs] rides along only so the save's log line can name the half
     *  a page turn now awaits beside the halves it no longer does. */
    private class Snapshot(val bitmap: Bitmap?, val copyMs: Long)

    /** **Main thread.** One raster's copy, or null when it could not be taken (a page-sized
     *  allocation on a device short of exactly that — that raster stays dirty and the caller
     *  retries). */
    private fun takeCopy(layer: RasterLayer): Snapshot? = try {
        val t0 = SystemClock.elapsedRealtime()
        Snapshot(copyPage(layer), SystemClock.elapsedRealtime() - t0)
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
    private suspend fun pushCopy(
        key: String,
        layer: RasterLayer,
        copy: Bitmap?,
        copyMs: Long,
    ): Throwable? = withContext(Dispatchers.IO) {
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
        // The three halves named apart (2026-09-19): a page turn awaits the copy and nothing else,
        // so a walk can read straight off this line which part of a save it is still paying for.
        Slog.d(TAG) {
            "save (${name(layer)}): ${bytes.size} B — copy $copyMs ms, encode $encoded ms, push " +
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

        /** Quiet time before a page is written — [SketchSaveCadence.DEBOUNCE_MS], kept under its
         *  old name for the callers and docs that know it. */
        const val SAVE_DEBOUNCE_MS = SketchSaveCadence.DEBOUNCE_MS

        /** A failed push waits this long before trying again — the usual cause is a host that is
         *  restarting, and its `.soil` open is asynchronous. */
        const val RETRY_DELAY_MS = 2_000L

        /** A raster's name for a log line — a word, never a pixel. */
        private fun name(layer: RasterLayer): String = if (layer == RasterLayer.INK) "ink" else "graphite"
    }
}
