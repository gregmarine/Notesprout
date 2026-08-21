package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPayloadTest {

    @Test fun xyPreserved_everythingElseDropped() {
        val s = Stroke(
            id = "abc",
            points = listOf(
                StrokePoint(1f, 2f, pressure = 0.3f, tilt = 0.7f, timeMillis = 12345L),
                StrokePoint(3.5f, 4.25f, pressure = 0.9f, tilt = 0.1f, timeMillis = 12360L),
            ),
            color = 0x11223344, width = 7f, style = StrokeStyle.PEN,
        )
        val ink = InkPayload.fromStrokes(listOf(s))
        assertEquals(1, ink.size)
        assertArrayEquals(floatArrayOf(1f, 3.5f), ink[0].x, 0f)
        assertArrayEquals(floatArrayOf(2f, 4.25f), ink[0].y, 0f)
        // InkStroke has no other field to carry the rest — the reduction is structural.
        assertEquals(2, ink[0].size)
    }

    @Test fun orderKept_multipleStrokes() {
        val a = Stroke("a", listOf(StrokePoint(0f, 0f)))
        val b = Stroke("b", listOf(StrokePoint(10f, 10f), StrokePoint(11f, 12f), StrokePoint(12f, 14f)))
        val ink = InkPayload.fromStrokes(listOf(a, b))
        assertEquals(2, ink.size)
        assertEquals(1, ink[0].size)
        assertEquals(3, ink[1].size)
        assertEquals(12f, ink[1].x[2], 0f)
    }

    @Test fun emptyList_isEmpty() {
        assertTrue(InkPayload.fromStrokes(emptyList()).isEmpty())
    }

    @Test fun pointlessStroke_skipped() {
        val empty = Stroke("e", emptyList())
        val ok = Stroke("o", listOf(StrokePoint(5f, 6f)))
        val ink = InkPayload.fromStrokes(listOf(empty, ok))
        assertEquals(1, ink.size)
        assertEquals(5f, ink[0].x[0], 0f)
    }
}
