package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SelectionAnchor] against a Nomad-shaped band: a 1404 px-wide root whose free band runs from the
 * top bar's bottom edge (≈ 170) to the bottom strip's top (≈ 1750), with a 62 dp-tier bar (a single
 * 62 px button plus 4 px padding either side ≈ 70 × 70) and an 8 dp gap.
 */
class SelectionAnchorTest {

    private val w = 70
    private val h = 70
    private val gap = 24
    private val rootWidth = 1404
    private val bandTop = 170
    private val bandBottom = 1750

    private fun place(l: Int, t: Int, r: Int, b: Int) =
        SelectionAnchor.place(l, t, r, b, w, h, gap, rootWidth, bandTop, bandBottom)

    @Test
    fun `sits the gap below a selection with room under it`() {
        val p = place(600, 500, 800, 700)
        assertEquals(700 + gap, p.y)
        assertEquals(700 - w / 2, p.x)   // centred on x = 700
    }

    @Test
    fun `flips above when below would cross the band's bottom`() {
        // Bottom at 1700: below would end at 1700 + 24 + 70 = 1794, past 1750.
        val p = place(600, 1400, 800, 1700)
        assertEquals(1400 - gap - h, p.y)
        assertTrue(p.y + h <= bandBottom)
    }

    @Test
    fun `clamps to the band's top when the flip would go under the top bar`() {
        // A selection filling nearly the whole band: below overflows, and above lands at 190 - 94.
        val p = place(600, 190, 800, 1740)
        assertEquals(bandTop, p.y)
    }

    @Test
    fun `clamps to the band's bottom when even the flip cannot fit`() {
        // Degenerate: a selection taller than the band. Below overflows; above is far negative; the
        // clamp keeps the bar inside the band rather than off-screen.
        val p = place(600, -400, 800, 3000)
        assertTrue(p.y >= bandTop)
        assertTrue(p.y + h <= bandBottom)
    }

    @Test
    fun `x is centred on the selection`() {
        val p = place(300, 500, 500, 700)
        assertEquals(400 - w / 2, p.x)
    }

    @Test
    fun `x clamps at the left edge`() {
        val p = place(-20, 500, 40, 700)
        assertEquals(0, p.x)
    }

    @Test
    fun `x clamps at the right edge`() {
        val p = place(rootWidth - 40, 500, rootWidth + 60, 700)
        assertEquals(rootWidth - w, p.x)
    }

    @Test
    fun `a bar wider than the root still starts at zero`() {
        val p = SelectionAnchor.place(600, 500, 800, 700, rootWidth + 200, h, gap, rootWidth, bandTop, bandBottom)
        assertEquals(0, p.x)
    }

    @Test
    fun `a zero-size selection is placed like any other`() {
        val p = place(700, 700, 700, 700)
        assertEquals(700 + gap, p.y)
        assertEquals(700 - w / 2, p.x)
    }
}
