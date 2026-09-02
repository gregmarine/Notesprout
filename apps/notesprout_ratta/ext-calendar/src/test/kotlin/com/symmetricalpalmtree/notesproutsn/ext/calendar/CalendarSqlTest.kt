package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import com.symmetricalpalmtree.notesproutsn.ink.StrokeBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every statement the calendar sends, pinned: exact text + bound arguments, and the schema's shape. */
class CalendarSqlTest {

    private fun stroke() = Stroke(
        id = "s1",
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
    )

    @Test
    fun schemaShape() {
        assertEquals(1, CalendarSchema.V1.version)
        assertEquals(1, CalendarSchema.V1.steps.size)
        assertEquals(5, CalendarSchema.V1.steps[0].size)
        assertEquals(4, CalendarSchema.V1.steps[0].count { StoreSql.createsTable(it) })
        assertEquals(2, CalendarSchema.V1.steps[0].count { it.contains("ON DELETE CASCADE") })
        assertTrue(CalendarSchema.V1.steps[0][0].contains("UNIQUE(kind, date)"))
        assertTrue(CalendarSchema.V1.steps[0][1].contains("UNIQUE(periodId, half)"))
    }

    @Test
    fun everyStatementPassesTheHostGate() {
        val s = stroke()
        listOf(
            CalendarSql.insertPeriod("p", 0, "2026-09-01"),
            CalendarSql.insertPage("g", 0, "2026-09-01", 0, 1f, 1f, 0L),
            CalendarSql.sizePage("g", 1f, 1f, 0L),
            CalendarSql.touchPage("g", 0L),
            CalendarSql.putStroke("g", 0L, s),
            CalendarSql.dropStroke("s"),
            CalendarSql.setState("k", "v"),
        ).forEach { StoreSql.checkExec(it.sql) }
        listOf(
            CalendarSql.selectPeriod(0, "2026-09-01"),
            CalendarSql.selectPage(0, "2026-09-01", 0),
            CalendarSql.selectStrokeLens("g"),
            CalendarSql.selectStrokes("g", 0L..0L),
            CalendarSql.selectMaxOrder("g"),
            CalendarSql.selectState(),
            CalendarSql.selectCounts(),
        ).forEach { StoreSql.checkQuery(it.sql) }
    }

    @Test
    fun periodAndPageAreInsertOrIgnore_neverReplace() {
        val period = CalendarSql.insertPeriod("p1", 1, "2026-08-30")
        assertEquals("INSERT OR IGNORE INTO period (id, kind, date) VALUES (?, ?, ?)", period.sql)
        assertEquals(listOf(Cell.Text("p1"), Cell.Integer(1), Cell.Text("2026-08-30")), period.args)

        val page = CalendarSql.insertPage("g1", 2, "2026-09-01", 1, 1404f, 1872f, 99L)
        assertEquals(
            "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) " +
                "VALUES (?, (SELECT id FROM period WHERE kind = ? AND date = ?), ?, ?, ?, ?, ?)",
            page.sql,
        )
        assertEquals(
            listOf(Cell.Text("g1"), Cell.Integer(2), Cell.Text("2026-09-01"), Cell.Integer(1), Cell.Real(1404.0), Cell.Real(1872.0), Cell.Integer(99), Cell.Integer(99)),
            page.args,
        )
        // REPLACE's delete cascades — the X2 trap. Neither row-minting statement may carry it.
        assertTrue(!period.sql.contains("REPLACE"))
        assertTrue(!page.sql.contains("REPLACE"))
    }

    @Test
    fun pageUpdates() {
        val size = CalendarSql.sizePage("g1", 800f, 1000f, 7L)
        assertEquals("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?", size.sql)
        assertEquals(listOf(Cell.Real(800.0), Cell.Real(1000.0), Cell.Integer(7), Cell.Text("g1")), size.args)
        val touch = CalendarSql.touchPage("g1", 8L)
        assertEquals("UPDATE page SET updatedAt = ? WHERE id = ?", touch.sql)
        assertEquals(listOf(Cell.Integer(8), Cell.Text("g1")), touch.args)
    }

    @Test
    fun strokeRowsAreThePads() {
        val s = stroke()
        val put = CalendarSql.putStroke("g1", 4L, s)
        assertEquals(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            put.sql,
        )
        assertEquals(Cell.Text("s1"), put.args[0])
        assertEquals(Cell.Text("g1"), put.args[1])
        assertEquals(Cell.Integer(4), put.args[2])
        assertEquals(Cell.Integer(Stroke.BLACK.toLong()), put.args[3])
        assertEquals(Cell.Real(3.0), put.args[4])
        assertEquals(Cell.Text("PEN"), put.args[5])
        assertArrayEquals(StrokeBlob.encode(s), (put.args[6] as Cell.Blob).value)

        val drop = CalendarSql.dropStroke("s1")
        assertEquals("DELETE FROM stroke WHERE id = ?", drop.sql)
        assertEquals(listOf(Cell.Text("s1")), drop.args)
    }

    @Test
    fun stateRows() {
        val s = CalendarSql.setState(CalendarSql.KEY_LAST_DATE, "2026-09-01")
        assertEquals("INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)", s.sql)
        assertEquals(listOf(Cell.Text("lastDate"), Cell.Text("2026-09-01")), s.args)
        assertEquals("lastView", CalendarSql.KEY_LAST_VIEW)
        assertEquals("lastHalf", CalendarSql.KEY_LAST_HALF)
        assertEquals("SELECT key, value FROM state", CalendarSql.selectState().sql)
    }

    @Test
    fun reads() {
        val period = CalendarSql.selectPeriod(0, "2026-09-01")
        assertEquals("SELECT id FROM period WHERE kind = ? AND date = ?", period.sql)
        assertEquals(listOf(Cell.Integer(0), Cell.Text("2026-09-01")), period.args)

        val page = CalendarSql.selectPage(2, "2026-09-01", 1)
        assertEquals(
            "SELECT page.id AS id, page.periodId AS periodId, page.width AS width, page.height AS height " +
                "FROM page JOIN period ON period.id = page.periodId " +
                "WHERE period.kind = ? AND period.date = ? AND page.half = ?",
            page.sql,
        )
        assertEquals(listOf(Cell.Integer(2), Cell.Text("2026-09-01"), Cell.Integer(1)), page.args)

        val lens = CalendarSql.selectStrokeLens("g1")
        assertEquals("SELECT \"order\", LENGTH(blob) AS len FROM stroke WHERE pageId = ? ORDER BY \"order\"", lens.sql)
        val strokes = CalendarSql.selectStrokes("g1", 3L..9L)
        assertEquals(
            "SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? AND \"order\" BETWEEN ? AND ? ORDER BY \"order\"",
            strokes.sql,
        )
        assertEquals(listOf(Cell.Text("g1"), Cell.Integer(3), Cell.Integer(9)), strokes.args)
        assertEquals("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM stroke WHERE pageId = ?", CalendarSql.selectMaxOrder("g1").sql)
        assertEquals(
            "SELECT (SELECT COUNT(*) FROM period) AS periods, (SELECT COUNT(*) FROM page) AS pages, (SELECT COUNT(*) FROM stroke) AS strokes",
            CalendarSql.selectCounts().sql,
        )
    }
}
