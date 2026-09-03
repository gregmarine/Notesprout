package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.ink.InkDocument
import com.symmetricalpalmtree.notesproutsn.ink.StrokeBlob

/**
 * The event note's ink (arc 24 / Z1) — the pad's stroke statements against the event's own table.
 *
 * `note_stroke` **is** the pad's stroke row: same columns, same `StrokeCodec` format B blob, same
 * `"order"` semantics. What differs is the table's name and its parent column (`eventId`, cascading
 * from `event`), and `InkSql`'s text spells `stroke` / `pageId` — a string cannot be delegated with
 * two words changed, so the six statements are written out here and `NoteSqlTest` repeats
 * `InkSqlTest`'s discipline against them: exact text, exact arguments, every one through the host's
 * own validator. `StrokeRows` / `StrokeBlob` / `StrokeReadPlan` / `InkDocument` are reused as they are.
 *
 * `"order"` is quoted (a real SQLite keyword) and a stroke row is `INSERT OR REPLACE` — safe here
 * where it is forbidden on `event`, because a stroke has no children for REPLACE's delete to take.
 */
object NoteSql : InkDocument.StrokeSql {

    // ── Writes ───────────────────────────────────────────────────────────────

    override fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement =
        Statement(
            "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            stroke.id, pageId, order, stroke.color.toLong(), stroke.width.toDouble(), stroke.style.name, StrokeBlob.encode(stroke),
        )

    override fun dropStroke(id: String): Statement =
        Statement("DELETE FROM note_stroke WHERE id = ?", id)

    /** Empty an event's note, keeping the event — what erasing the whole note comes to. */
    fun clearStrokes(eventId: String): Statement =
        Statement("DELETE FROM note_stroke WHERE eventId = ?", eventId)

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The read plan's first step: every stroke's order and blob length, which is small. */
    fun selectStrokeLens(eventId: String): Statement =
        Statement("SELECT \"order\", LENGTH(blob) AS len FROM note_stroke WHERE eventId = ? ORDER BY \"order\"", eventId)

    /** One planned range of the note's strokes (`StrokeReadPlan`); `BETWEEN` is inclusive. */
    fun selectStrokes(eventId: String, range: LongRange): Statement =
        Statement(
            "SELECT id, \"order\", color, width, style, blob FROM note_stroke WHERE eventId = ? AND \"order\" BETWEEN ? AND ? ORDER BY \"order\"",
            eventId, range.first, range.last,
        )

    /** Where new ink starts numbering; `-1` on a note with nothing on it. */
    fun selectMaxOrder(eventId: String): Statement =
        Statement("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM note_stroke WHERE eventId = ?", eventId)
}
