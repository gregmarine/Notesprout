package com.notesprout.android.data.tasks

import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.events.MonthlyMode
import com.notesprout.android.data.index.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The next-due rule for a recurring task — the heart of the task manager and the one place its
 * behaviour genuinely differs from calendar events.
 *
 * A task's successor is due at the first occurrence in the series, anchored on the series' original
 * start, **strictly after `max(due day, action day)`**. That is what makes lateness humane (a daily
 * chore finished two days late is next due tomorrow, not yesterday) without letting an early finish
 * drag the whole series forward.
 */
class TaskRecurrenceTest {

    // 2026-01-05 is a Monday, which makes the weekday cases readable.
    private val mon = LocalDate.of(2026, 1, 5)

    private fun task(
        anchor: LocalDate,
        due: LocalDate = anchor,
        freq: Freq? = Freq.DAILY,
        interval: Int = 1,
        weekdays: List<Int> = emptyList(),
        monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
        endMode: EndMode = EndMode.NEVER,
        endDay: LocalDate? = null,
        endCount: Int? = null,
        seriesIndex: Int = 0,
    ) = TaskEntity(
        id = "t",
        type = TaskRowType.TASK_NAME,
        title = "t",
        state = TaskState.NOT_DONE.name,
        dueEpochDay = due.toEpochDay(),
        seriesId = "s",
        seriesIndex = seriesIndex,
        seriesAnchorDay = anchor.toEpochDay(),
        recurFreq = freq?.name,
        recurInterval = interval,
        recurWeekdays = TaskWeekdays.pack(weekdays),
        recurMonthlyMode = monthlyMode.name,
        recurEndMode = endMode.name,
        recurEndEpochDay = endDay?.toEpochDay(),
        recurEndCount = endCount,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun nextDue(t: TaskEntity, on: LocalDate): LocalDate? =
        TaskRecurrence.nextDue(t, on.toEpochDay())?.let { LocalDate.ofEpochDay(it) }

    // ── The core lateness rule ─────────────────────────────────────────────────

    @Test
    fun `daily finished two days late is next due the day after completion`() {
        val t = task(anchor = mon, due = mon)                 // due Mon
        assertEquals(mon.plusDays(3), nextDue(t, mon.plusDays(2))) // done Wed -> Thu
    }

    @Test
    fun `daily finished early still advances from the due date, not the completion`() {
        val t = task(anchor = mon, due = mon.plusDays(4))     // due Fri
        assertEquals(mon.plusDays(5), nextDue(t, mon.plusDays(2))) // done Wed -> Sat, not Thu
    }

    @Test
    fun `an interval series stays on the anchor's phase grid when finished late`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        // Every 3 days from Jan 1 -> Jan 1, 4, 7, 10 …
        val t = task(anchor = jan1, due = jan1.plusDays(3), interval = 3)
        // Due Jan 4, done Jan 6: the next grid point after Jan 6 is Jan 7 — NOT Jan 9 (= done + 3).
        assertEquals(LocalDate.of(2026, 1, 7), nextDue(t, LocalDate.of(2026, 1, 6)))
    }

    @Test
    fun `monthly keeps its day of month when finished in the following month`() {
        val jan15 = LocalDate.of(2026, 1, 15)
        val t = task(anchor = jan15, freq = Freq.MONTHLY)
        assertEquals(LocalDate.of(2026, 2, 15), nextDue(t, LocalDate.of(2026, 2, 3)))
    }

    @Test
    fun `yearly finished after its date rolls to the next year`() {
        val anchor = LocalDate.of(2025, 7, 4)
        val t = task(anchor = anchor, due = LocalDate.of(2026, 7, 4), freq = Freq.YEARLY)
        assertEquals(LocalDate.of(2027, 7, 4), nextDue(t, LocalDate.of(2026, 7, 20)))
    }

    @Test
    fun `weekly with a weekday set picks the next day in the set`() {
        // Mon / Wed / Fri, anchored on the Monday.
        val t = task(
            anchor = mon, due = mon.plusDays(2), freq = Freq.WEEKLY, weekdays = listOf(1, 3, 5),
        )
        // Due Wed, done Thu -> Fri.
        assertEquals(mon.plusDays(4), nextDue(t, mon.plusDays(3)))
    }

    @Test
    fun `weekly with a weekday set wraps into the next week`() {
        val t = task(
            anchor = mon, due = mon.plusDays(4), freq = Freq.WEEKLY, weekdays = listOf(1, 3, 5),
        )
        // Due Fri, done Fri -> the following Monday.
        assertEquals(mon.plusDays(7), nextDue(t, mon.plusDays(4)))
    }

