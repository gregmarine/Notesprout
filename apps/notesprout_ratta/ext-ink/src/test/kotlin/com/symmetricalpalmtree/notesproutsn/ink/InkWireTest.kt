package com.symmetricalpalmtree.notesproutsn.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The extension's half of the wire ⇄ paper mapping — the deliberate twin of the host's `TransferCaps`. */
class InkWireTest {

    private fun wire(n: Int, width: Float = 3f, style: String = "PEN") =
        WireStroke(FloatArray(n) { it.toFloat() }, FloatArray(n), FloatArray(n) { 0.5f }, FloatArray(n) { 0.25f }, width, Stroke.BLACK, style)

    @Test
    fun inwardMintsFreshIdsAndZeroesTime() {
        val out = InkWire.toStrokes(listOf(wire(3), wire(2)))
        assertEquals(2, out.size)
        assertNotEquals(out[0].id, out[1].id)
        assertEquals(0L, out[0].points[0].timeMillis)
        assertEquals(0.5f, out[0].points[0].pressure, 0f)
        assertEquals(0.25f, out[0].points[0].tilt, 0f)
    }

    @Test
    fun inwardClampsWidthAndFallsBackToPen() {
        assertEquals(InkWire.MAX_WIDTH, InkWire.toStrokes(listOf(wire(1, width = 999f)))[0].width, 0f)
        assertEquals(InkWire.MIN_WIDTH, InkWire.toStrokes(listOf(wire(1, width = 0.01f)))[0].width, 0f)
        assertEquals(StrokeStyle.PEN, InkWire.toStrokes(listOf(wire(1, style = "GLITTER")))[0].style)
    }

    @Test
    fun outwardDropsIdAndSkipsPointLessStrokes() {
        val strokes = listOf(
            Stroke("keep", List(2) { StrokePoint(1f, 2f, 0.5f, 0.25f, 77L) }, Stroke.BLACK, 3f, StrokeStyle.PEN),
            Stroke("skip", emptyList(), Stroke.BLACK, 3f, StrokeStyle.PEN),
        )
        val wireStrokes = InkWire.toWireStrokes(strokes)
        assertEquals(1, wireStrokes.size)
        assertEquals(2, wireStrokes[0].size)
        assertEquals("PEN", wireStrokes[0].style)
    }

    @Test
    fun roundTripKeepsGeometry() {
        val back = InkWire.toWireStrokes(InkWire.toStrokes(listOf(wire(4))))
        assertEquals(4, back[0].size)
        assertEquals(3f, back[0].x[3], 0f)
    }
}
