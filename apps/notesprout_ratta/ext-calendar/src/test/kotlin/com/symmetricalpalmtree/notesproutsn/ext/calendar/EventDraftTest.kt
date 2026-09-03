package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Every field rule the editor has (arc 24 / Z2). These are the decisions that are silently wrong on
 * a device and obvious here: what the *other* fields should say once one of them changes.
 */
class EventDraftTest {

    private val sep1 = LocalDate.of(2026, 9, 1)      // a Tuesday
    private val sep3 = LocalDate.of(2026, 9, 3)
    private val sep5 = LocalDate.of(2026, 9, 5)
    private val now = 7_000L

    private fun blank() = EventDraft.blank("e1", sep1, now)

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun aBlankDraftAsksTheFewestQuestions() {
        val d = blank()
        assertEquals(EventType.OTHER, d.type)
        assertEquals("", d.title)
        assertEquals(sep1, d.startDate)
        assertEquals(sep1, d.endDate)
        assertTrue(d.allDay)
        assertNull(d.startMinute)
        assertNull(d.endMinute)
        assertNull(d.freq)
        assertEquals(1, d.interval)
        assertEquals(EndMode.NEVER, d.endMode)
        assertEquals(emptyList<Reminder>(), d.reminders)
        assertFalse(d.repeatTouched)
        assertEquals(now, d.createdAt)
    }

    @Test
    fun anExistingEventComesBackAsItself() {
        val e = testEvent(
            id = "x", type = EventType.MEETING, title = "Standup", start = sep1, end = sep3,
            allDay = false, startMinute = 540, endMinute = 600,
            recurrence = RecurrenceRule(Freq.WEEKLY, interval = 2, weekdays = setOf(2, 4), endMode = EndMode.COUNT, endCount = 7),
            exceptions = setOf(sep5), reminders = listOf(Reminder(1, ReminderUnit.WEEKS)),
            noteText = "note", createdAt = 42L,
        )
        val d = EventDraft.from(e)
        assertEquals(Freq.WEEKLY, d.freq)
        assertEquals(2, d.interval)
        assertEquals(setOf(2, 4), d.weekdays)
        assertEquals(EndMode.COUNT, d.endMode)
        assertEquals(7, d.endCount)
        assertEquals(setOf(sep5), d.exceptions)
        assertEquals("note", d.noteText)
        assertEquals(42L, d.createdAt)
        // An event that exists has already answered the repeat question, whatever the answer was.
        assertTrue(d.repeatTouched)
        assertEquals(e, d.toEvent(e.updatedAt))
    }

    @Test
    fun aOneOffOpenedGetsTheBlankDefaultsForTheRepeatControls() {
        val d = EventDraft.from(testEvent(recurrence = null))
        assertNull(d.freq)
        assertEquals(1, d.interval)
        assertEquals(EndMode.NEVER, d.endMode)
        assertEquals(EventDraft.DEFAULT_END_COUNT, d.endCount)
    }

    // ── Type ─────────────────────────────────────────────────────────────────

    @Test
    fun aTypeOffersItsUsualRepeatOnlyOnAnUntouchedNewDraft() {
        assertEquals(Freq.YEARLY, blank().withType(EventType.BIRTHDAY, isNew = true).freq)
        // Touched: the person has spoken about the repeat, so the type keeps its hands off it.
        val touched = blank().withFreq(Freq.MONTHLY)
        assertEquals(Freq.MONTHLY, touched.withType(EventType.BIRTHDAY, isNew = true).freq)
        // Not new: the same rule, for the same reason.
        val existing = EventDraft.from(testEvent(recurrence = null))
        assertNull(existing.withType(EventType.BIRTHDAY, isNew = false).freq)
        // The offer never counts as the person touching it — a second type change can still offer.
        val offered = blank().withType(EventType.BIRTHDAY, isNew = true)
        assertFalse(offered.repeatTouched)
        assertNull(offered.withType(EventType.MEETING, isNew = true).freq)
    }

    // ── Dates ────────────────────────────────────────────────────────────────

    @Test
    fun aOneDayEventStaysOneDayWhenItsStartMoves() {
        val moved = blank().withStartDate(sep5)
        assertEquals(sep5, moved.startDate)
        assertEquals(sep5, moved.endDate)
        // Backwards too — the end sat on the start, so it travels with it.
        val back = moved.withStartDate(sep1)
        assertEquals(sep1, back.endDate)
    }

    @Test
    fun aSpanIsPushedOnlyWhenTheStartPassesItsEnd() {
        val span = blank().withEndDate(sep3)
        assertEquals(sep3, span.withStartDate(LocalDate.of(2026, 8, 30)).endDate)   // untouched
        assertEquals(sep5, span.withStartDate(sep5).endDate)                        // pushed
    }

    @Test
    fun anEndDateNeverPrecedesTheStart() {
        val d = blank().withStartDate(sep3)
        assertEquals(sep3, d.withEndDate(sep1).endDate)
        assertEquals(sep5, d.withEndDate(sep5).endDate)
    }

