package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The recurrence engine, pinned against og's own cases re-derived — plus the one place this engine
 * deliberately answers differently (`weeksAreCountedFromSundays_notIsoMondays`).
 */
class RecurrenceTest {

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    private fun occursOn(rule: RecurrenceRule, anchor: LocalDate, day: LocalDate, span: Long = 0, exceptions: Set<LocalDate> = emptySet()) =
        Recurrence.occursOn(rule, anchor, anchor.plusDays(span), exceptions, day)

    // ── DAILY ──────

    @Test
    fun dailyEveryThirdDay() {
        val rule = RecurrenceRule(Freq.DAILY, interval = 3)
        val anchor = day(2026, 9, 1)
        assertTrue(occursOn(rule, anchor, day(2026, 9, 1)))
        assertFalse(occursOn(rule, anchor, day(2026, 9, 2)))
        assertFalse(occursOn(rule, anchor, day(2026, 9, 3)))
        assertTrue(occursOn(rule, anchor, day(2026, 9, 4)))
        assertFalse(occursOn(rule, anchor, day(2026, 8, 29)))   // nothing before the anchor
        assertEquals(
            listOf(day(2026, 9, 1), day(2026, 9, 4), day(2026, 9, 7)),
            Recurrence.generateStarts(rule, anchor, 3),
        )
    }

    // ── WEEKLY ──────

    @Test
    fun weeklyWeekdaySetEveryOtherWeekFromAMidWeekAnchor() {
        val rule = RecurrenceRule(Freq.WEEKLY, interval = 2, weekdays = setOf(1, 3))   // Mon + Wed
        val anchor = day(2026, 9, 2)                                                   // a Wednesday
        // The anchor's own week still yields the rest of its listed days (og's shape); the Monday
        // before the anchor is not an occurrence, because nothing precedes an anchor.
        assertEquals(
            listOf(day(2026, 9, 2), day(2026, 9, 14), day(2026, 9, 16), day(2026, 9, 28)),
            Recurrence.generateStarts(rule, anchor, 4),
        )
        assertTrue(occursOn(rule, anchor, day(2026, 9, 16)))
        assertFalse(occursOn(rule, anchor, day(2026, 9, 9)))    // the skipped week
        assertFalse(occursOn(rule, anchor, day(2026, 9, 15)))   // a Tuesday, not listed
    }

    /**
     * **The deliberate divergence, pinned.** og counts WEEKLY interval weeks from ISO Mondays; this
     * calendar's weeks start on Sunday everywhere else, so the week index is counted from Sundays.
     *
     * Anchor Sat 2026-09-05, "every 2 weeks on Sunday": under ISO weeks Sep 5 and Sep 6 share a week,
     * so Sep 6 would be an occurrence. Under Sunday weeks Sep 6 opens the *next* week — the skipped
     * one — and the first occurrence is Sep 13. This is intended; do not "fix" it.
     */
    @Test
    fun weeksAreCountedFromSundays_notIsoMondays() {
        val rule = RecurrenceRule(Freq.WEEKLY, interval = 2, weekdays = setOf(7))   // Sunday
        val anchor = day(2026, 9, 5)                                                // a Saturday
        assertFalse("Sep 6 shares an ISO week with the anchor but not a Sunday week", occursOn(rule, anchor, day(2026, 9, 6)))
        assertTrue(occursOn(rule, anchor, day(2026, 9, 13)))
        assertEquals(listOf(day(2026, 9, 13), day(2026, 9, 27)), Recurrence.generateStarts(rule, anchor, 2))
    }

    @Test
    fun weeklyWithNoWeekdaysUsesTheAnchorsOwn() {
        val rule = RecurrenceRule(Freq.WEEKLY)
        val anchor = day(2026, 9, 2)   // Wednesday
        assertTrue(occursOn(rule, anchor, day(2026, 9, 9)))
        assertFalse(occursOn(rule, anchor, day(2026, 9, 10)))
    }

