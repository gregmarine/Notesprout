package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a pasted selection lands — the arithmetic that decides whether ink ends up off the page. */
class ObjectPlacementTest {

    private val pageW = 1000f
    private val pageH = 2000f

    private fun box(l: Float, t: Float, w: Float, h: Float) = Bounds(l, t, l + w, t + h)

    // ── centred on the tap ───────────────────────────────────────────────────

    @Test
    fun `a tap in open space centres the box on it`() {
        val o = ObjectPlacement.centredOn(box(100f, 100f, 200f, 100f), 500f, 700f, pageW, pageH)
        // left 100 → 400 (500 - 200/2); top 100 → 650 (700 - 100/2)
        assertEquals(300f, o.dx, 0.001f)
        assertEquals(550f, o.dy, 0.001f)
    }

    @Test
    fun `a tap near the top-left corner pulls the box back onto the page`() {
        val o = ObjectPlacement.centredOn(box(400f, 400f, 200f, 100f), 10f, 10f, pageW, pageH)
        // Centring would put it at -90,-40; the clamp lands it flush at 0,0.
        assertEquals(-400f, o.dx, 0.001f)
        assertEquals(-400f, o.dy, 0.001f)
    }

    @Test
    fun `a tap near the bottom-right corner pulls the box back onto the page`() {
        val o = ObjectPlacement.centredOn(box(0f, 0f, 200f, 100f), 990f, 1990f, pageW, pageH)
        assertEquals(pageW - 200f, o.dx, 0.001f)
        assertEquals(pageH - 100f, o.dy, 0.001f)
    }

    @Test
    fun `content wider than the page pastes from the left edge`() {
        val o = ObjectPlacement.centredOn(box(120f, 300f, 1400f, 100f), 500f, 500f, pageW, pageH)
        assertEquals(-120f, o.dx, 0.001f)      // left edge, not centred into equal overflow
        assertEquals(150f, o.dy, 0.001f)       // the y axis still fits and still centres
    }

    // ── source coordinates (the popup's Paste) ───────────────────────────────

    @Test
    fun `a box already on the page does not move at all`() {
        val o = ObjectPlacement.atSource(box(100f, 100f, 200f, 100f), pageW, pageH)
        assertEquals(0f, o.dx, 0.001f)
        assertEquals(0f, o.dy, 0.001f)
    }

    @Test
    fun `a box hanging off a bigger source page is pulled inside this one`() {
        // Copied from a 1404-wide device, pasted into a 1000-wide page.
        val o = ObjectPlacement.atSource(box(900f, 1950f, 300f, 200f), pageW, pageH)
        assertEquals(-200f, o.dx, 0.001f)
        assertEquals(-150f, o.dy, 0.001f)
    }

    // ── degenerate inputs ────────────────────────────────────────────────────

    @Test
    fun `an unknown page size clamps nothing`() {
        val o = ObjectPlacement.centredOn(box(0f, 0f, 200f, 100f), 50f, 50f, 0f, 0f)
        assertEquals(-50f, o.dx, 0.001f)
        assertEquals(0f, o.dy, 0.001f)
    }

    @Test
    fun `a non-finite box moves nothing rather than everything`() {
        val o = ObjectPlacement.centredOn(
            Bounds(Float.NaN, 0f, Float.NaN, 100f), 500f, 500f, pageW, pageH,
        )
        assertEquals(0f, o.dx, 0.001f)
        assertEquals(450f, o.dy, 0.001f)
    }
}
