package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nearest-free-spot rule every centre drop obeys (the user's decision 2026-09-13). */
class FreePlacementTest {

    private val d = 1f   // density 1: gap 8 px, step 16 px

    private fun place(w: Float, h: Float, vararg occupied: Bounds) =
        FreePlacement.nearCentre(1000f, 800f, w, h, occupied.toList(), d)

    private fun clearOf(x: Float, y: Float, w: Float, h: Float, occupied: List<Bounds>): Boolean {
        val box = Bounds(x - 8f, y - 8f, x + w + 8f, y + h + 8f)
        return occupied.none { it.intersects(box) }
    }

    @Test
    fun `an empty page lands at the centre, exactly as before`() {
        assertEquals(TextPlacement.centred(1000f, 800f, 400f, 200f), place(400f, 200f))
    }

    @Test
    fun `a clear centre is kept even with things elsewhere on the page`() {
        val corner = Bounds(0f, 0f, 100f, 100f)
        assertEquals(300f to 300f, place(400f, 200f, corner))
    }

    @Test
    fun `an occupied centre moves the box to the nearest clear spot, with the gap`() {
        // A 72-px sticky icon sitting at the page centre (464..536 × 364..436).
        val sticky = Bounds(464f, 364f, 536f, 436f)
        val (x, y) = place(200f, 40f, sticky)
        assertTrue(clearOf(x, y, 200f, 40f, listOf(sticky)))
        // The nearest grid spot is straight up or down: the box (40 tall + 8 gap) must clear the
        // icon without touching it, which is five 16-px rows from the centred y of 380 —
        // 300 above (300+40+8 = 348 < 364) or 460 below (460−8 = 452 > 436); four rows would touch.
        assertEquals(400f, x, 0f)
        assertTrue("y=$y", y == 300f || y == 460f)
    }

    @Test
    fun `the gap keeps a landed box off its neighbour`() {
        val text = Bounds(300f, 300f, 700f, 500f)   // exactly where a 400x200 would centre
        val (x, y) = place(400f, 200f, text)
        val box = Bounds(x, y, x + 400f, y + 200f)
        assertTrue(!box.intersects(text))
        assertTrue(minOf(kotlin.math.abs(box.top - text.bottom), kotlin.math.abs(box.bottom - text.top),
            kotlin.math.abs(box.left - text.right), kotlin.math.abs(box.right - text.left)) >= 8f)
    }

    @Test
    fun `candidates are clamped onto the page, never off an edge`() {
        // A wall down the middle: everything must land to one side, fully on the page.
        val wall = Bounds(480f, 0f, 520f, 800f)
        val (x, y) = place(400f, 100f, wall)
        assertTrue(x >= 0f && x + 400f <= 1000f)
        assertTrue(y >= 0f && y + 100f <= 800f)
        assertTrue(clearOf(x, y, 400f, 100f, listOf(wall)))
        // And a box no side can hold beside the wall falls back to the centre, never off-page.
        assertEquals(TextPlacement.centred(1000f, 800f, 600f, 100f), place(600f, 100f, wall))
    }

    @Test
    fun `a full page, or a box the page cannot hold, lands at the centre rather than refusing`() {
        val everything = Bounds(-10f, -10f, 1010f, 810f)
        assertEquals(TextPlacement.centred(1000f, 800f, 400f, 200f), place(400f, 200f, everything))
        assertEquals(TextPlacement.centred(1000f, 800f, 1400f, 200f), place(1400f, 200f, Bounds(0f, 0f, 1f, 1f)))
    }

    @Test
    fun `repeated drops settle side by side around the centre, not stacked`() {
        val occupied = ArrayList<Bounds>()
        repeat(4) {
            val (x, y) = FreePlacement.nearCentre(1000f, 800f, 120f, 40f, occupied, d)
            val box = Bounds(x, y, x + 120f, y + 40f)
            assertTrue(occupied.none { it.intersects(box) })
            occupied.add(box)
        }
        assertEquals(4, occupied.distinct().size)
    }
}
