package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Which day the Events button opens on (arc 24 / Z2) — one line of arithmetic, pinned because it
 *  is a locked wizard decision: the **first day of the period showing**, never the anchor. */
class EventsLaunchTest {

    @Test
    fun aMonthOpensOnTheFirst() {
        assertEquals(
            LocalDate.of(2026, 9, 1),
            EventsLaunch.launchDay(CalendarTarget.KIND_MONTH, LocalDate.of(2026, 9, 1)),
        )
        // Handed a day inside the month rather than its first, it still answers the first.
        assertEquals(
            LocalDate.of(2026, 9, 1),
            EventsLaunch.launchDay(CalendarTarget.KIND_MONTH, LocalDate.of(2026, 9, 17)),
        )
    }

    @Test
    fun aWeekOpensOnItsSunday() {
        // Aug 30 2026 is a Sunday; Sep 1 sits in that week.
        assertEquals(
            LocalDate.of(2026, 8, 30),
            EventsLaunch.launchDay(CalendarTarget.KIND_WEEK, LocalDate.of(2026, 8, 30)),
        )
        assertEquals(
            LocalDate.of(2026, 8, 30),
            EventsLaunch.launchDay(CalendarTarget.KIND_WEEK, LocalDate.of(2026, 9, 1)),
        )
    }

    @Test
    fun aDayOpensOnThatDay() {
        val sep17 = LocalDate.of(2026, 9, 17)
        assertEquals(sep17, EventsLaunch.launchDay(CalendarTarget.KIND_DAY, sep17))
    }

    @Test
    fun anUnknownKindAnswersTheDayHandedOver() {
        // Unreachable from the calendar screen; a screen that will not open is worse than one that
        // opens on the day it was given.
        val sep17 = LocalDate.of(2026, 9, 17)
        assertEquals(sep17, EventsLaunch.launchDay(99, sep17))
    }
}
