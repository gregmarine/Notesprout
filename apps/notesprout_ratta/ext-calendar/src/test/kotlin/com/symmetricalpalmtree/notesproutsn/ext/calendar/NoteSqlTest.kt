package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import com.symmetricalpalmtree.notesproutsn.ink.StrokeBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `InkSqlTest`'s discipline against `note_stroke` / `eventId` — the same six statements the pad's
 * table gets, spelled out because `InkSql`'s text names `stroke` / `pageId` and cannot be delegated
 * to with two words changed.
 */
class NoteSqlTest {

    private fun stroke(id: String = "s1") = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
        style = StrokeStyle.PEN,
    )

    @Test
    fun putStroke_isIdempotent_andCarriesFormatBGeometry() {
        val s = NoteSql.putStroke("e1", 4L, stroke())
        assertEquals(
            "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(Cell.Text("s1"), s.args[0])
        assertEquals(Cell.Text("e1"), s.args[1])
        assertEquals(Cell.Integer(4), s.args[2])
        assertEquals(Cell.Integer(Stroke.BLACK.toLong()), s.args[3])
        assertEquals(Cell.Real(3.0), s.args[4])
        assertEquals(Cell.Text("PEN"), s.args[5])
        assertArrayEquals(StrokeBlob.encode(stroke()), (s.args[6] as Cell.Blob).value)
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun theDeletes() {
        val drop = NoteSql.dropStroke("s1")
        assertEquals("DELETE FROM note_stroke WHERE id = ?", drop.sql)
        assertEquals(listOf<Cell>(Cell.Text("s1")), drop.args)

        val clear = NoteSql.clearStrokes("e1")
        assertEquals("DELETE FROM note_stroke WHERE eventId = ?", clear.sql)
        assertEquals(listOf<Cell>(Cell.Text("e1")), clear.args)

        StoreSql.checkExec(drop.sql)
        StoreSql.checkExec(clear.sql)
    }

    @Test
    fun theThreeReads_keepOrderQuoted() {
        val lens = NoteSql.selectStrokeLens("e1")
        assertEquals("SELECT \"order\", LENGTH(blob) AS len FROM note_stroke WHERE eventId = ? ORDER BY \"order\"", lens.sql)
        assertEquals(listOf<Cell>(Cell.Text("e1")), lens.args)

        val strokes = NoteSql.selectStrokes("e1", 3L..9L)
        assertEquals(
            "SELECT id, \"order\", color, width, style, blob FROM note_stroke WHERE eventId = ? AND \"order\" BETWEEN ? AND ? ORDER BY \"order\"",
            strokes.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("e1"), Cell.Integer(3), Cell.Integer(9)), strokes.args)

        val max = NoteSql.selectMaxOrder("e1")
        assertEquals("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM note_stroke WHERE eventId = ?", max.sql)

        for (s in listOf(lens, strokes, max)) StoreSql.checkQuery(s.sql)
    }

    @Test
    fun theTableIsThePadsRowUnderItsOwnNameAndParent() {
        val ddl = CalendarSchema.V2.steps[1].single { it.contains("CREATE TABLE note_stroke") }
        assertTrue(ddl.contains("eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE"))
        assertTrue(ddl.contains("\"order\" INTEGER NOT NULL"))
        assertTrue(ddl.contains("blob BLOB NOT NULL"))
        StoreSql.checkDdl(ddl)
        assertTrue(StoreSql.createsTable(ddl))
    }
}
