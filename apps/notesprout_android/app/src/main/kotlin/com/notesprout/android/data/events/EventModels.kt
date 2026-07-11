package com.notesprout.android.data.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Calendar **Events** — birthdays, anniversaries, vacations, meetings, appointments, and one-off
 * entries — stored in the plaintext global index (`notesprout.db`, table `events`), never in a
 * `.soil` file. Distinct from the `notebook_activity` telemetry log (which the calendar docs
 * deliberately did *not* name "events" so this word stayed free).
 *
 * These are the `@Serializable` payload models. The queryable fields (type, title, start/end day,
 * all-day, times, recurring flag) live in row columns on `EventEntity`; the [EventPayload] JSON
 * (recurrence rule + notes) rides in that entity's `data` column.
 */

/** A fixed preset kind. Drives the default recurrence offered when the user creates an event. */
@Serializable
enum class EventType(val label: String, val defaultFreq: Freq?) {
    BIRTHDAY("Birthday", Freq.YEARLY),
    ANNIVERSARY("Anniversary", Freq.YEARLY),
    VACATION("Vacation", null),
    MEETING("Meeting", null),
    APPOINTMENT("Appointment", null),
    OTHER("Event", null);

    companion object {
        /** Safe parse of a stored [name]; unknown / legacy values fall back to [OTHER]. */
        fun fromName(name: String?): EventType =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** Recurrence frequency unit. Bi-weekly is expressed as [WEEKLY] with `interval = 2`. */
@Serializable
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** For [Freq.MONTHLY]: repeat on the same calendar day, or the same ordinal weekday ("3rd Tue"). */
@Serializable
enum class MonthlyMode { DAY_OF_MONTH, ORDINAL_WEEKDAY }

/** When a recurrence stops. Flattened (no sealed hierarchy) for robust, discriminator-free JSON. */
@Serializable
enum class EndMode { NEVER, UNTIL, COUNT }

/**
 * A full RRULE-like recurrence rule anchored to the event's own start date.
 *
 * @property interval "every N" units (bi-weekly = WEEKLY, interval 2).
 * @property weekdays [Freq.WEEKLY] only: ISO days-of-week (1 = Mon … 7 = Sun). Empty = the anchor's
 *   own weekday.
 * @property monthlyMode [Freq.MONTHLY] only.
 * @property endMode / [endEpochDay] / [endCount] the stop condition (see [EndMode]).
 * @property exceptionDates occurrence-START epoch-days removed from the series ("delete this
 *   occurrence" / the date an override replaced). New field with an empty default, so pre-existing
 *   `data`-JSON rows deserialize unchanged — no DB migration.
 */
@Serializable
data class RecurrenceRule(
    val freq: Freq,
    val interval: Int = 1,
    val weekdays: List<Int> = emptyList(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY_OF_MONTH,
    val endMode: EndMode = EndMode.NEVER,
    val endEpochDay: Long? = null,
    val endCount: Int? = null,
    val exceptionDates: List<Long> = emptyList(),
) {
    /** A concise human summary for a list row, e.g. "Every 2 weeks on Mon, Wed · until 1 Jan 2027". */
    fun summary(): String = RecurrenceSummary.of(this)
}

/** Unit for a [Reminder] lead-time. Weeks are kept distinct from days purely to preserve display
 *  ("1 week" vs "7 days"); [Reminder.leadDays] collapses both to a day count for the window math. */
@Serializable
enum class ReminderUnit(val label: String, val labelPlural: String) {
    DAYS("day", "days"),
    WEEKS("week", "weeks"),
}

/**
 * A paper-like look-ahead lead-time on an event. **Not** a notification/alarm — it only controls how
 * many days ahead the event begins surfacing in the *Upcoming* section of the Events screen: the event
 * appears on every day D where `occurrence − leadDays ≤ D < occurrence`. An event may carry several.
 *
 * @property amount "N" units of lead (must be ≥ 1 to be meaningful).
 * @property unit days or weeks (display-only distinction; see [ReminderUnit]).
 */
@Serializable
data class Reminder(
    val amount: Int,
    val unit: ReminderUnit,
) {
    /** Lead time flattened to whole days (weeks × 7) — the value the window math uses. */
    val leadDays: Int get() = amount * if (unit == ReminderUnit.WEEKS) 7 else 1

    /** Concise label for an editor row, e.g. "1 week before" / "3 days before". */
    fun label(): String =
        "$amount ${if (amount == 1) unit.label else unit.labelPlural} before"
}

/** The `data`-column payload: everything not promoted to a queryable [EventEntity] column. */
@Serializable
data class EventPayload(
    val recurrence: RecurrenceRule? = null,
    @SerialName("notes") val notes: String = "",
    /** Look-ahead lead-times (see [Reminder]). New field, empty default → pre-existing `data`-JSON
     *  rows deserialize unchanged; no DB migration. */
    val reminders: List<Reminder> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): EventPayload =
            runCatching { json.decodeFromString(serializer(), s) }.getOrDefault(EventPayload())
    }
}
