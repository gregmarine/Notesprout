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
) {
    /** A concise human summary for a list row, e.g. "Every 2 weeks on Mon, Wed · until 1 Jan 2027". */
    fun summary(): String = RecurrenceSummary.of(this)
}

/** The `data`-column payload: everything not promoted to a queryable [EventEntity] column. */
@Serializable
data class EventPayload(
    val recurrence: RecurrenceRule? = null,
    @SerialName("notes") val notes: String = "",
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(s: String): EventPayload =
            runCatching { json.decodeFromString(serializer(), s) }.getOrDefault(EventPayload())
    }
}
