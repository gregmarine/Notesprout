package com.symmetricalpalmtree.notesproutsn.extension

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's outward caps, the two mappings, and the inward drain's accumulator. */
class TransferCapsTest {

    private fun paperStroke(id: String, n: Int, width: Float = 3f, color: Int = Stroke.BLACK, style: StrokeStyle = StrokeStyle.PEN) =
        Stroke(
            id = id,
            points = List(n) { StrokePoint(it.toFloat(), it * 2f, 0.5f, 0.25f, 99L) },
            color = color, width = width, style = style,
        )

    private fun wire(n: Int, width: Float = 3f, color: Int = Stroke.BLACK, style: String = "PEN") =
        WireStroke(FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), width, color, style)

    // ── Outward ──────

    @Test
    fun withinLimitsIsTheOutwardGate() {
        assertTrue(TransferCaps.withinLimits(ExtensionContract.MAX_TRANSFER_STROKES, ExtensionContract.MAX_TRANSFER_POINTS))
        assertFalse(TransferCaps.withinLimits(ExtensionContract.MAX_TRANSFER_STROKES + 1, 0))
        assertFalse(TransferCaps.withinLimits(0, ExtensionContract.MAX_TRANSFER_POINTS + 1))
    }

    @Test
    fun toWireStrokesDropsIdAndTimeAndSkipsEmptyStrokes() {
        val strokes = listOf(paperStroke("a", 3), paperStroke("b", 0), paperStroke("c", 2))
        val wireStrokes = TransferCaps.toWireStrokes(strokes)
        assertEquals(2, wireStrokes.size)   // the point-less stroke never crosses
        assertEquals(3, wireStrokes[0].size)
        assertEquals(0.5f, wireStrokes[0].pressure[1], 0f)
        assertEquals(0.25f, wireStrokes[0].tilt[1], 0f)
        // Nothing on the wire carries an id: the receiving side mints its own.
        assertEquals(2, TransferCaps.pointCount(listOf(paperStroke("a", 2))))
    }

    // ── Inward ──────

    @Test
    fun sanitizeForcesWhatSnCanDraw() {
        val s = TransferCaps.sanitize(wire(2, width = 999f, color = 0xFFFF0000.toInt(), style = "GLITTER"))
        assertEquals(StrokeStyle.PEN.name, s.style)
        assertEquals(TransferCaps.MAX_WIDTH, s.width, 0f)
        assertEquals(Stroke.BLACK, s.colorArgb)
        assertEquals(TransferCaps.MIN_WIDTH, TransferCaps.sanitize(wire(2, width = 0.01f)).width, 0f)
        // A NaN or non-positive width can never reach `sanitize`: WireStroke's own `requireValid`
        // rejects it in the constructor, which is also where unmarshalling lands.
        assertThrows(IllegalArgumentException::class.java) { wire(2, width = Float.NaN) }
    }

    @Test
    fun sanitizeKeepsAnAlreadyCleanStrokeAsIs() {
        val clean = wire(2)
        assertTrue(TransferCaps.sanitize(clean) === clean)
    }

    @Test
    fun toStrokesMintsFreshIds() {
        val out = TransferCaps.toStrokes(listOf(wire(2), wire(3)))
        assertEquals(2, out.size)
        assertNotEquals(out[0].id, out[1].id)
        assertEquals(0L, out[0].points[0].timeMillis)
        assertEquals(StrokeStyle.PEN, out[0].style)
        // An unknown style name reads as PEN rather than failing the whole paste.
        assertEquals(StrokeStyle.PEN, TransferCaps.toStrokes(listOf(wire(1, style = "NOPE")))[0].style)
    }

    @Test
    fun drainStopsOnAnEmptyChunk() {
        val d = TransferCaps.Drain()
        assertTrue(d.add(listOf(wire(2))))
        assertFalse(d.add(emptyList()))
        assertEquals(1, d.chunks)
        assertEquals(1, d.strokes.size)
        assertFalse(d.truncated)
    }

    @Test
    fun drainSanitizesEveryAcceptedStroke() {
        val d = TransferCaps.Drain()
        d.add(listOf(wire(2, width = 999f, style = "GLITTER")))
        assertEquals(StrokeStyle.PEN.name, d.strokes[0].style)
        assertEquals(TransferCaps.MAX_WIDTH, d.strokes[0].width, 0f)
    }

    @Test
    fun drainTruncatesAtTheStrokeCap() {
        val d = TransferCaps.Drain()
        val full = List(ExtensionContract.TRANSFER_CHUNK_STROKES) { wire(1) }
        var accepted = 0
        // Feed full chunks until the summed stroke cap bites.
        while (d.add(full)) accepted += full.size
        assertTrue(d.truncated)
        assertTrue(d.strokes.size <= ExtensionContract.MAX_TRANSFER_STROKES)
    }

    @Test
    fun drainRefusesAChunkPastTheChunkBudgetWhole() {
        val d = TransferCaps.Drain()
        val one = listOf(wire(1))
        repeat(ExtensionContract.TRANSFER_MAX_CHUNKS) { assertTrue(d.add(one)) }
        // The probe chunk past the budget: refused whole, and it says so — nothing is half-taken.
        assertFalse(d.add(one))
        assertTrue(d.truncated)
        assertEquals(ExtensionContract.TRANSFER_MAX_CHUNKS, d.chunks)
        assertEquals(ExtensionContract.TRANSFER_MAX_CHUNKS, d.strokes.size)
    }

    @Test
    fun chunkDelegatesToTheContractRule() {
        val strokes = List(ExtensionContract.TRANSFER_CHUNK_STROKES + 1) { wire(1) }
        assertEquals(InkChunks.chunk(strokes).size, TransferCaps.chunk(strokes).size)
    }
}
