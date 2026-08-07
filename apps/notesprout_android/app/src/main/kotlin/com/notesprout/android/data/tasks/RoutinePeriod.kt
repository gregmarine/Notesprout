package com.notesprout.android.data.tasks

import com.notesprout.android.data.events.Freq
import java.time.LocalDate

/**
 * The calendar arithmetic behind a **routine**: which period it belongs to, when that period ends,
 * and where its member tasks sit inside it.
 *
 * A routine is anchored to a whole calendar period rather than to an interval, which is what
 * separates it from a recurring task. A weekly task repeating every 7 days can fall on any weekday;
 * a weekly *routine* is "this week's" work and is always due at the week's end. So the due date is
 * derived from the frequency, never chosen:
 *
 * | Frequency | Period | Due |
 * |---|---|---|
 * | `DAILY`   | that day          | that day |
 * | `WEEKLY`  | Sunday – Saturday | the **Saturday** |
 * | `MONTHLY` | the calendar month| the **last day** |
 * | `YEARLY`  | the calendar year | **Dec 31** |
 *
 * Weeks are **Sunday-first**, matching `DayPickerDialog` and the calendar grid.
 *
 * Everything here is a pure function over epoch-days so it can be tested on the JVM without Room or
 * a device (`RoutinePeriodTest`).
 */
object RoutinePeriod {

    /** Frequencies a routine may take. A routine always recurs, so there is no "does not repeat". */
    val FREQUENCIES: List<Freq> = listOf(Freq.DAILY, Freq.WEEKLY, Freq.MONTHLY, Freq.YEARLY)

    /** Human label for the frequency spinner and the routine's meta line. */
    fun label(freq: Freq): String = when (freq) {
        Freq.DAILY -> "Daily"
        Freq.WEEKLY -> "Weekly"
        Freq.MONTHLY -> "Monthly"
        Freq.YEARLY -> "Yearly"
    }

    /** The due day (period **end**) of the period containing [day]. */
    fun dueFor(freq: Freq, day: Long): Long {
        val d = LocalDate.ofEpochDay(day)
        return when (freq) {
            Freq.DAILY -> day
            // ISO Sunday is 7; in a Sunday-first week it is offset 0, so Saturday is 6 days on.
            Freq.WEEKLY -> day + (6 - (d.dayOfWeek.value % 7))
            Freq.MONTHLY -> d.withDayOfMonth(d.lengthOfMonth()).toEpochDay()
            Freq.YEARLY -> LocalDate.of(d.year, 12, 31).toEpochDay()
        }
    }

    /** The period **start** for a period ending on [dueDay]. */
    fun startFor(freq: Freq, dueDay: Long): Long {
        val d = LocalDate.ofEpochDay(dueDay)
        return when (freq) {
            Freq.DAILY -> dueDay
            Freq.WEEKLY -> dueDay - 6
            Freq.MONTHLY -> d.withDayOfMonth(1).toEpochDay()
            Freq.YEARLY -> LocalDate.of(d.year, 1, 1).toEpochDay()
        }
    }

    /**
     * The due day of the **next** period — the smallest period end strictly after [day].
     *
     * Callers pass `max(currentDue, actionDay)`, which is what makes a routine obey the same lateness
     * rule tasks already do: finishing a weekly routine three days after its Saturday rolls to the
     * *following* Saturday, not to one that has already gone by.
     */
    fun nextDueAfter(freq: Freq, day: Long): Long {
        val due = dueFor(freq, day)
        if (due > day) return due
        // `day` is itself a period end, so step into the next period and take its end.
        return dueFor(freq, day + 1)
    }

    /** Whether [day] falls inside the period ending on [dueDay]. */
    fun periodContains(freq: Freq, dueDay: Long, day: Long): Boolean =
        day in startFor(freq, dueDay)..dueDay

    /**
     * A member's position in its period, as days from the period start — the thing that survives a
     * roll-forward so "Monday: bins" stays Monday every week.
     *
     * Clamped into the period: a member somehow dated outside it (a hand-edited row, a routine whose
     * period shrank) is pulled to the nearest end rather than carried out of bounds.
     */
    fun offsetOf(periodStart: Long, periodEnd: Long, memberDue: Long): Int =
        (memberDue.coerceIn(periodStart, periodEnd) - periodStart).toInt()

    /**
     * Apply an [offset] taken from one period to another, clamped to the new period's end.
     *
     * The clamp is what makes month-end survive February: a member on the 31st is offset 30, and a
     * 28-day February has no offset 30, so it lands on the 28th rather than spilling into March.
     */
    fun applyOffset(newStart: Long, newEnd: Long, offset: Int): Long =
        (newStart + offset).coerceAtMost(newEnd)
}
