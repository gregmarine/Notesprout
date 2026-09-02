package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** [CalendarDates] — Sunday weeks across year ends, month starts, stepping, ISO text, titles. */
class CalendarDatesTest {

    private val month = CalendarTarget.KIND_MONTH
    private val week = CalendarTarget.KIND_WEEK
    private val day = CalendarTarget.KIND_DAY

    @Test
    fun weeksStartOnSunday() {
        // 2026-09-01 is a Tuesday; its week's Sunday is Aug 30.
        assertEquals(LocalDate.of(2026, 8, 30), CalendarDates.weekStart(LocalDate.of(2026, 9, 1)))
        // A Sunday is its own week start; a Saturday reaches back six days.
        assertEquals(LocalDate.of(2026, 8, 30), CalendarDates.weekStart(LocalDate.of(2026, 8, 30)))
        assertEquals(LocalDate.of(2026, 8, 30), CalendarDates.weekStart(LocalDate.of(2026, 9, 5)))
        // Across a year end: 2026-01-01 is a Thursday → Sunday 2025-12-28.
        assertEquals(LocalDate.of(2025, 12, 28), CalendarDates.weekStart(LocalDate.of(2026, 1, 1)))
        for (i in 0 until 400) {
            val d = LocalDate.of(2025, 1, 1).plusDays(i.toLong())
            assertEquals(DayOfWeek.SUNDAY, CalendarDates.weekStart(d).dayOfWeek)
            assertTrue(!CalendarDates.weekStart(d).isAfter(d))
            assertTrue(CalendarDates.weekStart(d).plusDays(6) >= d)
        }
    }

    @Test
    fun monthStartAndFirstCell() {
        assertEquals(LocalDate.of(2026, 9, 1), CalendarDates.monthStart(LocalDate.of(2026, 9, 17)))
        // September 2026 opens on a Tuesday → the grid's first cell is Sunday Aug 30.
        assertEquals(LocalDate.of(2026, 8, 30), CalendarDates.firstCell(LocalDate.of(2026, 9, 1)))
        // A month opening on a Sunday opens its own grid (2026-02-01 is a Sunday).
        assertEquals(LocalDate.of(2026, 2, 1), CalendarDates.firstCell(LocalDate.of(2026, 2, 1)))
    }

    @Test
    fun periodDateAndNormalization() {
        val tuesday = LocalDate.of(2026, 9, 1)
        assertEquals(tuesday, CalendarDates.periodDate(month, LocalDate.of(2026, 9, 30)))
        assertEquals(LocalDate.of(2026, 8, 30), CalendarDates.periodDate(week, tuesday))
        assertEquals(tuesday, CalendarDates.periodDate(day, tuesday))
        assertTrue(CalendarDates.isNormalized(month, tuesday))
        assertFalse(CalendarDates.isNormalized(month, LocalDate.of(2026, 9, 2)))
        assertTrue(CalendarDates.isNormalized(week, LocalDate.of(2026, 8, 30)))
        assertFalse(CalendarDates.isNormalized(week, tuesday))
        assertTrue(CalendarDates.isNormalized(day, tuesday))
    }

    @Test
    fun monthStepping() {
        val am = CalendarTarget.HALF_AM
        assertEquals(LocalDate.of(2026, 10, 1) to am, CalendarDates.step(month, LocalDate.of(2026, 9, 1), am, forward = true))
        assertEquals(LocalDate.of(2026, 8, 1) to am, CalendarDates.step(month, LocalDate.of(2026, 9, 1), am, forward = false))
        // Dec → Jan and back.
        assertEquals(LocalDate.of(2027, 1, 1) to am, CalendarDates.step(month, LocalDate.of(2026, 12, 1), am, forward = true))
        assertEquals(LocalDate.of(2026, 12, 1) to am, CalendarDates.step(month, LocalDate.of(2027, 1, 1), am, forward = false))
        // An unnormalized input is normalized first, never clamped from the 31st.
        assertEquals(LocalDate.of(2026, 2, 1) to am, CalendarDates.step(month, LocalDate.of(2026, 1, 31), am, forward = true))
    }

