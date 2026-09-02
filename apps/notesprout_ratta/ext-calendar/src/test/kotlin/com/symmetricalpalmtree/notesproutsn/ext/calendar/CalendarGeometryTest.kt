package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The three pages' rects and their hit-tests — at the Nomad's size and at pages it never sees. */
class CalendarGeometryTest {

    /** The Nomad: 1404 × 1872 at 1.875, two 56 dp bars plus their 1 dp hairline. */
    private val nomad = CalendarGeometry.month(1404, 1872, 1.875f, topInsetPx = 107, bottomInsetPx = 107)
    private val nomadWeek = CalendarGeometry.week(1404, 1872, 1.875f, topInsetPx = 107, bottomInsetPx = 107)
    private val nomadDay = CalendarGeometry.day(1404, 1872, 1.875f, topInsetPx = 107, bottomInsetPx = 107)
    private val september = LocalDate.of(2026, 9, 1)   // a Tuesday; the grid opens Sun Aug 30
    private val augustSunday = LocalDate.of(2026, 8, 30)   // the Sunday the week of Sep 1 opens on

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

    // ── Week ─────────────────────────────────────────────────────────────────

    @Test
    fun weekCellsAreTheWidthsQuarter_andItsBandIsMonthsBand() {
        val g = nomadWeek
        assertEquals(2, g.hairline)
        assertEquals((1404 - 3 * 2) / 4, g.cellW)            // 349
        assertEquals(349, g.cellW)
        assertEquals(1, g.left)                                // (1404 − (4·349 + 3·2)) / 2
        assertEquals(1403, g.contentRight)
        assertEquals(107, g.cellsTop)
        // The cell area IS the Month page's grid area, so the two bands match — to the one px
        // that halving an odd area cannot give back.
        assertTrue("cellsBottom ${g.cellsBottom} vs ${nomad.gridBottom}", nomad.gridBottom - g.cellsBottom in 0..1)
        assertEquals(636, g.cellH)
        assertEquals(g.cellsTop + 2 * g.cellH + g.hairline, g.cellsBottom)
        assertEquals(g.cellsBottom + g.hairline, g.notesTop)
        assertEquals(1872 - 107, g.notesBottom)
        assertTrue("week ${g.notesHeight} vs month ${nomad.notesHeight}", Math.abs(g.notesHeight - nomad.notesHeight) <= 1)
    }

    @Test
    fun weekDividersSitOnIntegerEdgesBetweenCells() {
        val g = nomadWeek
        for (c in 1..3) {
            assertEquals(g.cellLeft(c) - g.hairline, g.columnDividerX(c))
            assertEquals(g.cellLeft(c - 1) + g.cellW, g.columnDividerX(c))
        }
        assertEquals(g.cellTop(1) - g.hairline, g.rowDividerY())
        assertEquals(g.cellTop(0) + g.cellH, g.rowDividerY())
    }

    @Test
    fun weekHitTestNamesSundayThroughSaturday() {
        val g = nomadWeek
        for (index in 0..6) {
            val row = index / 4
            val col = index % 4
            assertEquals(
                "cell $index",
                augustSunday.plusDays(index.toLong()),
                g.hitTest(g.cellLeft(col) + 10f, g.cellTop(row) + 10f, augustSunday),
            )
        }
    }

    @Test
    fun weekHitTestIsNullForTheSpareCellAndEverythingThatIsNotACell() {
        val g = nomadWeek
        assertNull(g.hitTest(g.cellLeft(3) + 10f, g.cellTop(1) + 10f, augustSunday))   // the spare 8th cell
        assertNull(g.hitTest(10f, g.notesTop + 5f, augustSunday))                      // the Notes band
        assertNull(g.hitTest(0f, g.cellTop(0) + 5f, augustSunday))                     // the left margin
        assertNull(g.hitTest(g.contentRight.toFloat(), g.cellTop(0) + 5f, augustSunday))
        assertNull(g.hitTest(g.columnDividerX(1).toFloat(), g.cellTop(0) + 5f, augustSunday))
        assertNull(g.hitTest(g.cellLeft(1) + 5f, g.rowDividerY().toFloat(), augustSunday))
        assertNull(g.hitTest(10f, 5f, augustSunday))                                   // under the top bar
        assertNull(g.hitTest(10f, 1871f, augustSunday))                                // under the bottom bar
    }

