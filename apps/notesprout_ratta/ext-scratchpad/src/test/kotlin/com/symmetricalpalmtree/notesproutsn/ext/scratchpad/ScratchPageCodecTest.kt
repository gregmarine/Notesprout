package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page blob: round trip, the exact running size the 4 MiB full rule depends on, and the two
 *  damaged-input rules (truncated tail tolerated, unknown version unreadable). */
class ScratchPageCodecTest {

    private fun stroke(id: String, n: Int, width: Float = 3f, style: StrokeStyle = StrokeStyle.PEN) = Stroke(
        id = id,
        points = List(n) { StrokePoint(it.toFloat(), it * 1.5f, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK, width = width, style = style,
    )

    @Test
    fun roundTripKeepsEverythingThatMatters() {
        val strokes = listOf(stroke("a", 5, width = 2.5f), stroke("b", 3, style = StrokeStyle.PEN))
        val page = ScratchPageCodec.decode(ScratchPageCodec.encode(1404f, 1872f, strokes))
        assertEquals(1404f, page.pageWidth, 0f)
        assertEquals(1872f, page.pageHeight, 0f)
        assertEquals(2, page.strokes.size)
        assertEquals("a", page.strokes[0].id)
        assertEquals(2.5f, page.strokes[0].width, 0f)
        assertEquals(5, page.strokes[0].points.size)
        assertEquals(3f, page.strokes[0].points[3].x, 0f)
        assertEquals(0.5f, page.strokes[0].points[3].pressure, 0f)
        assertEquals(0.25f, page.strokes[0].points[3].tilt, 0f)
    }

    @Test
    fun emptyPageIsJustTheHeader() {
        assertEquals(ScratchPageCodec.HEADER_BYTES, ScratchPageCodec.encode(0f, 0f, emptyList()).size)
        assertEquals(0, ScratchPageCodec.decode(ScratchPageCodec.encode(0f, 0f, emptyList())).strokes.size)
    }

    @Test
    fun strokeBytesIsTheExactRunningTotal() {
        // The full rule adds strokeBytes to a running size instead of re-encoding the page; the two
        // must agree exactly, or the 4 MiB cap is enforced against a fiction. Geometry is
        // zlib-compressed per stroke, so this is measured, never assumed.
        val strokes = listOf(stroke("a", 40), stroke("b", 7, width = 9f), stroke("c", 300))
        val expected = ScratchPageCodec.HEADER_BYTES + strokes.sumOf { ScratchPageCodec.strokeBytes(it) }
        assertEquals(expected, ScratchPageCodec.encode(0f, 0f, strokes).size)
    }

    @Test
    fun strokeBytesIsIndependentOfThePageSize() {
        val s = stroke("a", 12)
        assertEquals(
            ScratchPageCodec.encode(0f, 0f, listOf(s)).size,
            ScratchPageCodec.encode(1404f, 1872f, listOf(s)).size,
        )
    }

    @Test
    fun aTruncatedTailKeepsWhatDecodedWhole() {
        val blob = ScratchPageCodec.encode(10f, 20f, listOf(stroke("a", 5), stroke("b", 5)))
        val cut = blob.copyOf(blob.size - 12)
        val page = ScratchPageCodec.decode(cut)
        assertEquals("a", page.strokes[0].id)
        assertTrue(page.strokes.size < 2)   // the partial stroke is dropped, the page still opens
    }

    @Test
    fun anUnknownVersionIsUnreadableRatherThanEmpty() {
        // Never a blank page saved over the real one — the caller must be able to tell the difference.
        val blob = ScratchPageCodec.encode(10f, 20f, listOf(stroke("a", 2)))
        blob[0] = 99
        assertThrows(IllegalArgumentException::class.java) { ScratchPageCodec.decode(blob) }
        assertThrows(IllegalArgumentException::class.java) { ScratchPageCodec.decode(ByteArray(4)) }
    }

    @Test
    fun anUnknownStyleNameReadsAsPen() {
        val blob = ScratchPageCodec.encode(0f, 0f, listOf(stroke("a", 2)))
        // "PEN" is 3 ASCII bytes in the stroke header; rewrite them to a name nothing maps to.
        val i = String(blob, Charsets.ISO_8859_1).indexOf("PEN")
        assertTrue(i > 0)
        blob[i] = 'Z'.code.toByte(); blob[i + 1] = 'Z'.code.toByte(); blob[i + 2] = 'Z'.code.toByte()
        assertEquals(StrokeStyle.PEN, ScratchPageCodec.decode(blob).strokes[0].style)
    }
}
