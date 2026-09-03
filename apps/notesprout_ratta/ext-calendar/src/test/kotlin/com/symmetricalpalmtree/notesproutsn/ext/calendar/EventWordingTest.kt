package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Every string an event shows, pinned literally — built from ints and the hand lists, never a formatter. */
class EventWordingTest {

    private val sep3 = LocalDate.of(2026, 9, 3)

    @Test
    fun minutesAreTwelveHour() {
        assertEquals("12:00 AM", EventWording.minute(0))
        assertEquals("12:05 AM", EventWording.minute(5))
        assertEquals("9:00 AM", EventWording.minute(540))
        assertEquals("11:59 AM", EventWording.minute(719))
        assertEquals("12:30 PM", EventWording.minute(750))
        assertEquals("1:00 PM", EventWording.minute(780))
        assertEquals("11:59 PM", EventWording.minute(1439))
    }

    @Test
    fun dates() {
        assertEquals("Sep 3", EventWording.date(sep3))
        assertEquals("Jan 1, 2027", EventWording.dateWithYear(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun theTimeBadge() {
        assertEquals("All day", EventWording.timeBadge(testEvent(allDay = true)))
        assertEquals("9:00 AM", EventWording.timeBadge(testEvent(allDay = false, startMinute = 540)))
        assertEquals("—", EventWording.timeBadge(testEvent(allDay = false, startMinute = null)))
    }

    @Test
    fun theMetaLineGrowsWithTheEvent() {
        assertEquals("Appointment", EventWording.meta(testEvent(type = EventType.APPOINTMENT)))
        assertEquals(
            "Appointment · ends 10:30 AM",
            EventWording.meta(testEvent(type = EventType.APPOINTMENT, allDay = false, startMinute = 540, endMinute = 630)),
        )
        assertEquals(
            "Vacation · Sep 3 – Sep 7",
            EventWording.meta(testEvent(type = EventType.VACATION, start = sep3, end = LocalDate.of(2026, 9, 7))),
        )
        assertEquals(
            "Birthday · Every year",
            EventWording.meta(testEvent(type = EventType.BIRTHDAY, recurrence = RecurrenceRule(Freq.YEARLY))),
        )
    }

    @Test
    fun aSpanCarriesTheYearOnBothSidesOnlyWhenTheYearsDiffer() {
        assertEquals("Sep 3 – Sep 7", EventWording.span(sep3, LocalDate.of(2026, 9, 7)))
        assertEquals(
            "Dec 28, 2026 – Jan 3, 2027",
            EventWording.span(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)),
        )
    }

    @Test
    fun recurrenceSummaries() {
        assertEquals("Every day", EventWording.recurrenceSummary(RecurrenceRule(Freq.DAILY)))
        assertEquals("Every 3 days", EventWording.recurrenceSummary(RecurrenceRule(Freq.DAILY, interval = 3)))
        assertEquals("Every week", EventWording.recurrenceSummary(RecurrenceRule(Freq.WEEKLY)))
        assertEquals("Every 2 weeks", EventWording.recurrenceSummary(RecurrenceRule(Freq.WEEKLY, interval = 2)))
        assertEquals("Every month", EventWording.recurrenceSummary(RecurrenceRule(Freq.MONTHLY)))
        assertEquals("Every 6 months", EventWording.recurrenceSummary(RecurrenceRule(Freq.MONTHLY, interval = 6)))
        assertEquals("Every year", EventWording.recurrenceSummary(RecurrenceRule(Freq.YEARLY)))
        assertEquals("Every 2 years", EventWording.recurrenceSummary(RecurrenceRule(Freq.YEARLY, interval = 2)))
    }

    @Test
    fun weekdaysAreListedSunFirst() {
        // ISO puts Sunday last; this calendar's week starts on it, and so does this list.
        assertEquals(
            "Every week on Sun, Mon, Wed",
            EventWording.recurrenceSummary(RecurrenceRule(Freq.WEEKLY, weekdays = setOf(3, 7, 1))),
        )
        assertEquals(
            "Every 2 weeks on Mon, Wed",
            EventWording.recurrenceSummary(RecurrenceRule(Freq.WEEKLY, interval = 2, weekdays = setOf(3, 1))),
        )
    }

    @Test
    fun theEndClause() {
        assertEquals(
            "Every week · until Jan 1, 2027",
            EventWording.recurrenceSummary(
                RecurrenceRule(Freq.WEEKLY, endMode = EndMode.UNTIL, untilDate = LocalDate.of(2027, 1, 1)),
            ),
        )
        assertEquals(
            "Every day · for 5 times",
            EventWording.recurrenceSummary(RecurrenceRule(Freq.DAILY, endMode = EndMode.COUNT, endCount = 5)),
        )
    }

    @Test
    fun theUpcomingRow() {
        assertEquals("Tomorrow", EventWording.upcomingBadge(1))
        assertEquals("In 6 days", EventWording.upcomingBadge(6))
        val u = UpcomingEvent(
            testEvent(type = EventType.MEETING, allDay = false, startMinute = 540),
            LocalDate.of(2026, 9, 12),
            11,
        )
        assertEquals("Meeting · Sep 12 · 9:00 AM", EventWording.upcomingMeta(u))
    }

    @Test
    fun reminderLabels() {
        assertEquals("1 day before", EventWording.reminderLabel(Reminder(1, ReminderUnit.DAYS)))
        assertEquals("3 days before", EventWording.reminderLabel(Reminder(3, ReminderUnit.DAYS)))
        assertEquals("1 week before", EventWording.reminderLabel(Reminder(1, ReminderUnit.WEEKS)))
        assertEquals("2 weeks before", EventWording.reminderLabel(Reminder(2, ReminderUnit.WEEKS)))
    }

    @Test
    fun theDayPageRowLabel() {
        assertEquals("Dentist", EventWording.dayRowLabel(1, "Dentist"))
        assertEquals("2 events", EventWording.dayRowLabel(2, "Dentist"))
        assertEquals("0 events", EventWording.dayRowLabel(0, "Dentist"))
    }

    @Test
    fun everyTypeHasItsLabelAndDefault() {
        assertEquals("Event", EventType.OTHER.label)
        assertEquals(Freq.YEARLY, EventType.BIRTHDAY.defaultFreq)
        assertEquals(Freq.YEARLY, EventType.ANNIVERSARY.defaultFreq)
        for (t in listOf(EventType.VACATION, EventType.MEETING, EventType.APPOINTMENT, EventType.OTHER)) {
            assertEquals(null, t.defaultFreq)
        }
    }
}
