package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verses column (arc 40 "Verses"): the fixed left edge, the page-fit refusal, the nearest clear y. */
class VersePlacementTest {

    private val pageW = 1000f
    private val pageH = 1400f

    @Test
    fun `the left edge is a tenth of the page`() {
        assertEquals(100f, VersePlacement.leftEdge(pageW), 0f)
        assertEquals(0f, VersePlacement.leftEdge(0f), 0f)
    }

    @Test
    fun `a box taller than the page has no spot`() {
        assertNull(VersePlacement.nearY(100f, 0f, 900f, 1401f, pageH, emptyList(), 2f))
        assertNull(VersePlacement.below(Bounds(0f, 0f, 10f, 10f), 100f, 900f, 1401f, pageH, emptyList(), 2f))
        assertEquals(0f, VersePlacement.nearY(100f, 0f, 900f, 1400f, pageH, emptyList(), 2f)!!, 0f)
    }

    @Test
    fun `an empty page takes the preferred y, clamped`() {
        assertEquals(500f, VersePlacement.nearY(100f, 500f, 900f, 200f, pageH, emptyList(), 2f)!!, 0f)
        assertEquals(1200f, VersePlacement.nearY(100f, 5000f, 900f, 200f, pageH, emptyList(), 2f)!!, 0f)
        assertEquals(0f, VersePlacement.nearY(100f, -50f, 900f, 200f, pageH, emptyList(), 2f)!!, 0f)
    }

    @Test
    fun `a taken preferred y moves to the nearest clear step`() {
        val taken = listOf(Bounds(100f, 480f, 800f, 720f))   // straddles 500..700
        val y = VersePlacement.nearY(100f, 500f, 900f, 200f, pageH, taken, 1f)!!
        // Steps of 16 px; the first clear y below is 720 + 8 gap → 736 = 500 + 15 × 16 = 740; above: 480 − 8 − 200 = 272 → 500 − 15 × 16 = 260.
        assertTrue(y == 740f || y == 260f)
        assertTrue(y + 200f + 8f <= 480f || y - 8f >= 720f)
    }

    @Test
    fun `a full page falls back to the preferred y`() {
        val wall = listOf(Bounds(0f, 0f, pageW, pageH))
        assertEquals(500f, VersePlacement.nearY(100f, 500f, 900f, 200f, pageH, wall, 1f)!!, 0f)
    }

    @Test
    fun `below the anchor when clear, else the nearest clear y from there`() {
        val anchor = Bounds(100f, 100f, 400f, 140f)
        assertEquals(156f, VersePlacement.below(anchor, 100f, 900f, 200f, pageH, emptyList(), 1f)!!, 0f)
        val blocker = listOf(Bounds(100f, 150f, 800f, 400f))
        val y = VersePlacement.below(anchor, 100f, 900f, 200f, pageH, blocker, 1f)!!
        assertTrue(y - 8f >= 400f || y + 200f + 8f <= 150f)
    }

    @Test
    fun `without drops boxes inside the ink and keeps the rest`() {
        val ink = Bounds(100f, 100f, 300f, 200f)
        val kept = VersePlacement.without(
            listOf(Bounds(110f, 110f, 200f, 150f), Bounds(0f, 0f, 50f, 50f), Bounds(250f, 150f, 350f, 250f)),
            ink,
        )
        assertEquals(2, kept.size)
    }
}