    // ── Awkward calendar shapes ────────────────────────────────────────────────

    @Test
    fun `monthly on the 31st skips months too short to hold it`() {
        val jan31 = LocalDate.of(2026, 1, 31)
        val t = task(anchor = jan31, freq = Freq.MONTHLY)
        // February 2026 has 28 days, so the next occurrence is March.
        assertEquals(LocalDate.of(2026, 3, 31), nextDue(t, LocalDate.of(2026, 2, 1)))
    }

    @Test
    fun `yearly on Feb 29 lands only on leap years`() {
        val feb29 = LocalDate.of(2024, 2, 29)
        val t = task(anchor = feb29, freq = Freq.YEARLY)
        // The look-ahead bound must be generous enough to reach four years out.
        assertEquals(LocalDate.of(2028, 2, 29), nextDue(t, LocalDate.of(2024, 3, 1)))
    }

    @Test
    fun `monthly ordinal weekday follows the nth weekday, not the date`() {
        // 2026-01-05 is the first Monday of January.
        val t = task(anchor = mon, freq = Freq.MONTHLY, monthlyMode = MonthlyMode.ORDINAL_WEEKDAY)
        assertEquals(LocalDate.of(2026, 2, 2), nextDue(t, mon)) // first Monday of February
    }

    // ── End conditions ─────────────────────────────────────────────────────────

    @Test
    fun `an UNTIL series stops once the end date has passed`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val t = task(
            anchor = jan1, due = LocalDate.of(2026, 1, 10),
            endMode = EndMode.UNTIL, endDay = LocalDate.of(2026, 1, 10),
        )
        assertNull(nextDue(t, LocalDate.of(2026, 1, 10)))
    }

    @Test
    fun `an UNTIL series still generates while inside its window`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val t = task(
            anchor = jan1, due = LocalDate.of(2026, 1, 5),
            endMode = EndMode.UNTIL, endDay = LocalDate.of(2026, 1, 10),
        )
        assertEquals(LocalDate.of(2026, 1, 6), nextDue(t, LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `a COUNT series stops after its final row`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        // Three occurrences: seriesIndex 0, 1, 2. Resolving index 2 must end the chain.
        val t = task(anchor = jan1, endMode = EndMode.COUNT, endCount = 3, seriesIndex = 2)
        assertNull(nextDue(t, jan1.plusDays(2)))
    }

    @Test
    fun `a COUNT series finished late still produces its remaining rows`() {
        val jan1 = LocalDate.of(2026, 1, 1)
        // Daily × 3 from Jan 1, but the first one is not finished until Jan 5. Counting calendar
        // positions (Jan 1/2/3) would find nothing after Jan 5 and silently end a series the user
        // has only done one of — the count is a count of ROWS.
        val t = task(anchor = jan1, endMode = EndMode.COUNT, endCount = 3, seriesIndex = 0)
        assertEquals(LocalDate.of(2026, 1, 6), nextDue(t, LocalDate.of(2026, 1, 5)))
    }

    // ── Non-recurring ──────────────────────────────────────────────────────────

    @Test
    fun `a one-time task has no successor`() {
        val t = task(anchor = mon, freq = null)
        assertNull(nextDue(t, mon))
    }

    @Test
    fun `isRecurring reflects the frequency column`() {
        assert(TaskRecurrence.isRecurring(task(anchor = mon)))
        assert(!TaskRecurrence.isRecurring(task(anchor = mon, freq = null)))
    }

    // ── Weekday bitmask ────────────────────────────────────────────────────────

    @Test
    fun `weekday mask round-trips and ignores out-of-range days`() {
        assertEquals(listOf(1, 3, 5), TaskWeekdays.unpack(TaskWeekdays.pack(listOf(1, 3, 5))))
        assertEquals(listOf(7), TaskWeekdays.unpack(TaskWeekdays.pack(listOf(7))))
        assertEquals(emptyList<Int>(), TaskWeekdays.unpack(TaskWeekdays.pack(listOf(0, 8, -1))))
        // Null / 0 both mean "the anchor's own weekday", which the engine reads as an empty set.
        assertEquals(emptyList<Int>(), TaskWeekdays.unpack(null))
        assertEquals(emptyList<Int>(), TaskWeekdays.unpack(0))
    }
}
