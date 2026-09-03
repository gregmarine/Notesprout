package com.symmetricalpalmtree.notesproutsn.ext.calendar

import java.time.LocalDate

/**
 * One calendar **event** and the small types it is made of (arc 24 / Z1) — immutable, pure,
 * `java.time` only. og's model re-derived for this codebase, with the two differences that matter:
 *
 * - **there is no JSON.** og kept the rule, the reminders and the exception list in a `data` column;
 *   here every field is a row column and the three sets are their own tables (arc 22's direction).
 *   So [RecurrenceRule] does **not** carry the exception dates — they belong to the event, not to
 *   the rule, because that is the table they live in;
 * - **an event carries a note** — a page of handwriting (`note_stroke` rows, whose page size is
 *   [Event.noteWidth] × [Event.noteHeight]) and a text note ([Event.noteText]), the two behind one
 *   toggle in the editor. Neither is og's.
 *
 * Dates are [LocalDate] everywhere in Kotlin; ISO text exists only at the row
 * (`CalendarDates.format` / `CalendarDates.parse`). Minutes are minute-of-day integers, so no
 * formatter and no time zone is ever involved: an event is a thing on a calendar page, not an
 * instant.
 */

/** A fixed preset kind. [defaultFreq] is the recurrence the editor offers when the type is chosen
 *  on a NEW event — a birthday repeats yearly unless the person says otherwise. */
enum class EventType(val label: String, val defaultFreq: Freq?) {
    BIRTHDAY("Birthday", Freq.YEARLY),
    ANNIVERSARY("Anniversary", Freq.YEARLY),
    VACATION("Vacation", null),
    MEETING("Meeting", null),
    APPOINTMENT("Appointment", null),
    OTHER("Event", null),
    ;

    companion object {
        /** The type [name] stores, or **null** when the stored name is not one of these. og folded an
         *  unknown value to [OTHER]; here the row is refused instead ([EventRows.decode]) — silently
         *  relabelling a row as "Event" is a lie about data this code did not write. */
        fun fromName(name: String): EventType? = entries.firstOrNull { it.name == name }
    }
}

/** Recurrence frequency. "Every other week" is [WEEKLY] with `interval = 2`. */
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** For [Freq.MONTHLY]: the same calendar day, or the same ordinal weekday ("the 2nd Tuesday"). */
enum class MonthlyMode { DAY_OF_MONTH, ORDINAL_WEEKDAY }

/** When a series stops. Flat, because these are columns. */
enum class EndMode { NEVER, UNTIL, COUNT }

/** Days or weeks. The distinction is kept only so a reminder reads the way it was entered
 *  ("1 week", not "7 days"); [Reminder.leadDays] is what the window arithmetic uses. */
enum class ReminderUnit { DAYS, WEEKS }

/**
 * A paper-like look-ahead lead time — **not a notification**. An event with a reminder of lead L
 * appears in the Upcoming section on every day D where `occurrence − L ≤ D < occurrence`.
 */
data class Reminder(val amount: Int, val unit: ReminderUnit) {
    /** The lead flattened to whole days; weeks × 7. */
    val leadDays: Int get() = amount * if (unit == ReminderUnit.WEEKS) 7 else 1
}

/**
 * A repeat rule anchored to the event's own start date.
 *
 * @property interval every N units, 1..99 ([EventRules]).
 * @property weekdays [Freq.WEEKLY] only: ISO days (1 = Mon … 7 = Sun). Empty = the anchor's own
 *   weekday. Rendered and toggled Sun-first, but stored ISO because that is what `DayOfWeek.value`
 *   answers and one convention is enough.
 * @property untilDate / [endCount] the stop condition, each meaningful only for its [endMode].
 */
data class RecurrenceRule(
    val freq: Freq,
    val interval: Int = 1,
    val weekdays: Set<Int> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    val endMode: EndMode = EndMode.NEVER,
    val untilDate: LocalDate? = null,
    val endCount: Int? = null,
)

/**
 * One event row and its three child sets, as the screens see it.
 *
 * [startDate]..[endDate] is an **inclusive** span (`endDate >= startDate`, which [EventRules]
 * enforces), and every occurrence of a recurring event preserves its length: an occurrence
 * beginning on `O` covers `[O, O + spanDays]`.
 *
 * [exceptions] are occurrence **starts** removed from the series — what "delete this occurrence"
 * writes, and what an edited occurrence's override replaces. They live on the event rather than
 * inside [recurrence] because they are their own table.
 */
data class Event(
    val id: String,
    val type: EventType,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val allDay: Boolean,
    val startMinute: Int?,
    val endMinute: Int?,
    val recurrence: RecurrenceRule?,
    val exceptions: Set<LocalDate>,
    val reminders: List<Reminder>,
    val noteText: String,
    val noteWidth: Float,
    val noteHeight: Float,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** The stored `recurring` column is this — a mirror of `freq IS NOT NULL`, so the expansion
     *  read is an index hit. [EventRows] refuses a row where the two disagree. */
    val recurring: Boolean get() = recurrence != null

    /** The span's length in days: 0 for a single-day event. A `val`, not a computed `get()`, would
     *  be the rule if a sort ordered by it; nothing does, and this is two subtractions. */
    val spanDays: Long get() = endDate.toEpochDay() - startDate.toEpochDay()
}

/** One Upcoming row: the event, the start of the occurrence being looked ahead to, and how many
 *  days away that start is (always ≥ 1 — an occurrence today is not upcoming, it is today). */
data class UpcomingEvent(val event: Event, val occurrenceStart: LocalDate, val daysUntil: Int)

/** Which occurrences an edit or a delete of a recurring event applies to. */
enum class Scope { THIS, FOLLOWING, ALL }