    // ── Day ──────────────────────────────────────────────────────────────────

    @Test
    fun dayRowsAreAFixedDpHeight_andTheSlackIsWhatIsLeft() {
        val g = nomadDay
        assertEquals(2, g.hairline)
        assertEquals(64, g.rowHeight)                          // round(34 × 1.875)
        assertEquals(66, g.pitch)
        assertEquals(107, g.rowsTop)
        assertEquals(150, g.gutterLeft)                        // round(80 × 1.875)
        assertEquals(152, g.gutterRight)
        assertEquals(0, g.left)
        assertEquals(1404, g.right)
        assertEquals(107 + 24 * 64 + 23 * 2, g.rowsBottom)      // 1689
        for (i in 0 until CalendarGeometry.DAY_ROWS) assertEquals(107 + i * 66, g.rowTop(i))
        for (i in 1..23) assertEquals(g.rowTop(i - 1) + g.rowHeight, g.rowDividerY(i))
        // The closing hairline sits AT rowsBottom; the band opens after it.
        assertEquals(g.rowsBottom + g.hairline, g.slackTop)
        assertEquals(1872 - 107, g.slackBottom)
        assertEquals(74, g.slackHeight)
        assertTrue(g.slackHeight >= 0)
    }

    @Test
    fun dayRowsAreNeverHeightProportional() {
        // Twice the slack, the same rows: the band absorbs a taller page, the ledger does not.
        val taller = CalendarGeometry.day(1404, 2400, 1.875f, 107, 107)
        assertEquals(nomadDay.rowHeight, taller.rowHeight)
        assertEquals(nomadDay.rowsBottom, taller.rowsBottom)
        assertEquals(nomadDay.slackHeight + (2400 - 1872), taller.slackHeight)
    }

    @Test
    fun aShortDayPageShrinksTheRowsRatherThanRunningUnderTheBar() {
        val g = CalendarGeometry.day(1404, 900, 1.875f, 107, 107)
        assertTrue("row ${g.rowHeight}", g.rowHeight in 1 until 64)
        assertEquals(107 + 24 * g.rowHeight + 23 * g.hairline, g.rowsBottom)
        assertTrue("last row at ${g.rowTop(23) + g.rowHeight}", g.rowTop(23) + g.rowHeight <= 900 - 107)
        assertTrue("slack ${g.slackHeight}", g.slackHeight >= 0)
        assertTrue(g.slackBottom >= g.slackTop)
    }

    @Test
    fun dayRowLabelsAreTwelveHourAndBuiltFromInts() {
        val am = (0 until CalendarGeometry.DAY_ROWS).map { CalendarGeometry.dayRowLabel(0, it) }
        assertEquals("12:00 AM", am[0])
        assertEquals("12:30 AM", am[1])
        assertEquals("1:00 AM", am[2])
        assertEquals("11:00 AM", am[22])
        assertEquals("11:30 AM", am[23])
        val pm = (0 until CalendarGeometry.DAY_ROWS).map { CalendarGeometry.dayRowLabel(1, it) }
        assertEquals("12:00 PM", pm[0])
        assertEquals("12:30 PM", pm[1])
        assertEquals("1:00 PM", pm[2])
        assertEquals("11:30 PM", pm[23])
        // The two halves are the same twelve clock faces, only the suffix differs.
        assertEquals(am.map { it.removeSuffix(" AM") }, pm.map { it.removeSuffix(" PM") })
    }
}
