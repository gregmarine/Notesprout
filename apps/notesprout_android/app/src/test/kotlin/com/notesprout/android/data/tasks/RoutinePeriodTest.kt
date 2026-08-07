package com.notesprout.android.data.tasks

import com.notesprout.android.data.events.Freq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The calendar arithmetic a routine is built on. A routine is anchored to a whole calendar period
 * rather than an interval, so its due date is derived, never chosen — and getting that derivation
 * wrong silently misdates every member task the routine ever generates.
 *
 * Reference week: **Sun 26 Jul 2026 – Sat 1 Aug 2026**. Weeks are Sunday-first.
 */
class RoutinePeriodTest {

    private fun d(iso: String): Long = LocalDate.parse(iso).toEpochDay()
    private fun iso(day: Long): String = LocalDate.ofEpochDay(day).toString()

    // ── dueFor ─────────────────────────────────────────────────────────────────

    @Test
    fun `daily is due on its own day`() {
        assertEquals(d("2026-07-29"), RoutinePeriod.dueFor(Freq.DAILY, d("2026-07-29")))
    }

    @Test
    fun `weekly is due the Saturday of a Sunday-first week`() {
        // Every day of the reference week resolves to the same Saturday.
        for (day in listOf(
            "2026-07-26", "2026-07-27", "2026-07-28", "2026-07-29",
            "2026-07-30", "2026-07-31", "2026-08-01",
        )) {
            assertEquals(
                "week containing $day",
                "2026-08-01",
                iso(RoutinePeriod.dueFor(Freq.WEEKLY, d(day))),
            )
        }
    }

    @Test
    fun `weekly on a Sunday looks forward, not back`() {
        // The Sunday *starts* its week — its due date is six days on, not the day before.
        assertEquals("2026-08-08", iso(RoutinePeriod.dueFor(Freq.WEEKLY, d("2026-08-02"))))
    }

    @Test
    fun `monthly is due the last day, whatever the month's length`() {
        assertEquals("2026-01-31", iso(RoutinePeriod.dueFor(Freq.MONTHLY, d("2026-01-05"))))
        assertEquals("2026-02-28", iso(RoutinePeriod.dueFor(Freq.MONTHLY, d("2026-02-05"))))
        assertEquals("2026-04-30", iso(RoutinePeriod.dueFor(Freq.MONTHLY, d("2026-04-30"))))
        // Leap year.
        assertEquals("2024-02-29", iso(RoutinePeriod.dueFor(Freq.MONTHLY, d("2024-02-01"))))
    }

    @Test
    fun `yearly is due Dec 31`() {
        assertEquals("2026-12-31", iso(RoutinePeriod.dueFor(Freq.YEARLY, d("2026-01-01"))))
        assertEquals("2026-12-31", iso(RoutinePeriod.dueFor(Freq.YEARLY, d("2026-12-31"))))
    }

    // ── startFor ───────────────────────────────────────────────────────────────

    @Test
    fun `startFor is the other end of the same period`() {
        assertEquals("2026-07-26", iso(RoutinePeriod.startFor(Freq.WEEKLY, d("2026-08-01"))))
        assertEquals("2026-02-01", iso(RoutinePeriod.startFor(Freq.MONTHLY, d("2026-02-28"))))
        assertEquals("2026-01-01", iso(RoutinePeriod.startFor(Freq.YEARLY, d("2026-12-31"))))
        assertEquals("2026-07-29", iso(RoutinePeriod.startFor(Freq.DAILY, d("2026-07-29"))))
    }

    @Test
    fun `start and due round-trip for every frequency`() {
        for (freq in RoutinePeriod.FREQUENCIES) {
            val due = RoutinePeriod.dueFor(freq, d("2026-07-29"))
            val start = RoutinePeriod.startFor(freq, due)
            assertEquals("$freq round-trip", due, RoutinePeriod.dueFor(freq, start))
        }
    }

    @Test
    fun `periodContains covers the whole span and nothing outside it`() {
        val due = d("2026-08-01")
        assertTrue(RoutinePeriod.periodContains(Freq.WEEKLY, due, d("2026-07-26")))
        assertTrue(RoutinePeriod.periodContains(Freq.WEEKLY, due, d("2026-08-01")))
        assertFalse(RoutinePeriod.periodContains(Freq.WEEKLY, due, d("2026-07-25")))
        assertFalse(RoutinePeriod.periodContains(Freq.WEEKLY, due, d("2026-08-02")))
    }

    // ── nextDueAfter — the roll-forward anchor ─────────────────────────────────

    @Test
    fun `nextDueAfter from mid-period gives this period's end`() {
        assertEquals("2026-08-01", iso(RoutinePeriod.nextDueAfter(Freq.WEEKLY, d("2026-07-29"))))
    }

