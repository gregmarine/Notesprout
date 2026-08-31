package com.symmetricalpalmtree.notesproutsn.notebook

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The session's **single** serial write queue — one IO coroutine draining a [Channel] of jobs, so
 * every row write lands in the order it was enqueued. It was [StrokeStore]'s private machinery
 * until N2; now [StrokeStore] and [HeadingStore] share it, which is what keeps a stroke soft-delete
 * and the heading row it converted into ordered as one sequence (the "single serial SoilWriter"
 * rule in the plan). There is exactly one per [NotebookSession].
 *
 * The `updatedAt` discipline: every enqueued write schedules a trailing-debounced
 * ([TOUCH_DEBOUNCE_MS]) [onEdited] (the notebook's index-row bump), so the library card's
 * "last modified" follows edits without an index write per stroke. [drain] waits for everything
 * queued so far — the seal path calls it (a write is durable the moment its row lands in the WAL;
 * a process kill loses at most the jobs still queued here).
 */
class SoilWriter(
    private val onEdited: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var touchJob: Job? = null

    init {
        scope.launch {
            for (job in queue) {
                try { job() } catch (e: Exception) { Log.e(TAG, "soil write failed", e) }
            }
        }
    }

    /** Queue one write. Safe from any thread; a closed writer drops the job with a warning.
     *  Answers whether the job was accepted — a caller that must not report success for a write
     *  that will never run (the document seam) checks it; ink callers may ignore it. */
    fun enqueue(job: suspend () -> Unit): Boolean {
        val r = queue.trySend(job)
        if (r.isFailure) Log.w(TAG, "write dropped: writer closed")
        else scheduleTouch()
        return r.isSuccess
    }

    /** Suspends until every write queued before this call has been applied. */
    suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        queue.send { done.complete(Unit) }
        done.await()
    }

    /** Stop accepting work. Anything already queued still runs; call [drain] first if it matters. */
    fun close() {
        queue.close()
    }

    private fun scheduleTouch() {
        touchJob?.cancel()
        touchJob = scope.launch {
            delay(TOUCH_DEBOUNCE_MS)
            try { onEdited() } catch (e: Exception) { Log.w(TAG, "updatedAt bump failed", e) }
        }
    }

    /** Flush a pending debounced bump now (close path — the card must not lag its last edit). */
    suspend fun flushTouch() {
        val j = touchJob ?: return
        touchJob = null
        j.cancel()
        try { onEdited() } catch (e: Exception) { Log.w(TAG, "updatedAt bump failed", e) }
    }

    private companion object {
        const val TAG = "SoilWriter"
        const val TOUCH_DEBOUNCE_MS = 2_000L
    }
}
