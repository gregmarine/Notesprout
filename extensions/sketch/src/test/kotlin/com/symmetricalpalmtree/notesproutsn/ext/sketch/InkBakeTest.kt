package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Bring in ink" (decision 8), the pure half (arc 43 / K5): the notebook page's own handwriting,
 * turned into the strokes the sketch surface composites — **as drawn, in black pen**.
 */
class InkBakeTest {

    private fun wire(
        x: FloatArray = floatArrayOf(0f, 10f),
        y: FloatArray = floatArrayOf(0f, 10f),
        width: Float = 3f,
        color: Int = 0xFFFF0000.toInt(),
        style: String = "FOUNTAIN",
    ) = WireStroke(x, y, FloatArray(x.size) { 1f }, FloatArray(x.size), width, color, style)

    @Test
    fun `an empty transfer bakes nothing`() {
        assertTrue(InkBake.toBakedStrokes(emptyList()).isEmpty())
    }

    @Test
    fun `every baked stroke is opaque black whatever colour it crossed as`() {
        val baked = InkBake.toBakedStrokes(listOf(wire(color = 0xFFFF0000.toInt()), wire(color = 0x8800FF00.toInt())))
        assertEquals(2, baked.size)
        baked.forEach { assertEquals(InkBake.BAKE_COLOR, it.color) }
        assertNotEquals("a transparent ink would bake as a ghost", 0, InkBake.BAKE_COLOR ushr 24)
    }

    @Test
    fun `width and style survive the bake - it is a trace, not a restyle`() {
        val baked = InkBake.toBakedStrokes(listOf(wire(width = 4.5f, style = "FOUNTAIN")))
        assertEquals(4.5f, baked.single().width, 0f)
        assertEquals(StrokeStyle.FOUNTAIN, baked.single().style)
    }

    @Test
    fun `geometry crosses unchanged, at 1 to 1`() {
        val baked = InkBake.toBakedStrokes(listOf(wire(x = floatArrayOf(3f, 7f), y = floatArrayOf(11f, 13f))))
        val points = baked.single().points
        assertEquals(3f, points[0].x, 0f)
        assertEquals(11f, points[0].y, 0f)
        assertEquals(7f, points[1].x, 0f)
        assertEquals(13f, points[1].y, 0f)
    }

    @Test
    fun `an unknown style name reads as PEN and a silly width is clamped - nothing from the wire is trusted`() {
        val baked = InkBake.toBakedStrokes(listOf(wire(width = 9_000f, style = "CRAYON")))
        assertEquals(StrokeStyle.PEN, baked.single().style)
        assertEquals(50f, baked.single().width, 0f)
    }

    @Test
    fun `ids are minted here - none crosses the seam`() {
        var n = 0
        val baked = InkBake.toBakedStrokes(listOf(wire(), wire())) { "minted-${n++}" }
        assertEquals(listOf("minted-0", "minted-1"), baked.map { it.id })
    }
}