    @Test
    fun `nextDueAfter from a period end steps to the next period`() {
        assertEquals("2026-08-08", iso(RoutinePeriod.nextDueAfter(Freq.WEEKLY, d("2026-08-01"))))
        assertEquals("2026-02-28", iso(RoutinePeriod.nextDueAfter(Freq.MONTHLY, d("2026-01-31"))))
        assertEquals("2027-12-31", iso(RoutinePeriod.nextDueAfter(Freq.YEARLY, d("2026-12-31"))))
        assertEquals("2026-07-30", iso(RoutinePeriod.nextDueAfter(Freq.DAILY, d("2026-07-29"))))
    }

    @Test
    fun `a routine finished late rolls to the period after the finish, not a past one`() {
        // Weekly due Sat 1 Aug, finished Mon 3 Aug. Callers pass max(due, actionDay).
        val due = d("2026-08-01")
        val finished = d("2026-08-03")
        val next = RoutinePeriod.nextDueAfter(Freq.WEEKLY, maxOf(due, finished))
        assertEquals("2026-08-08", iso(next))
    }

    @Test
    fun `a routine finished early still rolls a whole period on`() {
        // Weekly due Sat 1 Aug, finished Mon 27 Jul. max(due, action) is still the due date, so the
        // next occurrence is the following Saturday — finishing early must not pull the series in.
        val due = d("2026-08-01")
        val finished = d("2026-07-27")
        val next = RoutinePeriod.nextDueAfter(Freq.WEEKLY, maxOf(due, finished))
        assertEquals("2026-08-08", iso(next))
    }

    @Test
    fun `a monthly routine finished weeks late skips only the periods that passed`() {
        // Due 31 Jan, finished 10 Mar: February has gone entirely, so the next is 31 Mar.
        val next = RoutinePeriod.nextDueAfter(Freq.MONTHLY, maxOf(d("2026-01-31"), d("2026-03-10")))
        assertEquals("2026-03-31", iso(next))
    }

    // ── Member offsets ─────────────────────────────────────────────────────────

    @Test
    fun `a member keeps its weekday across a weekly roll`() {
        val start = d("2026-07-26")       // Sunday
        val end = d("2026-08-01")         // Saturday
        val monday = d("2026-07-27")
        val offset = RoutinePeriod.offsetOf(start, end, monday)
        assertEquals(1, offset)

        val nextEnd = RoutinePeriod.nextDueAfter(Freq.WEEKLY, end)
        val nextStart = RoutinePeriod.startFor(Freq.WEEKLY, nextEnd)
        assertEquals("2026-08-03", iso(RoutinePeriod.applyOffset(nextStart, nextEnd, offset)))
    }

    @Test
    fun `a member on the 31st is clamped into February rather than spilling into March`() {
        val janStart = d("2026-01-01")
        val janEnd = d("2026-01-31")
        val offset = RoutinePeriod.offsetOf(janStart, janEnd, d("2026-01-31"))
        assertEquals(30, offset)

        val febEnd = RoutinePeriod.nextDueAfter(Freq.MONTHLY, janEnd)
        val febStart = RoutinePeriod.startFor(Freq.MONTHLY, febEnd)
        assertEquals("2026-02-28", iso(RoutinePeriod.applyOffset(febStart, febEnd, offset)))
    }

    @Test
    fun `a mid-month member keeps its date across a roll`() {
        val janStart = d("2026-01-01")
        val janEnd = d("2026-01-31")
        val offset = RoutinePeriod.offsetOf(janStart, janEnd, d("2026-01-15"))

        val febEnd = RoutinePeriod.nextDueAfter(Freq.MONTHLY, janEnd)
        val febStart = RoutinePeriod.startFor(Freq.MONTHLY, febEnd)
        assertEquals("2026-02-15", iso(RoutinePeriod.applyOffset(febStart, febEnd, offset)))
    }

    @Test
    fun `a member dated outside its period is pulled back inside`() {
        val start = d("2026-07-26")
        val end = d("2026-08-01")
        assertEquals(0, RoutinePeriod.offsetOf(start, end, d("2026-07-01")))
        assertEquals(6, RoutinePeriod.offsetOf(start, end, d("2026-09-01")))
    }

    @Test
    fun `a daily routine's members all land on the single day`() {
        val day = d("2026-07-29")
        val offset = RoutinePeriod.offsetOf(day, day, day)
        assertEquals(0, offset)
        val next = RoutinePeriod.nextDueAfter(Freq.DAILY, day)
        assertEquals("2026-07-30", iso(RoutinePeriod.applyOffset(next, next, offset)))
    }
}
