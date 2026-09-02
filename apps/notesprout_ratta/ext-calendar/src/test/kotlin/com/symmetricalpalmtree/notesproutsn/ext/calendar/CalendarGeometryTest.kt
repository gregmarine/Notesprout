package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Month page's rects and its hit-test — at the Nomad's size and at a page too short for it. */
class CalendarGeometryTest {

    /** The Nomad: 1404 × 1872 at 1.875, two 56 dp bars plus their 1 dp hairline. */
    private val nomad = CalendarGeometry.month(1404, 1872, 1.875f, topInsetPx = 107, bottomInsetPx = 107)
    private val september = LocalDate.of(2026, 9, 1)   // a Tuesday; the grid opens Sun Aug 30

    @Test
    fun hairlineIsRoundedDensity_andEveryEdgeIsAnInteger() {
        assertEquals(2, nomad.hairline)
        assertEquals(1, CalendarGeometry.month(1000, 1000, 1f, 0, 0).hairline)
        assertEquals(1, CalendarGeometry.month(1000, 1000, 0.75f, 0, 0).hairline)
        assertEquals(3, CalendarGeometry.month(1000, 1000, 3.0f, 0, 0).hairline)
    }

    @Test
    fun cellsAreSquareFromTheWidth_andTheNotesBandTakesTheRest() {
        val g = nomad
        assertEquals((1404 - 6 * 2) / 7, g.cell)          // 198
        assertEquals(198, g.cell)
        assertEquals(3, g.left)                             // (1404 − (7·198 + 6·2)) / 2
        assertEquals(1401, g.contentRight)
        assertEquals(107, g.headerTop)
        assertEquals(107 + 75, g.headerBottom)              // 40 dp → 75 px
        assertEquals(g.headerBottom + 2, g.gridTop)
        assertEquals(g.gridTop + 6 * 198 + 5 * 2, g.gridBottom)
        assertEquals(g.gridBottom + 2, g.notesTop)
        assertEquals(1872 - 107, g.notesBottom)
        assertTrue("notes band ${g.notesHeight}", g.notesHeight > 300)
        // Nothing is a proportional slice of the height: the same width at a taller page gives the
        // same cells and a taller band.
        val taller = CalendarGeometry.month(1404, 2400, 1.875f, 107, 107)
        assertEquals(g.cell, taller.cell)
        assertEquals(g.notesHeight + (2400 - 1872), taller.notesHeight)
    }

    @Test
    fun dividersSitOnIntegerEdgesBetweenCells() {
        val g = nomad
        for (c in 1..6) {
            assertEquals(g.cellLeft(c) - g.hairline, g.columnDividerX(c))
            assertEquals(g.cellLeft(c - 1) + g.cell, g.columnDividerX(c))
        }
        for (r in 1..5) {
            assertEquals(g.cellTop(r) - g.hairline, g.rowDividerY(r))
            assertEquals(g.cellTop(r - 1) + g.cell, g.rowDividerY(r))
        }
    }

    @Test
    fun aShortPageShrinksTheCellsRatherThanRunningUnderTheBar() {
        val g = CalendarGeometry.month(1404, 900, 1.875f, 107, 107)
        assertTrue(g.cell < 198)
        assertTrue(g.gridBottom + g.hairline <= 900 - 107)
        // Integer cells leave at most the division's remainder as a band — a few px, never negative.
        assertTrue("band ${g.notesHeight}", g.notesHeight in 0..6)
        assertTrue(g.notesBottom >= g.notesTop)
    }

    @Test
    fun hitTestNamesTheCellsDay() {
        val g = nomad
        // Top-left cell = the grid's first cell, Sunday Aug 30; row 0 col 2 = Tue Sep 1.
        assertEquals(LocalDate.of(2026, 8, 30), g.hitTest(g.cellLeft(0) + 1f, g.cellTop(0) + 1f, september))
        assertEquals(september, g.hitTest(g.cellLeft(2) + 10f, g.cellTop(0) + 10f, september))
        // Row 4 col 3 = Sep 30 (Wed); row 5 col 6 = Oct 10, an out-of-month cell — writable, hit-testable.
        assertEquals(LocalDate.of(2026, 9, 30), g.hitTest(g.cellLeft(3) + 10f, g.cellTop(4) + 10f, september))
        assertEquals(LocalDate.of(2026, 10, 10), g.hitTest(g.cellLeft(6) + g.cell - 1f, g.cellTop(5) + g.cell - 1f, september))
    }

    @Test
    fun hitTestIsNullOffTheGrid() {
        val g = nomad
        assertNull(g.hitTest(10f, g.headerTop + 5f, september))                     // the header
        assertNull(g.hitTest(10f, g.notesTop + 5f, september))                      // the Notes band
        assertNull(g.hitTest(0f, g.cellTop(0) + 5f, september))                     // the left margin (left = 3)
        assertNull(g.hitTest(g.contentRight.toFloat(), g.cellTop(0) + 5f, september))
        assertNull(g.hitTest(g.columnDividerX(1).toFloat(), g.cellTop(0) + 5f, september))   // on a divider
        assertNull(g.hitTest(g.cellLeft(1) + 5f, g.rowDividerY(1).toFloat(), september))
        assertNull(g.hitTest(10f, 5f, september))                                   // under the top bar
        assertNull(g.hitTest(10f, 1871f, september))                                // under the bottom bar
    }
}