    // ── MONTHLY ──────

    @Test
    fun monthlyOnTheThirtyFirstSkipsShortMonths() {
        val rule = RecurrenceRule(Freq.MONTHLY, monthlyMode = MonthlyMode.DAY_OF_MONTH)
        val anchor = day(2026, 1, 31)
        assertEquals(
            listOf(day(2026, 1, 31), day(2026, 3, 31), day(2026, 5, 31), day(2026, 7, 31)),
            Recurrence.generateStarts(rule, anchor, 4),
        )
        assertFalse(occursOn(rule, anchor, day(2026, 2, 28)))
        assertTrue(occursOn(rule, anchor, day(2026, 3, 31)))
    }

    @Test
    fun monthlyOnTheSecondTuesday() {
        val rule = RecurrenceRule(Freq.MONTHLY, monthlyMode = MonthlyMode.ORDINAL_WEEKDAY)
        val anchor = day(2026, 9, 8)   // the 2nd Tuesday of September
        assertEquals(listOf(day(2026, 9, 8), day(2026, 10, 13)), Recurrence.generateStarts(rule, anchor, 2))
        assertTrue(occursOn(rule, anchor, day(2026, 10, 13)))
        assertFalse(occursOn(rule, anchor, day(2026, 10, 6)))   // the 1st Tuesday
    }

    @Test
    fun theFifthSlotMeansLast() {
        val rule = RecurrenceRule(Freq.MONTHLY, monthlyMode = MonthlyMode.ORDINAL_WEEKDAY)
        val anchor = day(2026, 9, 29)   // the 5th (and last) Tuesday of September
        assertEquals(listOf(day(2026, 9, 29), day(2026, 10, 27)), Recurrence.generateStarts(rule, anchor, 2))
        assertTrue("October's last Tuesday", occursOn(rule, anchor, day(2026, 10, 27)))
        assertFalse(occursOn(rule, anchor, day(2026, 10, 20)))
    }

    // ── YEARLY ──────

    @Test
    fun yearlyOnFebruary29LandsOnlyInLeapYears() {
        val rule = RecurrenceRule(Freq.YEARLY)
        val anchor = day(2024, 2, 29)
        assertEquals(
            listOf(day(2024, 2, 29), day(2028, 2, 29), day(2032, 2, 29)),
            Recurrence.generateStarts(rule, anchor, 3),
        )
        assertFalse(occursOn(rule, anchor, day(2025, 2, 28)))
        assertFalse(occursOn(rule, anchor, day(2026, 3, 1)))
        assertTrue(occursOn(rule, anchor, day(2028, 2, 29)))
    }

    // ── Ends ──────

    @Test
    fun countEnumeratesAndThenStops() {
        val rule = RecurrenceRule(Freq.DAILY, endMode = EndMode.COUNT, endCount = 5)
        val anchor = day(2026, 9, 1)
        assertTrue(occursOn(rule, anchor, day(2026, 9, 5)))
        assertFalse("the 6th day is past the 5 occurrences", occursOn(rule, anchor, day(2026, 9, 6)))
    }

    @Test
    fun untilIsInclusive() {
        val rule = RecurrenceRule(Freq.DAILY, endMode = EndMode.UNTIL, untilDate = day(2026, 9, 5))
        val anchor = day(2026, 9, 1)
        assertTrue(occursOn(rule, anchor, day(2026, 9, 5)))
        assertFalse(occursOn(rule, anchor, day(2026, 9, 6)))
    }

    // ── Exceptions + spans ──────

