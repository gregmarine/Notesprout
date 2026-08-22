package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * `heading` rows, through the session's single serial [SoilWriter] (shared with [StrokeStore] —
 * see its KDoc for why the queue is one). The store is write-through and dumb on purpose: the
 * screen's in-memory heading list is the working copy, every mutation here is fire-and-forget in
 * queue order, and undo replays through these same calls then reloads the page — the `.soil` stays
 * the source of truth.
 *
 * Soft deletes everywhere, and **restores are in place** ([restore] = clear `deletedAt`): a
 * heading's geometry, z-order and `createdAt` all survive a delete → undo round trip.
 */
class HeadingStore(
    private val dao: SoilDao,
    private val writer: SoilWriter,
) {

    /** Live headings of [pageId] in `"order"`. A malformed row is dropped; the page still renders. */
    suspend fun loadPage(pageId: String): List<Heading> =
        dao.childrenOfType(pageId, SoilSchema.TYPE_HEADING).mapNotNull { HeadingRows.toHeading(it) }

    /** New heading: insert its row, `"order"` = max among the page's headings (live or not) + 1. */
    fun create(pageId: String, heading: Heading) = writer.enqueue {
        val now = System.currentTimeMillis()
        val order = dao.maxOrder(pageId, SoilSchema.TYPE_HEADING) + 1
        dao.upsert(HeadingRows.toRow(heading.copy(order = order), pageId, now))
        Slog.d(TAG) { "create ${heading.id} level=${heading.level} order=$order" }
    }

    /** Delete (eraser sweep, selection delete, empty edit-save) — soft, like everything here. */
    fun erase(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.softDelete(ids, System.currentTimeMillis())
            Slog.d(TAG) { "erase ${ids.size}" }
        }
    }

    /** Undo of a delete: revive the rows in place — position, size, level and order all kept. */
    fun restore(ids: List<String>) {
        if (ids.isEmpty()) return
        writer.enqueue {
            dao.restore(ids, System.currentTimeMillis())
            Slog.d(TAG) { "restore ${ids.size}" }
        }
    }

    /** A finished selection drag: shift each live row's stored top-left by the same delta. */
    fun move(ids: List<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        writer.enqueue {
            val now = System.currentTimeMillis()
            for (row in dao.byIds(ids)) {
                if (row.deletedAt != null || row.type != SoilSchema.TYPE_HEADING) continue
                dao.setPosition(row.id, (row.x ?: 0f) + dx, (row.y ?: 0f) + dy, now)
            }
            Slog.d(TAG) { "move ${ids.size} by ($dx,$dy)" }
        }
    }

    /**
     * Text edit / level change: rewrite text (already hash-prefixed by `HeadingPrefix.applyLevel` —
     * the caller owns that), the authoritative level, and the re-measured box. Top-left is kept —
     * a heading grows and shrinks from its anchor, it never wanders.
     */
    fun updateContent(heading: Heading) = writer.enqueue {
        dao.setHeadingContent(
            heading.id, heading.text, heading.level, heading.width, heading.height,
            System.currentTimeMillis(),
        )
        Slog.d(TAG) { "update ${heading.id} level=${heading.level} ${heading.text.length} chars" }
    }

    private companion object {
        const val TAG = "HeadingStore"
    }
}
