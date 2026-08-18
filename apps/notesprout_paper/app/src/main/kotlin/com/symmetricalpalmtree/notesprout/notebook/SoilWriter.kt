package com.symmetricalpalmtree.notesprout.notebook

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
 * The one serial IO writer of an open `.soil` (arc 4 / H1 — lifted out of [StrokeStore] so
 * [ObjectStore] shares it): a [Channel] of jobs applied in enqueue order on one coroutine, so a
 * stroke commit, an object create and an erase can never race each other. Both stores enqueue here;
 * nothing else writes to the notebook table while the screen is open.
 *
 * The `updatedAt` discipline lives here too: every enqueued write schedules a trailing-debounced
 * (2 s) [onEdited] — the notebook's index row bump — one UPDATE per burst, [flushTouch]ed on close.
 * [drain] waits for everything queued so far — call it before sealing and before an undo replay
 * reads the rows back.
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

    /** Queue a write. Dropped (with a warning) once [close]d. */
    fun enqueue(job: suspend () -> Unit) {
        val r = queue.trySend(job)
        if (r.isFailure) Log.w(TAG, "write dropped: writer closed")
        else scheduleTouch()
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

    /** Flush a pending debounced bump now (close path). */
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
