package com.symmetricalpalmtree.notesproutsn.notebook

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The session's single serial `SoilWriter`: mirrors g-paper's data-out callbacks into `stroke`
 * rows. Every write goes through one serial IO coroutine (a [Channel] of jobs) so rows land in
 * callback order — a commit followed by an erase of the same stroke can never race. Reads
 * ([loadPage]) are plain suspend calls.
 *
 * The `updatedAt` discipline: every real edit schedules a trailing-debounced ([TOUCH_DEBOUNCE_MS])
 * bump of the notebook's index row via [onEdited], so the library card's "last modified" follows
 * ink without an index write per stroke. [drain] waits for everything queued so far — the seal
 * path calls it (ink is durable the moment its row lands in the WAL; a process kill loses at most
 * the strokes still queued here).
 */
class StrokeStore(
    private val dao: SoilDao,
    private val onEdited: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var touchJob: Job? = null

    init {
        scope.launch {
            for (job in queue) {
                try { job() } catch (e: Exception) { Log.e(TAG, "stroke write failed", e) }
            }
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live strokes of [pageId] in `"order"`. A bad blob is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<Stroke> =
        dao.childrenOfType(pageId, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }

    // ── Writes (main-thread callbacks → serial IO) ───────────────────────────

    /** New ink: insert a `stroke` row, `"order"` = max among the page's strokes (live or not) + 1. */
    fun commit(pageId: String, stroke: Stroke) = enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_STROKE) + 1
        dao.upsert(StrokeRows.toRow(stroke, pageId, order, now))
        Slog.d(TAG) { "commit ${stroke.id} (${stroke.points.size} pts) order=$order" }
    }

    /** Erased ink: soft delete — the rows stay for undo (R4) and the family's soft-delete rule. */
    fun erase(ids: List<String>) {
        if (ids.isEmpty()) return
        enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "erase ${ids.size}" }
        }
    }

    /** A finished selection drag: the row is the truth, so rewrite the persisted geometry. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        enqueue {
            val now = System.currentTimeMillis()
            for (row in dao.byIds(ids)) {
                if (row.deletedAt != null) continue
                val stroke = StrokeRows.toStroke(row) ?: continue
                val moved = StrokeRows.toRow(stroke.translated(dx, dy), row.parentId, row.order, now)
                dao.upsert(moved.copy(createdAt = row.createdAt))
            }
            Slog.d(TAG) { "move ${ids.size} by ($dx,$dy)" }
        }
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

    private fun enqueue(job: suspend () -> Unit) {
        val r = queue.trySend(job)
        if (r.isFailure) Log.w(TAG, "write dropped: store closed")
        else scheduleTouch()
    }

    private fun scheduleTouch() {
        touchJob?.cancel()
        touchJob = scope.launch {
            delay(TOUCH_DEBOUNCE_MS)
            try { onEdited() } catch (e: Exception) { Log.w(TAG, "updatedAt bump failed", e) }
        }
    }

    /** Flush a pending debounced bump now (close path — the card must not lag its last stroke). */
    suspend fun flushTouch() {
        val j = touchJob ?: return
        touchJob = null
        j.cancel()
        try { onEdited() } catch (e: Exception) { Log.w(TAG, "updatedAt bump failed", e) }
    }

    private companion object {
        const val TAG = "StrokeStore"
        const val TOUCH_DEBOUNCE_MS = 2_000L
    }
}
