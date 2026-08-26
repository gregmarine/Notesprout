package com.symmetricalpalmtree.notesproutsn.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an imported picture is laid onto a page (arc 13 / G3; the import that produces the other two
 * modes arrives in G4). One source rect and one destination rect, both in page pixels — Fit moves
 * the destination, Fill moves the source, Stretch moves neither.
 */
class TemplateFitTest {

    private val pageW = 1404
    private val pageH = 1872

    @Test
    fun `fit keeps the whole picture and centres it`() {
        // A wide picture on a tall page: full width, letterboxed top and bottom, nothing cropped.
        val plan = TemplateFit.plan(TemplateFit.FIT, 2000, 1000, pageW, pageH)!!
        assertEquals(0f, plan.src.left, 0f)
        assertEquals(2000f, plan.src.right, 0f)
        assertEquals(pageW.toFloat(), plan.dst.width, 0.01f)
        assertEquals(702f, plan.dst.height, 0.01f)
        // Centred: equal margins.
        assertEquals(plan.dst.top, pageH - plan.dst.bottom, 0.01f)
    }

    @Test
    fun `fit never enlarges past the page`() {
        val plan = TemplateFit.plan(TemplateFit.FIT, 100, 100, pageW, pageH)!!
        assertTrue(plan.dst.width <= pageW)
        assertTrue(plan.dst.height <= pageH)
        // Square on a portrait page: width-limited, so it fills the width exactly.
        assertEquals(pageW.toFloat(), plan.dst.width, 0.01f)
    }

    @Test
    fun `stretch takes the whole picture to the whole page`() {
        val plan = TemplateFit.plan(TemplateFit.STRETCH, 300, 900, pageW, pageH)!!
        assertEquals(TemplateFit.Rect(0f, 0f, 300f, 900f), plan.src)
        assertEquals(TemplateFit.Rect(0f, 0f, pageW.toFloat(), pageH.toFloat()), plan.dst)
    }

    @Test
    fun `fill covers the page and crops the source evenly`() {
        // A wide picture: the page is covered by matching heights, and the overhanging width is
        // taken off both sides equally.
        val plan = TemplateFit.plan(TemplateFit.FILL, 4000, 2000, pageW, pageH)!!
        assertEquals(TemplateFit.Rect(0f, 0f, pageW.toFloat(), pageH.toFloat()), plan.dst)
        assertEquals(0f, plan.src.top, 0f)
        assertEquals(2000f, plan.src.bottom, 0f)
        assertEquals(1500f, plan.src.width, 0.01f)                  // 2000 × (1404/1872)
        assertEquals(plan.src.left, 4000f - plan.src.right, 0.01f)  // symmetric crop
    }

    @Test
    fun `fill of a matching aspect crops nothing`() {
        val plan = TemplateFit.plan(TemplateFit.FILL, pageW, pageH, pageW, pageH)!!
        assertEquals(TemplateFit.Rect(0f, 0f, pageW.toFloat(), pageH.toFloat()), plan.src)
        assertEquals(TemplateFit.Rect(0f, 0f, pageW.toFloat(), pageH.toFloat()), plan.dst)
    }

    @Test
    fun `a degenerate size draws nothing`() {
        assertNull(TemplateFit.plan(TemplateFit.FIT, 0, 100, pageW, pageH))
        assertNull(TemplateFit.plan(TemplateFit.FIT, 100, 100, 0, pageH))
        assertNull(TemplateFit.plan(TemplateFit.FIT, 100, 100, pageW, -1))
    }

    /**
     * The three modes' defining property, swept across the aspects a real import brings (G4):
     * **Fit** never leaves the page and never crops; **Fill** never letterboxes and only ever crops
     * one axis; **Stretch** does both rects whole. A mode that broke one of these would still pass
     * every worked example above — the examples check arithmetic, this checks the promise.
     */
    @Test
    fun `every mode keeps its promise at every aspect`() {
        val sources = listOf(
            4000 to 3000,   // landscape 4:3, a camera
            3000 to 4000,   // portrait 4:3
            1404 to 1872,   // the page's own aspect
            2480 to 3508,   // A4 at 300 dpi, a scanner
            5000 to 500,    // a panorama
            300 to 4000,    // a strip
            1000 to 1000,   // square
        )
        for ((sw, sh) in sources) {
            val fit = TemplateFit.plan(TemplateFit.FIT, sw, sh, pageW, pageH)!!
            assertEquals("fit crops $sw x $sh", 0f, fit.src.left, 0.01f)
            assertEquals("fit crops $sw x $sh", sw.toFloat(), fit.src.right, 0.01f)
            assertEquals("fit crops $sw x $sh", sh.toFloat(), fit.src.bottom, 0.01f)
            assertTrue("fit overflows $sw x $sh", fit.dst.left >= -0.01f && fit.dst.top >= -0.01f)
            assertTrue("fit overflows $sw x $sh", fit.dst.right <= pageW + 0.01f && fit.dst.bottom <= pageH + 0.01f)
            // One axis fills exactly; the other is the letterbox.
            val fillsW = Math.abs(fit.dst.width - pageW) < 0.5f
            val fillsH = Math.abs(fit.dst.height - pageH) < 0.5f
            assertTrue("fit fills neither axis at $sw x $sh", fillsW || fillsH)

            val fill = TemplateFit.plan(TemplateFit.FILL, sw, sh, pageW, pageH)!!
            assertEquals("fill letterboxes $sw x $sh", pageW.toFloat(), fill.dst.width, 0.01f)
            assertEquals("fill letterboxes $sw x $sh", pageH.toFloat(), fill.dst.height, 0.01f)
            assertTrue("fill reads outside $sw x $sh", fill.src.left >= -0.01f && fill.src.top >= -0.01f)
            assertTrue("fill reads outside $sw x $sh", fill.src.right <= sw + 0.01f && fill.src.bottom <= sh + 0.01f)
            // The crop is centred: equal margins on whichever axis it took from.
            assertEquals("fill crop off-centre $sw x $sh", fill.src.left, sw - fill.src.right, 0.01f)
            assertEquals("fill crop off-centre $sw x $sh", fill.src.top, sh - fill.src.bottom, 0.01f)

            val stretch = TemplateFit.plan(TemplateFit.STRETCH, sw, sh, pageW, pageH)!!
            assertEquals(TemplateFit.Rect(0f, 0f, sw.toFloat(), sh.toFloat()), stretch.src)
            assertEquals(TemplateFit.Rect(0f, 0f, pageW.toFloat(), pageH.toFloat()), stretch.dst)
        }
    }

    @Test
    fun `an unknown fit falls back to Fit rather than drawing something else`() {
        assertEquals(TemplateFit.FIT, TemplateFit.sanitize(null))
        assertEquals(TemplateFit.FIT, TemplateFit.sanitize(99))
        assertEquals(TemplateFit.FILL, TemplateFit.sanitize(TemplateFit.FILL))
        assertEquals(
            TemplateFit.plan(TemplateFit.FIT, 500, 500, pageW, pageH),
            TemplateFit.plan(99, 500, 500, pageW, pageH),
        )
    }
}
