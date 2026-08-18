package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.data.soil.SoilDao
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema

/**
 * Mirrors g-paper's data-out callbacks into `stroke` rows. All writes go through the notebook's one
 * serial IO [SoilWriter] (shared with [ObjectStore] since arc 4 / H1) so they land in callback order
 * — a commit followed by an erase of the same stroke can never race. Reads ([loadPage]) are plain
 * suspend calls. The `updatedAt` discipline ([SoilWriter.enqueue] → debounced index bump) and
 * [SoilWriter.drain] belong to the writer.
 */
class StrokeStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
) {
    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live strokes of [pageId] in `"order"`. A bad blob is dropped, the page still renders. IO-safe. */
    suspend fun loadPage(pageId: String): List<Stroke> =
        dao.childrenOfType(pageId, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }

    // ── Writes (callback thread → serial IO) ─────────────────────────────────

    fun commit(pageId: String, stroke: Stroke) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_STROKE) + 1
        dao.upsert(StrokeRows.toRow(stroke, pageId, order, now))
        Slog.d(TAG) { "commit ${stroke.id} (${stroke.points.size} pts) order=$order" }
    }

    fun erase(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "erase ${ids.size}" }
        }
    }

    /** Soft-delete strokes by id (undo of a draw). Same primitive as [erase], named for the caller. */
    fun remove(ids: List<String>) = erase(ids)

    /** Re-add previously-erased strokes as fresh live rows (undo of an erase, redo of a draw). */
    fun restore(pageId: String, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            var order = dao.maxOrder(pageId, SoilSchema.TYPE_STROKE)
            for (s in strokes) {
                order += 1
                dao.upsert(StrokeRows.toRow(s, pageId, order, now))
            }
            Slog.d(TAG) { "restore ${strokes.size} to $pageId" }
        }
    }

    /** Rewrite the moved strokes' geometry — the row is the truth, so translate the persisted points. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
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

    /** Suspends until every write queued so far (by either store) has been applied. */
    suspend fun drain() = writer.drain()

    private companion object {
        const val TAG = "StrokeStore"
    }
}