    @Test
    fun anEndsOnDateBeforeTheStartIsLeftToSaveToRefuse() {
        // Quietly repairing it would hide a repeat the person thinks they set.
        val d = blank().withStartDate(sep5).withFreq(Freq.WEEKLY).withEndMode(EndMode.UNTIL).withUntil(sep1)
        assertEquals(sep1, d.untilDate)
        assertEquals(EventRules.Problem.UNTIL_BEFORE_START, d.withTitle("Thing").problem())
    }

    // ── Times ────────────────────────────────────────────────────────────────

    @Test
    fun allDayClearsBothTimesAndComingBackSeedsAStart() {
        val timed = blank().withAllDay(false).withStartTime(540).withEndTime(600)
        assertEquals(540, timed.startMinute)
        val allDay = timed.withAllDay(true)
        assertNull(allDay.startMinute)
        assertNull(allDay.endMinute)
        // Off again with nothing set: the time button has to have something to read.
        val again = allDay.withAllDay(false)
        assertEquals(TimeMath.DEFAULT_MINUTE, again.startMinute)
        assertNull(again.endMinute)
        // Off again when a start survives: it is left alone.
        assertEquals(540, timed.withAllDay(false).startMinute)
    }

    @Test
    fun anEndTimeBeforeTheStartIsCleared() {
        val d = blank().withAllDay(false).withStartTime(540).withEndTime(600)
        // Pushing the start past the end clears it, the same answer normalization gives.
        assertNull(d.withStartTime(720).endMinute)
        assertEquals(600, d.withStartTime(560).endMinute)
        // Picking one before the start is refused by clearing, never by moving the start.
        assertNull(d.withEndTime(300).endMinute)
        assertEquals(540, d.withEndTime(300).startMinute)
        // The long-press clears it outright.
        assertNull(d.withEndTime(null).endMinute)
    }

    @Test
    fun allDayIsWhatTheSavedEventSays() {
        val e = blank().withTitle("Thing").withAllDay(false).withStartTime(540).withAllDay(true).toEvent(now)
        assertTrue(e.allDay)
        assertNull(e.startMinute)
    }

    // ── The repeat ───────────────────────────────────────────────────────────

    @Test
    fun weeklyWithNoDayChosenListsTheStartsOwnWeekday() {
        // Sep 1 2026 is a Tuesday: ISO 2.
        val d = blank().withFreq(Freq.WEEKLY)
        assertEquals(setOf(2), d.weekdays)
        assertTrue(d.repeatTouched)
        // A set already chosen is never overwritten.
        val chosen = d.toggleWeekday(5).withFreq(Freq.DAILY).withFreq(Freq.WEEKLY)
        assertEquals(setOf(2, 5), chosen.weekdays)
    }

    @Test
    fun theLastWeekdayCanBeCleared() {
        // An empty set means "the day this event starts on" to the engine, so the repeat still
        // lands somewhere and the person is not stopped from un-picking a day.
        val d = blank().withFreq(Freq.WEEKLY).toggleWeekday(2)
        assertEquals(emptySet<Int>(), d.weekdays)
        assertTrue(Recurrence.occursOn(d.withTitle("T").toEvent(now), sep1.plusDays(7)))
    }

    @Test
    fun turningTheRepeatOffKeepsTheControlsButDropsTheRule() {
        val on = blank().withFreq(Freq.WEEKLY).withInterval(1)
        val off = on.withFreq(null)
        assertEquals(2, off.interval)
        assertEquals(setOf(2), off.weekdays)
        assertNull(off.withTitle("T").toEvent(now).recurrence)
        // And back on, unchanged — the controls remembered what they said.
        assertEquals(2, off.withFreq(Freq.WEEKLY).withTitle("T").toEvent(now).recurrence?.interval)
    }

    @Test
    fun theSteppersClampRatherThanDisable() {
        // A disabled control is invisible on e-ink; the ends simply have nothing left to do.
        var d = blank()
        repeat(3) { d = d.withInterval(-1) }
        assertEquals(EventRules.INTERVAL_RANGE.first, d.interval)
        d = blank()
        repeat(200) { d = d.withInterval(1) }
        assertEquals(EventRules.INTERVAL_RANGE.last, d.interval)
        var c = blank()
        repeat(20) { c = c.withCount(-1) }
        assertEquals(EventRules.END_COUNT_RANGE.first, c.endCount)
    }

    @Test
    fun endsOnSeedsItsDateSoTheControlIsNeverBlank() {
        val d = blank().withStartDate(sep3).withFreq(Freq.MONTHLY).withEndMode(EndMode.UNTIL)
        assertEquals(sep3, d.untilDate)
        // A date already chosen survives leaving the mode and coming back.
        val chosen = d.withUntil(sep5).withEndMode(EndMode.NEVER).withEndMode(EndMode.UNTIL)
        assertEquals(sep5, chosen.untilDate)
    }

