package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SketchCover.sampleFor] — the decode's memory rule (arc 43 / K4). The rest of the class is
 * `Bitmap` work that needs a device; this is the part that decides how much memory a cover costs,
 * which is exactly the part worth pinning off it.
 */
class SketchCoverTest {

    private val edge = 512

    @Test
    fun anImageAlreadySmallEnoughIsNotSampled() {
        assertEquals(1, SketchCover.sampleFor(400, 512, edge))
        assertEquals(1, SketchCover.sampleFor(600, 800, edge))
    }

    @Test
    fun theNomadsPageSamplesToTwo() {
        // 1685 / 2 = 842 ≥ 512; / 4 = 421 < 512.
        assertEquals(2, SketchCover.sampleFor(1404, 1685, edge))
    }

    @Test
    fun theMantasPageSamplesToFour() {
        // 2480 / 4 = 620 ≥ 512; / 8 = 310 < 512.
        assertEquals(4, SketchCover.sampleFor(1860, 2480, edge))
    }

    @Test
    fun theSampledLongEdgeNeverFallsBelowTheCardsOwn() {
        // The card is never scaled UP: every sample leaves the long edge at or above `edge`.
        for (h in listOf(512, 513, 1023, 1024, 1685, 2480, 8192)) {
            val sample = SketchCover.sampleFor(h / 2, h, edge)
            assertTrue("h=$h sample=$sample", h / sample >= edge)
            assertTrue("h=$h sample=$sample", sample >= 1)
        }
    }

    @Test
    fun theSampleIsAPowerOfTwo() {
        for (h in 512..4096 step 137) {
            val sample = SketchCover.sampleFor(h, h, edge)
            assertEquals(0, sample and (sample - 1))
        }
    }
}
