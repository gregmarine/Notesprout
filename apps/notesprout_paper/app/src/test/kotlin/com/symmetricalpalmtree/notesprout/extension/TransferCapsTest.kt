package com.symmetricalpalmtree.notesprout.extension

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCapsTest {

    private fun wire(n: Int, width: Float = 3f, color: Int = Stroke.BLACK, style: String = "PEN") =
        PaperStroke(FloatArray(n) { it.toFloat() }, FloatArray(n) { it * 2f }, FloatArray(n) { 0.5f }, FloatArray(n) { 0.1f }, width, color, style)

    private fun paper(id: String, n: Int) = Stroke(
        id = id, points = List(n) { StrokePoint(it.toFloat(), it * 3f, 0.7f, 0.2f, 123L) },
        color = Stroke.BLACK, width = 4f, style = StrokeStyle.FOUNTAIN,
    )

    @Test
    fun limits() {
        assertTrue(TransferCaps.withinLimits(ExtensionContract.MAX_TRANSFER_STROKES, ExtensionContract.MAX_TRANSFER_POINTS))
        assertFalse(TransferCaps.withinLimits(ExtensionContract.MAX_TRANSFER_STROKES + 1, 0))
        assertFalse(TransferCaps.withinLimits(0, ExtensionContract.MAX_TRANSFER_POINTS + 1))
        assertEquals(5, TransferCaps.pointCount(listOf(paper("a", 2), paper("b", 3))))
    }

    @Test
    fun chunkByStrokeCount() {
        val strokes = List(ExtensionContract.TRANSFER_CHUNK_STROKES * 2 + 1) { wire(1) }
        val chunks = TransferCaps.chunk(strokes)
        assertEquals(3, chunks.size)
        assertEquals(ExtensionContract.TRANSFER_CHUNK_STROKES, chunks[0].size)
        assertEquals(1, chunks[2].size)
        assertEquals(strokes.size, chunks.sumOf { it.size })
        for (c in chunks) InkBundle(c, 0f, 0f)   // every chunk is a legal bundle
    }

    @Test
    fun chunkByPoints_andALoneGiantStrokeIsItsOwnChunk() {
        val half = ExtensionContract.TRANSFER_CHUNK_POINTS / 2
        val strokes = listOf(wire(half), wire(half), wire(1), wire(ExtensionContract.TRANSFER_CHUNK_POINTS + 5), wire(2))
        val chunks = TransferCaps.chunk(strokes)
        assertEquals(listOf(2, 1, 1, 1), chunks.map { it.size })
        assertEquals(ExtensionContract.TRANSFER_CHUNK_POINTS + 5, chunks[2][0].size)
        for (c in chunks) InkBundle(c, 0f, 0f)
        assertTrue(TransferCaps.chunk(emptyList()).isEmpty())
    }

    @Test
    fun outward_dropsIdAndTime_keepsGeometryWidthColourStyle_skipsEmpty() {
        val out = TransferCaps.toPaperStrokes(listOf(paper("keep", 3), Stroke("empty", emptyList()), paper("k2", 1)))
        assertEquals(2, out.size)
        val s = out[0]
        assertEquals(3, s.size)
        assertEquals(6f, s.y[2], 0f)
        assertEquals(0.7f, s.pressure[1], 0f)
        assertEquals(0.2f, s.tilt[1], 0f)
        assertEquals(4f, s.width, 0f)
        assertEquals("FOUNTAIN", s.style)
        assertEquals(Stroke.BLACK, s.colorArgb)
    }

    @Test
    fun sanitize_unknownStyleToPen_widthClamped_colourForcedBlack() {
        val b = InkBundle(listOf(wire(2, width = 0.01f, color = 0x12FF0000, style = "NEON"), wire(2, width = 999f), wire(2)), 10f, 20f)
        val s = TransferCaps.sanitize(b)
        assertEquals(10f, s.pageWidth, 0f)
        assertEquals("PEN", s.strokes[0].style)
        assertEquals(TransferCaps.MIN_WIDTH, s.strokes[0].width, 0f)
        assertEquals(Stroke.BLACK, s.strokes[0].colorArgb)
        assertEquals(TransferCaps.MAX_WIDTH, s.strokes[1].width, 0f)
        assertSame(b.strokes[2], s.strokes[2])   // an already-clean stroke is kept as is
        assertEquals("FOUNTAIN", TransferCaps.sanitize(wire(1, style = "FOUNTAIN")).style)
    }

    @Test
    fun drain_stopsOnEmpty_cutsAtCaps_probesPastTheChunkBudget() {   // arc 6 / S2
        // Plain: two chunks then an empty one.
        val d = TransferCaps.Drain()
        assertTrue(d.add(listOf(wire(2), wire(3, style = "NEON"))))
        assertTrue(d.add(listOf(wire(1))))
        assertFalse(d.add(emptyList()))
        assertEquals(3, d.strokes.size)
        assertEquals("PEN", d.strokes[1].style)   // sanitized on the way in
        assertEquals(2, d.chunks)
        assertFalse(d.truncated)

        // The stroke cap cuts inside a chunk.
        val cap = TransferCaps.Drain()
        val per = ExtensionContract.TRANSFER_CHUNK_STROKES
        var n = 0
        while (cap.add(List(per) { wire(1) })) n++
        assertTrue(cap.truncated)
        assertEquals(ExtensionContract.MAX_TRANSFER_STROKES, cap.strokes.size)

        // The point cap cuts too.
        val pts = TransferCaps.Drain()
        assertTrue(pts.add(listOf(wire(ExtensionContract.TRANSFER_CHUNK_POINTS))))
        var i = 1
        while (pts.add(listOf(wire(ExtensionContract.TRANSFER_CHUNK_POINTS)))) i++
        assertTrue(pts.truncated)
        assertTrue(pts.strokes.sumOf { it.size } <= ExtensionContract.MAX_TRANSFER_POINTS)

        // The chunk budget: small chunks never hit a cap; the probe past the budget marks truncated only if non-empty.
        val budget = TransferCaps.Drain()
        repeat(ExtensionContract.TRANSFER_MAX_CHUNKS) { assertTrue(budget.add(listOf(wire(1)))) }
        assertFalse(budget.truncated)
        assertFalse(budget.add(emptyList()))
        assertFalse(budget.truncated)
        val budget2 = TransferCaps.Drain()
        repeat(ExtensionContract.TRANSFER_MAX_CHUNKS) { budget2.add(listOf(wire(1))) }
        assertFalse(budget2.add(listOf(wire(1))))
        assertTrue(budget2.truncated)
        assertEquals(ExtensionContract.TRANSFER_MAX_CHUNKS, budget2.strokes.size)
    }

    @Test
    fun inward_mintsFreshIds_zeroTime() {
        var n = 0
        val strokes = TransferCaps.toStrokes(listOf(wire(2, style = "DASH"), wire(3)), newId = { "id${n++}" })
        assertEquals(listOf("id0", "id1"), strokes.map { it.id })
        assertNotEquals(strokes[0].id, strokes[1].id)
        assertEquals(StrokeStyle.DASH, strokes[0].style)
        assertEquals(StrokeStyle.PEN, TransferCaps.toStrokes(listOf(wire(1, style = "zzz")))[0].style)
        assertEquals(0L, strokes[0].points[0].timeMillis)
        assertEquals(4f, strokes[1].points[2].y, 0f)
        assertEquals(0.5f, strokes[1].points[2].pressure, 0f)
        // Round trip paper → wire → paper keeps geometry.
        val back = TransferCaps.toStrokes(TransferCaps.toPaperStrokes(listOf(paper("x", 4))))
        assertEquals(paper("x", 4).points.map { it.x to it.y }, back[0].points.map { it.x to it.y })
    }
}