    @Test
    fun theRuleTheDraftSavesIsTheRuleTheControlsShow() {
        val e = blank().withTitle("Standup")
            .withFreq(Freq.WEEKLY).withInterval(1).toggleWeekday(4)
            .withEndMode(EndMode.COUNT).withCount(-4)
            .toEvent(now)
        val r = requireNotNull(e.recurrence)
        assertEquals(Freq.WEEKLY, r.freq)
        assertEquals(2, r.interval)
        assertEquals(setOf(2, 4), r.weekdays)
        assertEquals(EndMode.COUNT, r.endMode)
        assertEquals(EventDraft.DEFAULT_END_COUNT - 4, r.endCount)
        // Normalization cleared what the mode does not use.
        assertNull(r.untilDate)
    }

    // ── Reminders ────────────────────────────────────────────────────────────

    @Test
    fun remindersRefuseTheCapADuplicateAndAnEmptyLead() {
        var d = requireNotNull(blank().addReminder(1, ReminderUnit.DAYS))
        d = requireNotNull(d.addReminder(3, ReminderUnit.DAYS))
        d = requireNotNull(d.addReminder(1, ReminderUnit.WEEKS))
        assertEquals(EventRules.REMINDERS_MAX, d.reminders.size)
        // Past the cap, a duplicate and a lead of less than a day are all **null**, so the screen
        // can say why the tap did nothing rather than looking unresponsive.
        assertNull(d.addReminder(2, ReminderUnit.DAYS))
        assertNull(blank().addReminder(1, ReminderUnit.DAYS)!!.addReminder(1, ReminderUnit.DAYS))
        assertNull(blank().addReminder(0, ReminderUnit.DAYS))
        // Ordered by the lead the look-ahead uses.
        assertEquals(listOf(1, 3, 7), d.reminders.map { it.leadDays })
    }

    @Test
    fun aChipRemovedIsGone() {
        val d = requireNotNull(requireNotNull(blank().addReminder(2, ReminderUnit.DAYS)).addReminder(1, ReminderUnit.WEEKS))
        val less = d.removeReminder(Reminder(2, ReminderUnit.DAYS))
        assertEquals(listOf(Reminder(1, ReminderUnit.WEEKS)), less.reminders)
        // Removing one that is not there changes nothing.
        assertEquals(less.reminders, less.removeReminder(Reminder(9, ReminderUnit.DAYS)).reminders)
    }

    // ── Change tracking ──────────────────────────────────────────────────────

    @Test
    fun changedFromIgnoresTheRepeatTouchedBookkeeping() {
        val d = blank()
        assertFalse(d.changedFrom(d))
        assertTrue(d.withTitle("x").changedFrom(d))
        assertTrue(d.withStartDate(sep5).changedFrom(d))
        // Touching the repeat and putting it back where it was is not an edit: the flag records a
        // gesture, not a value.
        assertFalse(d.withFreq(null).changedFrom(d))
        assertTrue(d.withFreq(Freq.DAILY).changedFrom(d))
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    @Test
    fun aBlankTitleIsTheOneThingNormalizationCannotFix() {
        assertEquals(EventRules.Problem.EMPTY_TITLE, blank().problem())
        assertEquals(EventRules.Problem.EMPTY_TITLE, blank().withTitle("   ").problem())
        assertNull(blank().withTitle("Thing").problem())
    }

    @Test
    fun theDraftRoundTripsThroughTheEvent() {
        val d = blank().withTitle("Trip").withType(EventType.VACATION, isNew = true)
            .withEndDate(sep5).withAllDay(false).withStartTime(540).withEndTime(1020)
        val e = d.toEvent(now)
        val back = EventDraft.from(e)
        assertEquals(e, back.toEvent(now))
        assertEquals(d.title, back.title)
        assertEquals(d.startDate, back.startDate)
        assertEquals(d.endDate, back.endDate)
        assertEquals(d.startMinute, back.startMinute)
        assertEquals(d.endMinute, back.endMinute)
    }

    // ── The monthly ordinal ──────────────────────────────────────────────────

    @Test
    fun theOrdinalMatchesWhatTheEngineWillDo() {
        assertEquals(1 to false, EventDraft.ordinalOf(LocalDate.of(2026, 9, 1)))
        assertEquals(2 to false, EventDraft.ordinalOf(LocalDate.of(2026, 9, 8)))
        assertEquals(4 to false, EventDraft.ordinalOf(LocalDate.of(2026, 9, 22)))
        // The 5th slot reads as "last": most months have no fifth Tuesday, and the person picking
        // the fifth meant the last one.
        assertEquals(5 to true, EventDraft.ordinalOf(LocalDate.of(2026, 9, 29)))
        // A 4th that is also the month's last is still the 4th — that is what Recurrence matches.
        assertEquals(4 to false, EventDraft.ordinalOf(LocalDate.of(2026, 2, 25)))
    }
}
