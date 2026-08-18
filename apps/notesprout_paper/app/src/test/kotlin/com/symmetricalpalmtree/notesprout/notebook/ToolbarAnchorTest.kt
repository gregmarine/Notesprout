package com.symmetricalpalmtree.notesprout.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarAnchorTest {
    // Root 1000 wide; band 100..1900 (top bar bottom .. bottom strip top); toolbar 300×60, gap 10.
    private fun place(l: Int, t: Int, r: Int, b: Int) = ToolbarAnchor.place(l, t, r, b, 300, 60, 10, 1000, 100, 1900)

    @Test
    fun belowAndCentred() {
        val p = place(400, 500, 600, 700)
        assertEquals(350, p.x); assertEquals(710, p.y); assertFalse(p.flipped)
    }

    @Test
    fun flipsAboveNearBottom() {
        val p = place(400, 1700, 600, 1880)
        assertTrue(p.flipped); assertEquals(1700 - 10 - 60, p.y)
    }

    @Test
    fun clampsHorizontally() {
        assertEquals(0, place(0, 500, 50, 600).x)
        assertEquals(700, place(950, 500, 1000, 600).x)
    }

    @Test
    fun clampsIntoBandWhenNeitherFits() {
        // Selection spans the whole band: below is out, above is out → clamped to the band top.
        val p = place(0, 100, 1000, 1900)
        assertEquals(100, p.y)
    }

    @Test
    fun subHangsOffToolbar() {
        val bar = place(400, 500, 600, 700)
        val sub = ToolbarAnchor.placeSub(bar, 300, 60, 200, 60, 10, 1000, 100, 1900)
        assertEquals(bar.x + 150 - 100, sub.x)
        assertEquals(bar.y + 60 + 10, sub.y)
        assertFalse(sub.flipped)
        val flippedBar = place(400, 1700, 600, 1880)
        val subUp = ToolbarAnchor.placeSub(flippedBar, 300, 60, 200, 60, 10, 1000, 100, 1900)
        assertEquals(flippedBar.y - 10 - 60, subUp.y)
        assertTrue(subUp.flipped)
    }

    @Test
    fun subFallsBackToOtherSideWhenClipped() {
        // Toolbar sits at the very bottom of the band (not flipped): the sub goes above it.
        val bar = ToolbarAnchor.Placement(350, 1840, false)
        val sub = ToolbarAnchor.placeSub(bar, 300, 60, 200, 60, 10, 1000, 100, 1900)
        assertEquals(1840 - 10 - 60, sub.y); assertTrue(sub.flipped)
    }
}
