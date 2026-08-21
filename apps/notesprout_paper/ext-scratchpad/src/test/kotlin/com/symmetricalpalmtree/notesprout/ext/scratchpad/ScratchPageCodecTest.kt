package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchPageCodecTest {

    private fun stroke(id: String, n: Int, style: StrokeStyle = StrokeStyle.PEN) = Stroke(
        id = id, points = List(n) { StrokePoint(it * 1.5f, it * 2.5f, 0.25f * (it % 4), 0.1f, 99L) },
        color = Stroke.BLACK, width = 3f + n, style = style,
    )

    @Test
    fun roundTrip() {
        val strokes = listOf(stroke("a", 3), stroke("b-ü", 1, StrokeStyle.FOUNTAIN), stroke("c", 200, StrokeStyle.DASH))
        val blob = ScratchPageCodec.encode(1404f, 1872f, strokes)
        val page = ScratchPageCodec.decode(blob)
        assertEquals(1404f, page.pageWidth, 0f)
        assertEquals(1872f, page.pageHeight, 0f)
        assertEquals(3, page.strokes.size)
        for ((i, s) in strokes.withIndex()) {
            val d = page.strokes[i]
            assertEquals(s.id, d.id)
            assertEquals(s.width, d.width, 0f)
            assertEquals(s.color, d.color)
            assertEquals(s.style, d.style)
            assertEquals(s.points.map { listOf(it.x, it.y, it.pressure, it.tilt) }, d.points.map { listOf(it.x, it.y, it.pressure, it.tilt) })
            assertEquals(0L, d.points[0].timeMillis)   // time is not stored
        }
    }

    @Test
    fun emptyPageAndUnknownSize() {
        val page = ScratchPageCodec.decode(ScratchPageCodec.encode(0f, 0f, emptyList()))
        assertEquals(0f, page.pageWidth, 0f)
        assertTrue(page.strokes.isEmpty())
    }

    @Test
    fun truncatedTailDropsThePartialStroke() {
        val blob = ScratchPageCodec.encode(10f, 10f, listOf(stroke("a", 5), stroke("b", 5)))
        val cut = blob.copyOf(blob.size - 7)
        val page = ScratchPageCodec.decode(cut)
        assertEquals(1, page.strokes.size)
        assertEquals("a", page.strokes[0].id)
    }

    @Test
    fun badHeaderIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScratchPageCodec.decode(ByteArray(3)) }
        val blob = ScratchPageCodec.encode(10f, 10f, listOf(stroke("a", 2)))
        blob[0] = 9
        assertThrows(IllegalArgumentException::class.java) { ScratchPageCodec.decode(blob) }
    }

    @Test
    fun malformedGeometryBlobIsSkipped() {
        val good = stroke("ok", 2)
        // Encode two strokes, then corrupt the first one's geometry version byte in place.
        val blob = ScratchPageCodec.encode(1f, 1f, listOf(stroke("bad", 2), good))
        // header 13 + u16 idLen(2) + "bad"(3) + f32(4) + i32(4) + u8(1) + "PEN"(3) + u32 len(4) → geometry starts at 34
        blob[34] = 0x7F
        val page = ScratchPageCodec.decode(blob)
        assertEquals(listOf("ok"), page.strokes.map { it.id })
    }
}
