package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.StrokeCodec
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every statement the pad sends, pinned as exact text and arguments (arc 22 / X2) — the host's
 * validator is run over each one, so a shape it would refuse fails here rather than on the device,
 * and the `"order"` quoting is pinned because an unquoted ORDER is a syntax error.
 */
class ScratchSqlTest {

    private fun stroke(id: String = "s1") = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
        style = StrokeStyle.PEN,
    )

    // ── Writes ───────────────────────────────────────────────────────────────

    @Test
    fun insertPage_ignoresOnConflict_andNeverReplaces() {
        val s = ScratchSql.insertPage("p1", 2, 1404f, 1872f, 99L)
        assertEquals(
            "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(
            listOf<Cell>(
                Cell.Text("p1"), Cell.Integer(2), Cell.Real(1404.0), Cell.Real(1872.0),
                Cell.Integer(99), Cell.Integer(99),
            ),
            s.args,
        )
        // The trap the schema's cascade sets: REPLACE would delete the page row first, and that
        // delete takes the page's strokes with it.
        assertTrue(!s.sql.contains("REPLACE"))
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun sizePage_andPosition() {
        val size = ScratchSql.sizePage("p1", 800f, 1000f, 7L)
        assertEquals("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?", size.sql)
        assertEquals(listOf<Cell>(Cell.Real(800.0), Cell.Real(1000.0), Cell.Integer(7), Cell.Text("p1")), size.args)

        val position = ScratchSql.position("p1", 3)
        assertEquals("UPDATE page SET position = ? WHERE id = ?", position.sql)
        assertEquals(listOf<Cell>(Cell.Integer(3), Cell.Text("p1")), position.args)
        StoreSql.checkExec(size.sql)
        StoreSql.checkExec(position.sql)
    }

    @Test
    fun deletePage_andClearPage_areDifferentSentences() {
        val delete = ScratchSql.deletePage("p1")
        assertEquals("DELETE FROM page WHERE id = ?", delete.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), delete.args)

        val clear = ScratchSql.clearPage("p1")
        assertEquals("DELETE FROM stroke WHERE pageId = ?", clear.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), clear.args)
        StoreSql.checkExec(delete.sql)
        StoreSql.checkExec(clear.sql)
    }

    @Test
    fun putStroke_isIdempotent_andCarriesFormatBGeometry() {
        val s = ScratchSql.putStroke("p1", 4L, stroke())
        assertEquals(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(Cell.Text("s1"), s.args[0])
        assertEquals(Cell.Text("p1"), s.args[1])
        assertEquals(Cell.Integer(4), s.args[2])
        assertEquals(Cell.Integer(Stroke.BLACK.toLong()), s.args[3])
        assertEquals(Cell.Real(3.0), s.args[4])
        assertEquals(Cell.Text("PEN"), s.args[5])
        // The blob is the `.soil`'s own stroke encoding, unchanged by the move to rows.
        val blob = (s.args[6] as Cell.Blob).value
        assertArrayEquals(
            StrokeCodec.encode(floatArrayOf(1f, 3f), floatArrayOf(2f, 4f), floatArrayOf(0.5f, 0.6f), floatArrayOf(0.25f, 0.3f)),
            blob,
        )
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun dropStroke_andSetCurrent() {
        val drop = ScratchSql.dropStroke("s1")
        assertEquals("DELETE FROM stroke WHERE id = ?", drop.sql)
        assertEquals(listOf<Cell>(Cell.Text("s1")), drop.args)

        val current = ScratchSql.setCurrent("p2")
        assertEquals("INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)", current.sql)
        assertEquals(listOf<Cell>(Cell.Text("p2")), current.args)
        StoreSql.checkExec(drop.sql)
        StoreSql.checkExec(current.sql)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    fun theFiveReads() {
        val pages = ScratchSql.selectPages()
        assertEquals("SELECT id FROM page ORDER BY position", pages.sql)
        assertEquals(emptyList<Cell>(), pages.args)

        val current = ScratchSql.selectCurrent()
        assertEquals("SELECT value FROM state WHERE key = 'current'", current.sql)

        val size = ScratchSql.selectPageSize("p1")
        assertEquals("SELECT width, height FROM page WHERE id = ?", size.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), size.args)

        val lens = ScratchSql.selectStrokeLens("p1")
        assertEquals("SELECT \"order\", LENGTH(blob) AS len FROM stroke WHERE pageId = ? ORDER BY \"order\"", lens.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), lens.args)

        val strokes = ScratchSql.selectStrokes("p1", 3L..9L)
        assertEquals(
            "SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? AND \"order\" BETWEEN ? AND ? ORDER BY \"order\"",
            strokes.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("p1"), Cell.Integer(3), Cell.Integer(9)), strokes.args)

        val max = ScratchSql.selectMaxOrder("p1")
        assertEquals("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM stroke WHERE pageId = ?", max.sql)

        for (s in listOf(pages, current, size, lens, strokes, max)) StoreSql.checkQuery(s.sql)
    }

    @Test
    fun everyStatementIsWithinTheSeamsCaps() {
        val all = listOf(
            ScratchSql.insertPage("p", 0, 0f, 0f, 0L),
            ScratchSql.sizePage("p", 1f, 1f, 0L),
            ScratchSql.position("p", 0),
            ScratchSql.deletePage("p"),
            ScratchSql.clearPage("p"),
            ScratchSql.putStroke("p", 0L, stroke()),
            ScratchSql.dropStroke("s"),
            ScratchSql.setCurrent("p"),
            ScratchSql.selectPages(),
            ScratchSql.selectCurrent(),
            ScratchSql.selectPageSize("p"),
            ScratchSql.selectStrokeLens("p"),
            ScratchSql.selectStrokes("p", 0L..0L),
            ScratchSql.selectMaxOrder("p"),
        )
        for (s in all) {
            assertTrue(s.sql, s.sql.length <= ExtensionContract.STORE_MAX_SQL_CHARS)
            assertTrue(s.sql, s.args.size <= ExtensionContract.STORE_MAX_ARGS)
        }
    }

    // ── The schema ───────────────────────────────────────────────────────────

    @Test
    fun schemaV1_constructsAndIsVersionOne() {
        // StoreSchema's constructor runs the DDL validator over every statement, so simply reaching
        // this line is the assertion that the host would accept the declaration.
        assertEquals(1, ScratchSchema.V1.version)
        assertEquals(1, ScratchSchema.V1.steps.size)
        assertEquals(5, ScratchSchema.V1.steps[0].size)
        assertEquals(3, ScratchSchema.V1.steps[0].count { StoreSql.createsTable(it) })
        assertTrue(ScratchSchema.V1.steps[0].any { it.contains("ON DELETE CASCADE") })
    }
}