    @Test
    fun anExcludedStartTakesItsWholeSpanWithIt() {
        val rule = RecurrenceRule(Freq.DAILY, interval = 10)
        val anchor = day(2026, 9, 1)
        val excluded = setOf(day(2026, 9, 11))
        assertTrue(occursOn(rule, anchor, day(2026, 9, 2), span = 2))
        assertFalse(occursOn(rule, anchor, day(2026, 9, 11), span = 2, exceptions = excluded))
        assertFalse("the second day of the removed occurrence", occursOn(rule, anchor, day(2026, 9, 12), span = 2, exceptions = excluded))
        assertTrue(occursOn(rule, anchor, day(2026, 9, 21), span = 2, exceptions = excluded))
    }

    @Test
    fun aSpanIsCoveredByItsOwnStart() {
        val rule = RecurrenceRule(Freq.YEARLY)
        val anchor = day(2026, 9, 1)
        val end = day(2026, 9, 3)
        assertTrue(Recurrence.occursOn(rule, anchor, end, emptySet(), day(2026, 9, 2)))
        assertEquals(
            day(2027, 9, 1),
            Recurrence.occurrenceStartCovering(rule, anchor, end, emptySet(), day(2027, 9, 3)),
        )
        assertNull(Recurrence.occurrenceStartCovering(rule, anchor, end, emptySet(), day(2027, 9, 4)))
    }

    @Test
    fun theEventOverloadsAnswerForAOneOffToo() {
        val e = testEvent(start = day(2026, 9, 1), end = day(2026, 9, 3))
        assertTrue(Recurrence.occursOn(e, day(2026, 9, 2)))
        assertFalse(Recurrence.occursOn(e, day(2026, 9, 4)))
        assertEquals(day(2026, 9, 1), Recurrence.occurrenceStartCovering(e, day(2026, 9, 3)))
        assertNull(Recurrence.occurrenceStartCovering(e, day(2026, 9, 4)))
    }

    // ── nextOccurrenceStart ──────

    @Test
    fun nextOccurrenceIsStrictlyAfter_boundedAndExceptionSkipping() {
        val rule = RecurrenceRule(Freq.DAILY, interval = 7)
        val anchor = day(2026, 9, 1)
        assertEquals(
            day(2026, 9, 8),
            Recurrence.nextOccurrenceStart(rule, anchor, anchor, emptySet(), day(2026, 9, 1), 30),
        )
        assertNull(
            "6 days ahead cannot reach the next weekly start",
            Recurrence.nextOccurrenceStart(rule, anchor, anchor, emptySet(), day(2026, 9, 1), 6),
        )
        assertEquals(
            day(2026, 9, 15),
            Recurrence.nextOccurrenceStart(rule, anchor, anchor, setOf(day(2026, 9, 8)), day(2026, 9, 1), 30),
        )
    }

    @Test
    fun nextOccurrenceReadsTheCountEnumeration() {
        val rule = RecurrenceRule(Freq.DAILY, endMode = EndMode.COUNT, endCount = 3)
        val anchor = day(2026, 9, 1)
        assertEquals(day(2026, 9, 3), Recurrence.nextOccurrenceStart(rule, anchor, anchor, emptySet(), day(2026, 9, 2), 30))
        assertNull("nothing after the 3rd occurrence", Recurrence.nextOccurrenceStart(rule, anchor, anchor, emptySet(), day(2026, 9, 3), 30))
    }

    @Test
    fun aNonPositiveHorizonIsNothing() {
        val rule = RecurrenceRule(Freq.DAILY)
        val anchor = day(2026, 9, 1)
        assertNull(Recurrence.nextOccurrenceStart(rule, anchor, anchor, emptySet(), day(2026, 9, 1), 0))
        assertNull(Recurrence.nextOccurrenceStart(rule, anchor, anchor, emptySet(), day(2026, 9, 1), -5))
    }

    @Test
    fun generateStartsOfNothingIsEmpty() {
        assertTrue(Recurrence.generateStarts(RecurrenceRule(Freq.DAILY), day(2026, 9, 1), 0).isEmpty())
        assertTrue(Recurrence.generateStarts(RecurrenceRule(Freq.WEEKLY), day(2026, 9, 1), -1).isEmpty())
    }
}
