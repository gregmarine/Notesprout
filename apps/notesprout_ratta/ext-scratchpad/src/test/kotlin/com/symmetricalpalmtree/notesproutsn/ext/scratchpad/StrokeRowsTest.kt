package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One `stroke` row → one stroke (arc 22 / X2). The rule the whole read rests on: **a bad row is a
 * dropped stroke, never a lost page** — arc 11's page blob could not say that, and a row can.
 */
class StrokeRowsTest {

    private val columns = listOf("id", "order", "color", "width", "style", "blob")

    private fun stroke(id: String = "s1", style: StrokeStyle = StrokeStyle.PEN) = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
        style = style,
    )

    private fun row(vararg cells: Cell) = Row(columns, cells.toList())

    private fun goodRow(order: Long = 4L, s: Stroke = stroke()) = row(
        Cell.Text(s.id),
        Cell.Integer(order),
        Cell.Integer(s.color.toLong()),
        Cell.Real(s.width.toDouble()),
        Cell.Text(s.style.name),
        Cell.Blob(ScratchSql.geometry(s)),
    )

    @Test
    fun aGoodRowRoundTripsItsStroke() {
        val (order, decoded) = StrokeRows.decode(goodRow())!!
        assertEquals(4L, order)
        assertEquals("s1", decoded.id)
        assertEquals(Stroke.BLACK, decoded.color)
        assertEquals(3f, decoded.width, 0f)
        assertEquals(StrokeStyle.PEN, decoded.style)
        assertEquals(2, decoded.points.size)
        assertEquals(1f, decoded.points[0].x, 0.001f)
        assertEquals(4f, decoded.points[1].y, 0.001f)
        assertEquals(0.6f, decoded.points[1].pressure, 0.01f)
    }

    @Test
    fun anUnknownStyleReadsAsPen() {
        val s = stroke()
        val r = row(
            Cell.Text(s.id), Cell.Integer(0), Cell.Integer(s.color.toLong()), Cell.Real(3.0),
            Cell.Text("SOMETHING_A_LATER_BUILD_WROTE"), Cell.Blob(ScratchSql.geometry(s)),
        )
        assertEquals(StrokeStyle.PEN, StrokeRows.decode(r)!!.second.style)
    }

    @Test
    fun aBadBlobDropsThatStrokeOnly() {
        val s = stroke()
        val r = row(
            Cell.Text(s.id), Cell.Integer(0), Cell.Integer(s.color.toLong()), Cell.Real(3.0),
            Cell.Text("PEN"), Cell.Blob(byteArrayOf(9, 9, 9, 9)),
        )
        assertNull(StrokeRows.decode(r))
        // The neighbours are untouched — that is the whole point of a row.
        assertNotNull(StrokeRows.decode(goodRow()))
    }

    @Test
    fun aCellOfTheWrongStorageClassDropsTheRow() {
        val s = stroke()
        val blob = Cell.Blob(ScratchSql.geometry(s))
        // id as an INTEGER
        assertNull(StrokeRows.decode(row(Cell.Integer(1), Cell.Integer(0), Cell.Integer(0), Cell.Real(3.0), Cell.Text("PEN"), blob)))
        // order as TEXT
        assertNull(StrokeRows.decode(row(Cell.Text("s"), Cell.Text("0"), Cell.Integer(0), Cell.Real(3.0), Cell.Text("PEN"), blob)))
        // the geometry as TEXT
        assertNull(StrokeRows.decode(row(Cell.Text("s"), Cell.Integer(0), Cell.Integer(0), Cell.Real(3.0), Cell.Text("PEN"), Cell.Text("x"))))
        // a NULL where a value must be
        assertNull(StrokeRows.decode(row(Cell.Text("s"), Cell.Integer(0), Cell.Integer(0), Cell.Real(3.0), Cell.Null, blob)))
    }

    @Test
    fun aPointLessStrokeIsDropped() {
        val empty = Stroke(id = "s", points = emptyList())
        val r = row(
            Cell.Text("s"), Cell.Integer(0), Cell.Integer(0), Cell.Real(3.0),
            Cell.Text("PEN"), Cell.Blob(ScratchSql.geometry(empty)),
        )
        assertNull(StrokeRows.decode(r))
    }

    /** An INTEGER width reads as a REAL — SQLite's own affinity, which the seam keeps. */
    @Test
    fun anIntegerWidthIsAcceptedAsAReal() {
        val s = stroke()
        val r = row(
            Cell.Text("s"), Cell.Integer(0), Cell.Integer(0), Cell.Integer(3),
            Cell.Text("PEN"), Cell.Blob(ScratchSql.geometry(s)),
        )
        assertEquals(3f, StrokeRows.decode(r)!!.second.width, 0f)
    }
}
