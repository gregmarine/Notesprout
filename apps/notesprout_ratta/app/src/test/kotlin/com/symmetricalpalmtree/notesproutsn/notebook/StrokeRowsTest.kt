package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.StrokeCodec
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StrokeRows] is part of the family format contract — the mapping must be exact both ways. */
class StrokeRowsTest {

    private fun stroke(
        id: String = "s-1",
        color: Int = 0xFF000000.toInt(),
        width: Float = 3f,
        style: StrokeStyle = StrokeStyle.PEN,
    ) = Stroke(
        id = id,
        points = listOf(
            StrokePoint(10.5f, 20.25f, pressure = 0.5f, tilt = 0.1f),
            StrokePoint(11f, 21f, pressure = 0.75f, tilt = 0.2f),
            StrokePoint(12.125f, 22.5f, pressure = 1f, tilt = 0f),
        ),
        color = color, width = width, style = style,
    )

    @Test
    fun `row carries identity, type, order and style name`() {
        val row = StrokeRows.toRow(stroke(style = StrokeStyle.FOUNTAIN), pageId = "p-1", order = 7, now = 123L)
        assertEquals("s-1", row.id)
        assertEquals("p-1", row.parentId)
        assertEquals(SoilSchema.TYPE_STROKE, row.type)
        assertEquals(7, row.order)
        assertEquals(123L, row.createdAt)
        assertEquals(123L, row.updatedAt)
        assertEquals("FOUNTAIN", row.style)
        assertEquals("#000000", row.color)
        assertEquals(3f, row.strokeWidth!!, 0f)
        assertNull(row.deletedAt)
    }

    @Test
    fun `round trip preserves geometry, pressure, tilt, colour, width, style`() {
        val original = stroke(color = 0xFF888888.toInt(), width = 5f, style = StrokeStyle.BRUSH)
        val back = StrokeRows.toStroke(StrokeRows.toRow(original, "p-1", 0, 1L))!!
        assertEquals(original.id, back.id)
        assertEquals(original.color, back.color)
        assertEquals(original.width, back.width, 0f)
        assertEquals(original.style, back.style)
        assertEquals(original.points.size, back.points.size)
        for (i in original.points.indices) {
            assertEquals(original.points[i].x, back.points[i].x, 0f)
            assertEquals(original.points[i].y, back.points[i].y, 0f)
            assertEquals(original.points[i].pressure, back.points[i].pressure, 1e-4f)
            assertEquals(original.points[i].tilt, back.points[i].tilt, 1e-4f)
        }
    }

    @Test
    fun `every g-paper style survives the round trip`() {
        for (style in StrokeStyle.entries) {
            val back = StrokeRows.toStroke(StrokeRows.toRow(stroke(style = style), "p", 0, 1L))!!
            assertEquals(style, back.style)
        }
    }

    @Test
    fun `unknown style name falls back to PEN`() {
        val row = StrokeRows.toRow(stroke(), "p-1", 0, 1L).copy(style = "CRAYON")
        assertEquals(StrokeStyle.PEN, StrokeRows.toStroke(row)!!.style)
        assertEquals(StrokeStyle.PEN, StrokeRows.styleOf(null))
        assertEquals(StrokeStyle.PEN, StrokeRows.styleOf(""))
    }

    @Test
    fun `missing width reads as the g-paper default`() {
        val row = StrokeRows.toRow(stroke(), "p-1", 0, 1L).copy(strokeWidth = null)
        assertEquals(Stroke.DEFAULT_WIDTH, StrokeRows.toStroke(row)!!.width, 0f)
    }

    @Test
    fun `missing or malformed blob decodes to null, never throws`() {
        val row = StrokeRows.toRow(stroke(), "p-1", 0, 1L)
        assertNull(StrokeRows.toStroke(row.copy(blob = null)))
        assertNull(StrokeRows.toStroke(row.copy(blob = byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `zero-point blob decodes to null`() {
        val empty = StrokeCodec.encode(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        val row = StrokeRows.toRow(stroke(), "p-1", 0, 1L).copy(blob = empty)
        assertNull(StrokeRows.toStroke(row))
    }

    @Test
    fun `blob is format B with both channels`() {
        val row = StrokeRows.toRow(stroke(), "p-1", 0, 1L)
        val pts = StrokeCodec.decode(row.blob!!)
        assertEquals(3, pts.size)
        assertTrue(pts.pressure != null)
        assertTrue(pts.tilt != null)
    }
}
