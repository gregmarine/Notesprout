package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InkPayloadTest {

    private fun stroke(id: String, vararg xy: Float) = Stroke(
        id = id,
        points = List(xy.size / 2) { StrokePoint(xy[it * 2], xy[it * 2 + 1], pressure = 0.5f, tilt = 0.3f, timeMillis = 42L) },
        color = 0xFF00FF00.toInt(),
        width = 7f,
    )

    @Test
    fun onlyGeometryCrossesTheBoundary() {
        val out = InkPayload.fromStrokes(listOf(stroke("a", 1f, 2f, 3f, 4f)))
        assertEquals(1, out.size)
        assertArrayEquals(floatArrayOf(1f, 3f), out[0].x, 0f)
        assertArrayEquals(floatArrayOf(2f, 4f), out[0].y, 0f)
        // Nothing else has anywhere to go: InkStroke carries x and y and nothing more.
        assertEquals(2, out[0].size)
    }

    @Test
    fun writingOrderIsPreserved() {
        val ids = listOf("first", "second", "third")
        val strokes = ids.mapIndexed { i, id -> stroke(id, i.toFloat(), i.toFloat()) }
        val out = InkPayload.fromStrokes(strokes)
        assertEquals(3, out.size)
        for (i in ids.indices) assertEquals(i.toFloat(), out[i].x[0], 0f)
    }

    @Test
    fun pointlessStrokesAreSkippedRatherThanCrashing() {
        // An InkStroke may not be empty, so a stroke with no points cannot be represented — it is
        // dropped instead of throwing on the way out.
        val out = InkPayload.fromStrokes(listOf(Stroke("empty", emptyList()), stroke("real", 5f, 6f)))
        assertEquals(1, out.size)
        assertEquals(5f, out[0].x[0], 0f)
        assertEquals(0, InkPayload.fromStrokes(emptyList()).size)
    }

    @Test
    fun aLinkedHashMapsValuesKeepTheirOrder() {
        // This is exactly how the notebook sources the payload: load order, then commit order.
        val live = linkedMapOf<String, Stroke>()
        live["a"] = stroke("a", 0f, 0f)
        live["b"] = stroke("b", 1f, 1f)
        live["c"] = stroke("c", 2f, 2f)
        live.remove("b")                       // an erase
        live["a"] = stroke("a", 9f, 9f)        // a move updates in place, it does not re-append
        val out = InkPayload.fromStrokes(live.values.toList())
        assertEquals(2, out.size)
        assertEquals(9f, out[0].x[0], 0f)
        assertEquals(2f, out[1].x[0], 0f)
    }
}
