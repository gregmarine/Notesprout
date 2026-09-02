package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.Statement

/**
 * The **stroke half** of an ink-on-rows extension store — the one table both the scratch pad and the
 * calendar keep, and every statement that reads or writes it (arc 23; the two `…Sql` objects
 * restated all of it until then, and [StrokeRows.decode] pins the column shape from the other end).
 *
 * A consumer composes rather than restates: its `StoreSchema` step lists [CREATE_STROKE_TABLE] and
 * [CREATE_STROKE_INDEX] among its own tables, and its `…Sql` object delegates
 * [InkDocument.StrokeSql] here and forwards the reads. **The emitted text is byte-identical to what
 * each consumer used to declare** — `ScratchSqlTest` and `CalendarSqlTest` pin every string through
 * the host's own validator, so a change here fails there first.
 *
 * Two rules the text carries:
 *
 * - `"order"` is **quoted** everywhere. It is a real SQLite keyword and an unquoted one is a syntax
 *   error; it is the writing order within the page, which is what makes a page's ink stable across
 *   an undo/redo cycle.
 * - a stroke row is written with `INSERT OR REPLACE` and removed with `DELETE … WHERE id = ?`, both
 *   **idempotent**: a stroke has no children, so REPLACE's delete cascades nothing, and a row that
 *   is not there is not an error. A batch that failed part-way is retried, and the retry converges.
 *
 * The `pageId` foreign key points at a table named `page` with `ON DELETE CASCADE` — which is why
 * neither consumer ever writes its **page** row with `INSERT OR REPLACE`: that delete would take
 * the page's strokes with it.
 */
object InkSql : InkDocument.StrokeSql {

    // ── The table ────────────────────────────────────────────────────────────

    /** `stroke` — `StrokeCodec` format B in `blob`, the `.soil`'s own encoding. */
    const val CREATE_STROKE_TABLE = """CREATE TABLE stroke (
                       id TEXT PRIMARY KEY,
                       pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                       "order" INTEGER NOT NULL,
                       color INTEGER NOT NULL,
                       width REAL NOT NULL,
                       style TEXT NOT NULL,
                       blob BLOB NOT NULL);"""

    /** Every read of a page's ink is `(pageId, "order")` ordered — the index that serves them all. */
    const val CREATE_STROKE_INDEX = """CREATE INDEX stroke_page_order ON stroke(pageId, "order");"""

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement =
        Statement(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            stroke.id, pageId, order, stroke.color.toLong(), stroke.width.toDouble(), stroke.style.name, StrokeBlob.encode(stroke),
        )

    override fun dropStroke(id: String): Statement =
        Statement("DELETE FROM stroke WHERE id = ?", id)

    /** Empty a page, keeping its row — what deleting the pad's lone page does. */
    fun clearStrokes(pageId: String): Statement =
        Statement("DELETE FROM stroke WHERE pageId = ?", pageId)

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The read plan's first step: every stroke's order and blob length, which is small. */
    fun selectStrokeLens(pageId: String): Statement =
        Statement("SELECT \"order\", LENGTH(blob) AS len FROM stroke WHERE pageId = ? ORDER BY \"order\"", pageId)

    /** One planned range of the page's strokes ([StrokeReadPlan]); `BETWEEN` is inclusive. */
    fun selectStrokes(pageId: String, range: LongRange): Statement =
        Statement(
            "SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? AND \"order\" BETWEEN ? AND ? ORDER BY \"order\"",
            pageId, range.first, range.last,
        )

    /** Where a placement onto an existing page starts numbering; `-1` on an empty page. */
    fun selectMaxOrder(pageId: String): Statement =
        Statement("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM stroke WHERE pageId = ?", pageId)
}
