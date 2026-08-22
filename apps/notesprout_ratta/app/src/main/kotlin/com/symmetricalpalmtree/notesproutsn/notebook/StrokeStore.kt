package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * Mirrors g-paper's data-out callbacks into `stroke` rows, through the session's single serial
 * [SoilWriter] (shared with [HeadingStore] since N2 — rows land in callback order across both
 * stores, so a commit followed by an erase of the same stroke, or a stroke soft-delete followed by
 * the heading it became, can never race). Reads ([loadPage]) are plain suspend calls.
 */
class StrokeStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
) {

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Live strokes of [pageId] in `"order"`. A bad blob is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<Stroke> =
        dao.childrenOfType(pageId, SoilSchema.TYPE_STROKE).mapNotNull { StrokeRows.toStroke(it) }

    // ── Writes (main-thread callbacks → serial IO) ───────────────────────────

    /** New ink: insert a `stroke` row, `"order"` = max among the page's strokes (live or not) + 1. */
    fun commit(pageId: String, stroke: Stroke) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_STROKE) + 1
        dao.upsert(StrokeRows.toRow(stroke, pageId, order, now))
        Slog.d(TAG) { "commit ${stroke.id} (${stroke.points.size} pts) order=$order" }
    }

    /** Erased ink: soft delete — the rows stay for undo and the family's soft-delete rule. */
    fun erase(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "erase ${ids.size}" }
        }
    }

    /** Undo of a draw: the same soft delete as [erase], named for the caller that means it. */
    fun remove(ids: List<String>) = erase(ids)

    /**
     * Undo of an erase (and redo of a draw): put the strokes back as live rows at the tail of the
     * page's z-order. The upsert is REPLACE, so a soft-deleted row is revived in place with
     * `deletedAt` cleared — and because [SoilDao.maxOrder] counts deleted rows too, the revived
     * stroke can never tie with one committed after the erase.
     */
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

    /**
     * Un-soft-delete rows **in place** — `"order"`, geometry and `createdAt` all survive. This is
     * the undo of a heading conversion: the converted ink must come back in its original *writing
     * order* (the arc-3 ML Kit trap — a re-recognize after the undo reads the strokes as a
     * sequence), which the tail-append [restore] would destroy.
     */
    fun revive(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.restore(ids, System.currentTimeMillis())
            Slog.d(TAG) { "revive ${ids.size}" }
        }
    }

    /** A finished selection drag: the row is the truth, so rewrite the persisted geometry. */
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

    /** Suspends until every write queued (by any store on this writer) so far has been applied. */
    suspend fun drain() = writer.drain()

    private companion object {
        const val TAG = "StrokeStore"
    }
}
