package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesprout.extension.PaperStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The extension's wire ⇄ paper mappings (arc 6 / S2). */
class ScratchInkTest {

    private fun wire(n: Int, width: Float = 3f, style: String = "PEN") =
        PaperStroke(FloatArray(n) { it.toFloat() }, FloatArray(n) { it * 2f }, FloatArray(n) { 0.5f }, FloatArray(n) { 0.1f }, width, Stroke.BLACK, style)

    @Test
    fun inward_freshIds_zeroTime_unknownStyleToPen_widthClamped() {
        var n = 0
        val strokes = ScratchInk.toStrokes(listOf(wire(2, style = "DASH"), wire(3, width = 0.01f, style = "zzz"), wire(1, width = 999f)), newId = { "id${n++}" })
        assertEquals(listOf("id0", "id1", "id2"), strokes.map { it.id })
        assertNotEquals(strokes[0].id, strokes[1].id)
        assertEquals(StrokeStyle.DASH, strokes[0].style)
        assertEquals(StrokeStyle.PEN, strokes[1].style)
        assertEquals(ScratchInk.MIN_WIDTH, strokes[1].width, 0f)
        assertEquals(ScratchInk.MAX_WIDTH, strokes[2].width, 0f)
        assertEquals(0L, strokes[0].points[0].timeMillis)
        assertEquals(4f, strokes[1].points[2].y, 0f)
        assertEquals(0.5f, strokes[1].points[2].pressure, 0f)
    }

    @Test
    fun outward_dropsIdAndTime_skipsEmpty_roundTrips() {
        val paper = listOf(
            Stroke(id = "keep", points = List(3) { StrokePoint(it * 1f, it * 3f, 0.7f, 0.2f, 99L) }, width = 4f, style = StrokeStyle.FOUNTAIN),
            Stroke(id = "empty", points = emptyList()),
        )
        val out = ScratchInk.toPaperStrokes(paper)
        assertEquals(1, out.size)
        assertEquals(3, out[0].size)
        assertEquals(6f, out[0].y[2], 0f)
        assertEquals("FOUNTAIN", out[0].style)
        assertEquals(4f, out[0].width, 0f)
        val back = ScratchInk.toStrokes(out)
        assertEquals(paper[0].points.map { it.x to it.y }, back[0].points.map { it.x to it.y })
        assertNotEquals("keep", back[0].id)
    }
}
