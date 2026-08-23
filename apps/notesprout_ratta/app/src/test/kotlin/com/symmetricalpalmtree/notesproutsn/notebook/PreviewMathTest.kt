package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.library.GridMath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Preview sizing (K2): real page aspect at cell width, clamped; degenerate falls back. */
class PreviewMathTest {

    @Test
    fun `aspect is height over width`() {
        assertEquals(1872f / 1404f, PreviewMath.aspect(1404, 1872), 1e-4f)
    }

    @Test
    fun `degenerate page size falls back to the library card aspect`() {
        assertEquals(GridMath.CARD_ASPECT, PreviewMath.aspect(0, 1872), 0f)
        assertEquals(GridMath.CARD_ASPECT, PreviewMath.aspect(1404, 0), 0f)
        assertEquals(GridMath.CARD_ASPECT, PreviewMath.aspect(-5, -5), 0f)
    }

    @Test
    fun `extreme aspects are clamped`() {
        assertEquals(PreviewMath.MAX_ASPECT, PreviewMath.aspect(10, 100_000), 0f)
        assertEquals(PreviewMath.MIN_ASPECT, PreviewMath.aspect(100_000, 10), 0f)
    }

    @Test
    fun `render size follows the page aspect at cell width`() {
        val (w, h) = PreviewMath.renderSize(300, 1404, 1872)
        assertEquals(300, w)
        assertEquals((300 * (1872f / 1404f)).toInt(), h)
    }

    @Test
    fun `render size is capped on both edges`() {
        val (w, h) = PreviewMath.renderSize(5_000, 1404, 1872)
        assertEquals(PreviewMath.MAX_RENDER_EDGE_PX, w)
        assertEquals(PreviewMath.MAX_RENDER_EDGE_PX, h)   // 1024 × 1.333 clamps too
        val (w2, h2) = PreviewMath.renderSize(0, 1404, 1872)
        assertEquals(1, w2)
        assertEquals(1, h2)
    }
}
