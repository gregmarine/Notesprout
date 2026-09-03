package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Upcoming look-ahead's windows and bounds — what surfaces, when, and exactly once. */
class UpcomingTest {

    private val today = LocalDate.of(2026, 9, 1)

    private fun oneOff(id: String, start: LocalDate, reminders: List<Reminder>, title: String = "Thing", allDay: Boolean = true) =
        testEvent(id = id, title = title, start = start, allDay = allDay, reminders = reminders)

    private fun daily(id: String, anchor: LocalDate, interval: Int, reminders: List<Reminder>, title: String = "Daily") =
        testEvent(id = id, title = title, start = anchor, reminders = reminders, recurrence = RecurrenceRule(Freq.DAILY, interval = interval))

    @Test
    fun aLeadThatReachesTheDaySurfaces_andOneThatDoesNotDoesNot() {
        val reaches = oneOff("a", today.plusDays(3), listOf(Reminder(3, ReminderUnit.DAYS)))
        val short = oneOff("b", today.plusDays(4), listOf(Reminder(3, ReminderUnit.DAYS)))
        val out = Upcoming.forDay(today, listOf(reaches, short), emptyList())
        assertEquals(listOf("a"), out.map { it.event.id })
        assertEquals(3, out.single().daysUntil)
        assertEquals(today.plusDays(3), out.single().occurrenceStart)
    }

    @Test
    fun theDayBeforeIsTheLastDayAnEventIsUpcoming_andTheDayItselfIsNot() {
        val e = oneOff("a", today.plusDays(1), listOf(Reminder(1, ReminderUnit.WEEKS)))
        assertEquals(1, Upcoming.forDay(today, listOf(e), emptyList()).single().daysUntil)
        // On the occurrence itself there is nothing upcoming — it is today's own list's business.
        assertTrue(Upcoming.forDay(today.plusDays(1), listOf(e), emptyList()).isEmpty())
    }

    @Test
    fun aSpanAlreadyUnderWayIsNotUpcoming() {
        val started = testEvent(id = "a", start = today.minusDays(2), end = today.plusDays(2), reminders = listOf(Reminder(3, ReminderUnit.DAYS)))
        assertTrue(Upcoming.forDay(today, listOf(started), emptyList()).isEmpty())
    }

    @Test
    fun anEventWithNoRemindersNeverSurfaces() {
        assertTrue(Upcoming.forDay(today, listOf(oneOff("a", today.plusDays(1), emptyList())), emptyList()).isEmpty())
        assertTrue(Upcoming.forDay(today, emptyList(), listOf(daily("r", today, 7, emptyList()))).isEmpty())
    }

    @Test
    fun aRecurringEventIsBoundedByItsLargestLead() {
        // Every 30 days from today: the next start is Oct 1, 30 days out.
        val monthlyish = daily("r", today, 30, listOf(Reminder(2, ReminderUnit.DAYS), Reminder(5, ReminderUnit.WEEKS)))
        val out = Upcoming.forDay(today, emptyList(), listOf(monthlyish))
        assertEquals(1, out.size)
        assertEquals(30, out.single().daysUntil)
        assertEquals(today.plusDays(30), out.single().occurrenceStart)

        val tooShort = daily("r", today, 30, listOf(Reminder(2, ReminderUnit.DAYS)))
        assertTrue(Upcoming.forDay(today, emptyList(), listOf(tooShort)).isEmpty())
    }

    @Test
    fun oneRowPerEvent_itsSoonestQualifyingOccurrence() {
        val standup = daily("r", today, 1, listOf(Reminder(3, ReminderUnit.DAYS)))
        val out = Upcoming.forDay(today, emptyList(), listOf(standup))
        assertEquals(1, out.size)
        assertEquals(1, out.single().daysUntil)
    }

    @Test
    fun anExcludedOccurrenceIsSkipped() {
        val weekly = testEvent(
            id = "r",
            start = today,
            reminders = listOf(Reminder(2, ReminderUnit.WEEKS)),
            recurrence = RecurrenceRule(Freq.DAILY, interval = 7),
            exceptions = setOf(today.plusDays(7)),
        )
        assertEquals(14, Upcoming.forDay(today, emptyList(), listOf(weekly)).single().daysUntil)
    }

    @Test
    fun nearestFirstThenAllDayThenTitle() {
        val far = oneOff("far", today.plusDays(5), listOf(Reminder(1, ReminderUnit.WEEKS)), title = "Aaa")
        val timed = oneOff("timed", today.plusDays(2), listOf(Reminder(1, ReminderUnit.WEEKS)), title = "Bbb", allDay = false)
        val allDay = oneOff("allDay", today.plusDays(2), listOf(Reminder(1, ReminderUnit.WEEKS)), title = "Zzz")
        val out = Upcoming.forDay(today, listOf(far, timed, allDay), emptyList())
        assertEquals(listOf("allDay", "timed", "far"), out.map { it.event.id })
    }

    @Test
    fun theHorizonIsAYear() {
        assertEquals(366, Upcoming.MAX_LOOKAHEAD_DAYS)
        // A lead longer than the horizon still cannot pull an event in from beyond it.
        val distant = daily("r", today, 400, listOf(Reminder(60, ReminderUnit.WEEKS)))
        assertTrue(Upcoming.forDay(today, emptyList(), listOf(distant)).isEmpty())
    }
}
