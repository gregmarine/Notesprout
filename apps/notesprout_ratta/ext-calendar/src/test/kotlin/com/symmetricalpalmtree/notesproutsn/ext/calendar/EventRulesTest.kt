package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Every cap, every field a mode makes meaningless, and the two things normalization cannot fix. */
class EventRulesTest {

    private val day = LocalDate.of(2026, 9, 1)

    @Test
    fun theTitleIsTrimmed_tabsAndNewlinesDropped_thenCut() {
        assertEquals("Dentist", EventRules.normalize(testEvent(title = "  Dentist  ")).title)
        // The tag rule: dropped, not replaced by a space — a pasted two-line title stays one line.
        assertEquals("DentistTuesday", EventRules.normalize(testEvent(title = "Dentist\nTuesday")).title)
        assertEquals("ab", EventRules.normalize(testEvent(title = "a\tb")).title)
        assertEquals("ab", EventRules.normalize(testEvent(title = "a\r\nb")).title)
        assertEquals(EventRules.TITLE_MAX, EventRules.normalize(testEvent(title = "x".repeat(500))).title.length)
    }

    @Test
    fun theNoteTextIsCut() {
        assertEquals(EventRules.NOTE_TEXT_MAX, EventRules.normalize(testEvent(noteText = "n".repeat(20_000))).noteText.length)
    }

    @Test
    fun remindersAreFilteredDedupedSortedAndCapped() {
        val e = EventRules.normalize(
            testEvent(
                reminders = listOf(
                    Reminder(0, ReminderUnit.DAYS),          // no lead at all
                    Reminder(2, ReminderUnit.WEEKS),         // 14 days
                    Reminder(3, ReminderUnit.DAYS),
                    Reminder(3, ReminderUnit.DAYS),          // a duplicate
                    Reminder(1, ReminderUnit.DAYS),
                    Reminder(1, ReminderUnit.WEEKS),         // 7 days
                ),
            ),
        )
        assertEquals(
            listOf(Reminder(1, ReminderUnit.DAYS), Reminder(3, ReminderUnit.DAYS), Reminder(1, ReminderUnit.WEEKS)),
            e.reminders,
        )
        assertTrue(e.reminders.size <= EventRules.REMINDERS_MAX)
    }

    @Test
    fun sevenDaysAndOneWeekAreOrderedByUnit_notByEntry() {
        val e = EventRules.normalize(testEvent(reminders = listOf(Reminder(1, ReminderUnit.WEEKS), Reminder(7, ReminderUnit.DAYS))))
        assertEquals(listOf(Reminder(7, ReminderUnit.DAYS), Reminder(1, ReminderUnit.WEEKS)), e.reminders)
    }

    @Test
    fun anEndBeforeTheStartBecomesTheStart() {
        assertEquals(day, EventRules.normalize(testEvent(start = day, end = day.minusDays(3))).endDate)
        assertEquals(day.plusDays(2), EventRules.normalize(testEvent(start = day, end = day.plusDays(2))).endDate)
    }

    @Test
    fun allDayClearsBothMinutes_andMinutesAreCoerced() {
        val allDay = EventRules.normalize(testEvent(allDay = true, startMinute = 540, endMinute = 600))
        assertNull(allDay.startMinute)
        assertNull(allDay.endMinute)
        val timed = EventRules.normalize(testEvent(allDay = false, startMinute = -20, endMinute = 5_000))
        assertEquals(0, timed.startMinute)
        assertEquals(1439, timed.endMinute)
    }

    @Test
    fun anEndMinuteBeforeTheStartMinuteIsCleared() {
        val e = EventRules.normalize(testEvent(allDay = false, startMinute = 600, endMinute = 540))
        assertEquals(600, e.startMinute)
        assertNull(e.endMinute)
    }

    @Test
    fun theIntervalIsCoercedIntoRange() {
        assertEquals(1, EventRules.normalize(testEvent(recurrence = RecurrenceRule(Freq.DAILY, interval = 0))).recurrence!!.interval)
        assertEquals(99, EventRules.normalize(testEvent(recurrence = RecurrenceRule(Freq.DAILY, interval = 500))).recurrence!!.interval)
    }

    @Test
    fun weekdaysAreFilteredAndClearedUnlessWeekly() {
        val weekly = EventRules.normalize(testEvent(recurrence = RecurrenceRule(Freq.WEEKLY, weekdays = setOf(0, 1, 3, 9))))
        assertEquals(setOf(1, 3), weekly.recurrence!!.weekdays)
        val monthly = EventRules.normalize(testEvent(recurrence = RecurrenceRule(Freq.MONTHLY, weekdays = setOf(1, 3))))
        assertTrue(monthly.recurrence!!.weekdays.isEmpty())
    }

    @Test
    fun theEndConditionKeepsOnlyItsOwnField() {
        val never = EventRules.normalize(
            testEvent(recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.NEVER, untilDate = day, endCount = 5)),
        ).recurrence!!
        assertNull(never.untilDate)
        assertNull(never.endCount)

        val until = EventRules.normalize(
            testEvent(recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.UNTIL, untilDate = day, endCount = 5)),
        ).recurrence!!
        assertEquals(day, until.untilDate)
        assertNull(until.endCount)

        val count = EventRules.normalize(
            testEvent(recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.COUNT, untilDate = day, endCount = 5_000)),
        ).recurrence!!
        assertNull(count.untilDate)
        assertEquals(999, count.endCount)
        assertEquals(
            1,
            EventRules.normalize(testEvent(recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.COUNT, endCount = 0))).recurrence!!.endCount,
        )
    }

    @Test
    fun normalizingIsIdempotent() {
        val once = EventRules.normalize(
            testEvent(
                title = "  Two\nWords  ",
                allDay = false,
                startMinute = 600,
                endMinute = 30,
                end = day.minusDays(1),
                reminders = listOf(Reminder(2, ReminderUnit.WEEKS), Reminder(2, ReminderUnit.WEEKS)),
                recurrence = RecurrenceRule(Freq.WEEKLY, interval = 0, weekdays = setOf(9), endMode = EndMode.NEVER, endCount = 4),
            ),
        )
        assertEquals(once, EventRules.normalize(once))
    }

    @Test
    fun theTwoProblems() {
        assertNull(EventRules.problem(EventRules.normalize(testEvent(title = "Dentist"))))
        assertEquals(EventRules.Problem.EMPTY_TITLE, EventRules.problem(EventRules.normalize(testEvent(title = "  \n "))))
        val backwards = EventRules.normalize(
            testEvent(start = day, recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.UNTIL, untilDate = day.minusDays(1))),
        )
        assertEquals(EventRules.Problem.UNTIL_BEFORE_START, EventRules.problem(backwards))
        // The start itself is not "before the start" — a series of exactly one occurrence is legal.
        val sameDay = EventRules.normalize(
            testEvent(start = day, recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.UNTIL, untilDate = day)),
        )
        assertNull(EventRules.problem(sameDay))
    }
}
