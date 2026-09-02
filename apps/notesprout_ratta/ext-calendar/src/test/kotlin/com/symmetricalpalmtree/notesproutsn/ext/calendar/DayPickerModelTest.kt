package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The picker's two grids: Sunday-first weeks with the right blanks, and the twelve months. */
class DayPickerModelTest {

    private fun rows(y: Int, m: Int) = DayPickerModel.dayRows(LocalDate.of(y, m, 1))

    @Test
    fun aMonthThatBeginsOnASundayHasNoLeadingBlanks() {
        val r = rows(2026, 3)                       // Mar 1 2026 is a Sunday, 31 days
        assertEquals(LocalDate.of(2026, 3, 1), r[0][0])
        assertEquals(5, r.size)                      // 31 days from column 0 = 5 rows
        assertEquals(LocalDate.of(2026, 3, 7), r[0][6])
    }

    @Test
    fun aMonthThatBeginsOnASaturdayCarriesSixLeadingBlanks() {
        val r = rows(2026, 8)                        // Aug 1 2026 is a Saturday, 31 days
        for (col in 0..5) assertNull("col $col", r[0][col])
        assertEquals(LocalDate.of(2026, 8, 1), r[0][6])
        assertEquals(6, r.size)                      // 6 blanks + 31 days = 37 slots
    }

    @Test
    fun aTwentyEightDayFebruaryFromSundayIsExactlyFourRows() {
        val r = rows(2026, 2)                        // Feb 1 2026 is a Sunday, 28 days
        assertEquals(4, r.size)
        assertEquals(LocalDate.of(2026, 2, 28), r[3][6])
        assertTrue("no blank in a full month", r.all { week -> week.all { it != null } })
    }

    @Test
    fun everyRowIsSevenSlotsAndNoTrailingWeekIsEverEmpty() {
        var day = LocalDate.of(2024, 1, 1)           // two years, leap February included
        while (day.year < 2026) {
            val r = DayPickerModel.dayRows(day)
            assertTrue("rows ${r.size} for $day", r.size in 4..6)
            for (week in r) {
                assertEquals("row width for $day", 7, week.size)
                assertTrue("empty trailing week for $day", week.any { it != null })
            }
            // Every day of the month, once, in order.
            val days = r.flatten().filterNotNull()
            assertEquals(day.lengthOfMonth(), days.size)
            assertEquals(day.withDayOfMonth(1), days.first())
            assertEquals(day.withDayOfMonth(day.lengthOfMonth()), days.last())
            day = day.plusMonths(1)
        }
    }

    @Test
    fun leadingBlanksMatchTheFirstDaysColumn() {
        var day = LocalDate.of(2026, 1, 1)
        repeat(12) {
            val first = DayPickerModel.dayRows(day)[0]
            val lead = day.dayOfWeek.value % 7
            assertEquals("lead for $day", lead, first.indexOfFirst { it != null })
            day = day.plusMonths(1)
        }
    }

    @Test
    fun theMonthGridIsOneThroughTwelveInFourRowsOfThree() {
        val g = DayPickerModel.monthGrid()
        assertEquals(4, g.size)
        assertTrue(g.all { it.size == 3 })
        assertEquals((1..12).toList(), g.flatten())
    }

    @Test
    fun titlesComeFromTheHandListsAndTheYearItself() {
        assertEquals("September 2026", DayPickerModel.monthTitle(LocalDate.of(2026, 9, 17)))
        assertEquals("2026", DayPickerModel.yearTitle(2026))
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), DayPickerModel.WEEKDAY_LETTERS)
    }
}
