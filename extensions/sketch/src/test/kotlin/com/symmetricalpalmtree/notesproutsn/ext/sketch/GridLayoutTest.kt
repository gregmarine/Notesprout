package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 51 / J3 — the grid's geometry: square cells, one count across the width, symmetric about
 *  the page's centre, every position computed from the centre (never accumulated). */
class GridLayoutTest {

    private val w = 1404   // the Nomad's page
    private val h = 1872

    private fun plan(count: Int, kind: Int = SketchContract.GRID_LINES) =
        GridLayout.plan(kind, count, w, h)!!

    /** What `centre ± (offset + k·cell)` answers for one axis — the test's own statement of the
     *  rule, strictly inside the page, in increasing order. */
    private fun expected(extent: Int, count: Int): List<Float> {
        val cell = w.toDouble() / count
        val offset = if (count % 2 == 0) 0.0 else cell / 2
        val c = extent / 2.0
        val out = sortedSetOf<Double>()
        var k = 0
        while (offset + k * cell < extent) {
            val d = offset + k * cell
            if (c - d > 1e-6) out += c - d
            if (c + d < extent - 1e-6) out += c + d
            k++
        }
        return out.map { it.toFloat() }
    }

    @Test fun offIsNothing() {
        assertNull(GridLayout.plan(SketchContract.GRID_OFF, 4, w, h))
        assertNull(GridLayout.plan(SketchContract.GRID_LINES, 0, w, h))
        assertNull(GridLayout.plan(SketchContract.GRID_LINES, 4, 0, h))
    }

    @Test fun cellIsWidthOverCount() {
        for (count in GuideSheet.COUNTS) assertEquals(w.toFloat() / count, plan(count).cell, 1e-4f)
    }

    @Test fun evenCountPutsALineOnTheCentre() {
        val p = plan(4)
        assertTrue(p.xs.contains(w / 2f))
        assertTrue(p.ys.contains(h / 2f))
        // 4 across: the two edges are not guides, so three columns.
        assertEquals(listOf(351f, 702f, 1053f), p.xs)
    }

    @Test fun oddCountPutsTheCentreMidCell() {
        val p = plan(3)
        assertTrue(!p.xs.contains(w / 2f))
        assertEquals(listOf(468f, 936f), p.xs)
        // Rows: centred on 936 ± 234, 702, 1170 … — the page's centre mid-cell on both axes.
        assertTrue(!p.ys.contains(h / 2f))
        assertEquals(listOf(234f, 702f, 1170f, 1638f), p.ys)
    }

    @Test fun symmetricAboutTheCentre() {
        for (count in GuideSheet.COUNTS) {
            val p = plan(count)
            for ((axis, extent) in listOf(p.xs to w, p.ys to h)) {
                val n = axis.size
                for (i in 0 until n) {
                    assertEquals("count $count", extent.toFloat(), axis[i] + axis[n - 1 - i], 1e-3f)
                }
            }
        }
    }

    @Test fun everyPositionIsCentrePlusWholeCells_noDriftAtTwelve() {
        val p = plan(12)
        assertEquals(expected(w, 12), p.xs)
        assertEquals(expected(h, 12), p.ys)
        // Exactly the partial cells: 1872 / 117 = 16 cells, 8 each side of the centre line.
        assertEquals(11, p.xs.size)
        assertEquals(15, p.ys.size)
        // Each gap is one cell, to float precision — no step accumulated.
        for (axis in listOf(p.xs, p.ys)) {
            for (i in 1 until axis.size) assertEquals(p.cell, axis[i] - axis[i - 1], 1e-3f)
        }
    }

    @Test fun everyCountMatchesTheRule() {
        for (count in GuideSheet.COUNTS) {
            assertEquals("xs at $count", expected(w, count), plan(count).xs)
            assertEquals("ys at $count", expected(h, count), plan(count).ys)
        }
    }

    @Test fun positionsStayStrictlyInsideThePage() {
        for (count in GuideSheet.COUNTS) {
            val p = plan(count)
            assertTrue(p.xs.all { it > 0f && it < w })
            assertTrue(p.ys.all { it > 0f && it < h })
        }
    }

    @Test fun dotsAreEveryCrossing() {
        val lines = plan(6)
        val dots = plan(6, SketchContract.GRID_DOTS)
        assertEquals(SketchContract.GRID_DOTS, dots.kind)
        assertEquals(lines.xs, dots.xs)
        assertEquals(lines.ys, dots.ys)
        assertEquals(dots.xs.size * dots.ys.size, dots.dotCount)
    }

    @Test fun landscapePageIsSymmetricToo() {
        val p = GridLayout.plan(SketchContract.GRID_LINES, 4, h, w)!!
        assertEquals(h / 4f, p.cell, 1e-4f)
        assertTrue(p.ys.contains(w / 2f))
    }
}
