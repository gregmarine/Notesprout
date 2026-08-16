package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesprout.core.StrokeCodec
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeRowsTest {

    private fun sample() = Stroke(
        id = "s1",
        points = listOf(
            StrokePoint(1f, 2f, 0.5f, 0.1f, 123L),
            StrokePoint(3.25f, 4.5f, 0.75f, 0.2f, 456L),
            StrokePoint(-7f, 8e3f, 1f, 0f, 789L),
        ),
        color = 0xFF000000.toInt(), width = 3f, style = StrokeStyle.PEN,
    )

    @Test
    fun roundTrip_preservesGeometryPressureTiltColourWidthStyle() {
        val row = StrokeRows.toRow(sample(), "page1", 7, 1000L)
        assertEquals(SoilSchema.TYPE_STROKE, row.type)
        assertEquals("page1", row.parentId)
        assertEquals(7, row.order)
        assertEquals("#000000", row.color)
        assertEquals("PEN", row.style)
        val back = StrokeRows.toStroke(row)!!
        assertEquals("s1", back.id)
        assertEquals(3, back.points.size)
        for (i in 0 until 3) {
            assertEquals(sample().points[i].x, back.points[i].x, 0f)
            assertEquals(sample().points[i].y, back.points[i].y, 0f)
            assertEquals(sample().points[i].pressure, back.points[i].pressure, 0f)
            assertEquals(sample().points[i].tilt, back.points[i].tilt, 0f)
            assertEquals(0L, back.points[i].timeMillis) // timestamps are never persisted
        }
        assertEquals(3f, back.width, 0f)
        assertEquals(StrokeStyle.PEN, back.style)
        assertEquals(0xFF000000.toInt(), back.color)
    }

    @Test
    fun blobWithoutChannels_defaultsPressureAndTilt() {
        val row = StrokeRows.toRow(sample(), "p", 0, 1L)
            .copy(blob = StrokeCodec.encode(floatArrayOf(1f), floatArrayOf(2f)))
        val s = StrokeRows.toStroke(row)!!
        assertEquals(1f, s.points[0].pressure, 0f)
        assertEquals(0f, s.points[0].tilt, 0f)
    }

    @Test
    fun unknownStyle_and_missingWidth_fallBack() {
        val row = StrokeRows.toRow(sample(), "p", 0, 1L).copy(style = "LASER", strokeWidth = null, color = "garbage")
        val s = StrokeRows.toStroke(row)!!
        assertEquals(StrokeStyle.PEN, s.style)
        assertEquals(Stroke.DEFAULT_WIDTH, s.width, 0f)
        assertEquals(0xFF000000.toInt(), s.color)
    }

    @Test
    fun corruptOrMissingBlob_isDropped() {
        val row = StrokeRows.toRow(sample(), "p", 0, 1L)
        assertNull(StrokeRows.toStroke(row.copy(blob = null)))
        assertNull(StrokeRows.toStroke(row.copy(blob = byteArrayOf(9, 9, 9))))
        assertNull(StrokeRows.toStroke(row.copy(blob = byteArrayOf(1, 0x78, 0x9c.toByte(), 1, 2))))
        assertNull(StrokeRows.toStroke(row.copy(blob = StrokeCodec.encode(FloatArray(0), FloatArray(0)))))
    }

    @Test
    fun translated_thenRow_movesPersistedGeometry() {
        val moved = sample().translated(10f, -5f)
        val back = StrokeRows.toStroke(StrokeRows.toRow(moved, "p", 0, 1L))!!
        assertEquals(11f, back.points[0].x, 0f)
        assertEquals(-3f, back.points[0].y, 0f)
        assertTrue(back.bounds.left <= back.bounds.right)
    }
}
