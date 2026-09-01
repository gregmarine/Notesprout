package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.AssignmentRecord
import com.symmetricalpalmtree.notesproutsn.extension.Row
import com.symmetricalpalmtree.notesproutsn.extension.TagRecord

/**
 * A `tag` / `assignment` row → its record (arc 22 / X3) — pure, JVM-tested.
 *
 * **A bad row is a dropped record, never a lost index.** Arc 21's whole-index blob had to treat an
 * unreadable byte as an unreadable *library*, because half a decode said nothing about where the
 * rest began; a row says exactly one tag or one attachment. So a display that is not a tag, an id
 * that is not a UUID, or a cell of the wrong storage class is skipped and **counted** — the count is
 * all that is ever logged, because a tag is the user's own words — and everything else loads. It is
 * the same rule the scratch pad takes for a malformed stroke (`StrokeRows`).
 */
object TagRows {

    /** What a decode produced, and how much of it was thrown away. */
    class Decoded<T>(val records: List<T>, val dropped: Int)

    /** Columns: `id, display`. */
    fun tags(rows: Iterable<Row>): Decoded<TagRecord> {
        val kept = ArrayList<TagRecord>()
        var dropped = 0
        for (row in rows) {
            val record = tag(row)
            if (record == null) dropped++ else kept += record
        }
        return Decoded(kept, dropped)
    }

    /** Columns: `tagId, notebookId, pageId`. */
    fun assignments(rows: Iterable<Row>): Decoded<AssignmentRecord> {
        val kept = ArrayList<AssignmentRecord>()
        var dropped = 0
        for (row in rows) {
            val record = assignment(row)
            if (record == null) dropped++ else kept += record
        }
        return Decoded(kept, dropped)
    }

    fun tag(row: Row): TagRecord? = try {
        TagRecord(row.text("id"), row.text("display"))
    } catch (e: Exception) {
        null
    }

    fun assignment(row: Row): AssignmentRecord? = try {
        AssignmentRecord(row.text("tagId"), row.text("notebookId"), row.text("pageId"))
    } catch (e: Exception) {
        null
    }
}
