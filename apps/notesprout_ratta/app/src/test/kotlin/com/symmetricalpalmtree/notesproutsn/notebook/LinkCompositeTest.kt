package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composite's stroke-width margin (eye-check #7): `Stroke.bounds` is point-tight, rendered ink
 * overhangs it by half the width plus the cap, so the bitmap must be padded — and the renderer's
 * reuse check must expect the padded size, or a stale bitmap would be reused at the wrong offset.
 */
class LinkCompositeTest {

    private fun link(strokes: List<Stroke>, headings: List<Heading> = emptyList()) = PageLink(
        id = "l1", payload = "", chrome = LinkPayload.CHROME_NONE,
        x = 10f, y = 20f, width = 100f, height = 50f, order = 0,
        strokes = strokes, headings = headings,
    )

    private fun stroke(width: Float) = Stroke(
        id = "s", points = listOf(StrokePoint(1f, 2f), StrokePoint(3f, 4f)), width = width,
    )

    @Test
    fun `pad is half the widest stroke plus a pixel of slop`() {
        assertEquals(3, LinkComposite.padOf(link(listOf(stroke(3f), stroke(1f)))))  // ceil(1.5)+1
        assertEquals(5, LinkComposite.padOf(link(listOf(stroke(8f)))))              // 4+1
    }

    @Test
    fun `a heading-only link needs no pad`() {
        val h = Heading(id = "h", text = "## T", level = 2, x = 0f, y = 0f, width = 60f, height = 30f, order = 0)
        assertEquals(0, LinkComposite.padOf(link(emptyList(), listOf(h))))
    }

    @Test
    fun `sizeOf is the bounds plus the pad on each side`() {
        val l = link(listOf(stroke(3f)))   // pad 3
        assertEquals(106 to 56, LinkComposite.sizeOf(l))
    }
}