    @Test
    fun weekStepping() {
        val am = CalendarTarget.HALF_AM
        assertEquals(LocalDate.of(2026, 9, 6) to am, CalendarDates.step(week, LocalDate.of(2026, 8, 30), am, forward = true))
        assertEquals(LocalDate.of(2026, 8, 23) to am, CalendarDates.step(week, LocalDate.of(2026, 8, 30), am, forward = false))
        // Across a year end, from a mid-week input (normalized to its Sunday first).
        assertEquals(LocalDate.of(2026, 1, 4) to am, CalendarDates.step(week, LocalDate.of(2025, 12, 31), am, forward = true))
    }

    @Test
    fun dayStepping() {
        val am = CalendarTarget.HALF_AM
        val pm = CalendarTarget.HALF_PM
        val d = LocalDate.of(2026, 9, 1)
        assertEquals(d to pm, CalendarDates.step(day, d, am, forward = true))
        assertEquals(d.plusDays(1) to am, CalendarDates.step(day, d, pm, forward = true))
        assertEquals(d to am, CalendarDates.step(day, d, pm, forward = false))
        assertEquals(d.minusDays(1) to pm, CalendarDates.step(day, d, am, forward = false))
        // Feb 29 in a leap year, and the day after it.
        val leap = LocalDate.of(2028, 2, 29)
        assertEquals(LocalDate.of(2028, 3, 1) to am, CalendarDates.step(day, leap, pm, forward = true))
        assertEquals(leap to pm, CalendarDates.step(day, LocalDate.of(2028, 3, 1), am, forward = false))
        // Dec 31 PM → Jan 1 AM.
        assertEquals(LocalDate.of(2027, 1, 1) to am, CalendarDates.step(day, LocalDate.of(2026, 12, 31), pm, forward = true))
    }

    @Test
    fun isoTextRoundTrips() {
        val d = LocalDate.of(2026, 9, 1)
        assertEquals("2026-09-01", CalendarDates.format(d))
        assertEquals(d, CalendarDates.parse("2026-09-01"))
        assertEquals(LocalDate.of(2028, 2, 29), CalendarDates.parse("2028-02-29"))
        assertNull(CalendarDates.parse("2026-9-1"))
        assertNull(CalendarDates.parse("2026-02-30"))
        assertNull(CalendarDates.parse("2027-02-29"))
        assertNull(CalendarDates.parse("20260901"))
        assertNull(CalendarDates.parse("2026-09-01T00:00"))
        assertNull(CalendarDates.parse("+2026-09-01"))
        assertNull(CalendarDates.parse("٢٠٢٦-09-01"))
        assertNull(CalendarDates.parse(""))
    }

    @Test
    fun titlesComeFromTheHandLists() {
        assertEquals("September 2026", CalendarDates.monthTitle(LocalDate.of(2026, 9, 1)))
        assertEquals("Aug 30 – Sep 5, 2026", CalendarDates.weekTitle(LocalDate.of(2026, 8, 30)))
        assertEquals("Sep 6 – 12, 2026", CalendarDates.weekTitle(LocalDate.of(2026, 9, 6)))
        assertEquals("Dec 28, 2025 – Jan 3, 2026", CalendarDates.weekTitle(LocalDate.of(2025, 12, 28)))
        assertEquals("Tue, Sep 1, 2026 · AM", CalendarDates.dayTitle(LocalDate.of(2026, 9, 1), CalendarTarget.HALF_AM))
        assertEquals("Sun, Aug 30, 2026 · PM", CalendarDates.dayTitle(LocalDate.of(2026, 8, 30), CalendarTarget.HALF_PM))
        assertEquals(7, CalendarDates.DAY_NAMES.size)
        assertEquals(12, CalendarDates.MONTH_NAMES.size)
        assertEquals(12, CalendarDates.MONTH_NAMES_SHORT.size)
        assertEquals("Sun", CalendarDates.DAY_NAMES[0])
        assertEquals("Sat", CalendarDates.DAY_NAMES[6])
    }
}
