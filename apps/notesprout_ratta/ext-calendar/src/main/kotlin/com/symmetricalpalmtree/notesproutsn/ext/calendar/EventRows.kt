package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.Row
import java.time.LocalDate

/**
 * One `event` row (and its three child sets) → one [Event] (arc 24 / Z1) — pure, JVM-tested.
 *
 * **`StrokeRows`' rule, applied to a wider row: a bad row is a dropped event, never a lost day.**
 * The store counts what it dropped and logs the count; nothing throws, because a single unreadable
 * row must not take the day's other events with it. What counts as unreadable:
 *
 * - an enum name this build does not know (a type, a freq, a monthly mode, an end mode, a reminder
 *   unit). og folded an unknown type to "Event"; relabelling a row this code did not write is a
 *   lie about data, so the row is dropped instead;
 * - a date that is not ISO `yyyy-MM-dd`, or an `endDate` before its `startDate` — the span is the
 *   one invariant every query depends on;
 * - **`recurring` disagreeing with `freq IS NOT NULL`.** The column is a stored mirror kept so the
 *   expansion read is an index hit, which means a row where the two differ is invisible to exactly
 *   one of the two reads — the mirror is load-bearing, so a broken one is a broken row.
 *
 * A NULL `freq` means no recurrence, and the weekdays and exceptions handed in are then ignored:
 * a one-off has none by construction, and honouring stray child rows would resurrect a rule the
 * event no longer has.
 */
object EventRows {

    /** Columns: [EventSql.COLUMNS]. Null = drop this row. */
    fun decode(row: Row, weekdays: Set<Int>, exceptions: Set<LocalDate>, reminders: List<Reminder>): Event? = try {
        val type = EventType.fromName(row.text("type"))
        val startDate = CalendarDates.parse(row.text("startDate"))
        val endDate = CalendarDates.parse(row.text("endDate"))
        val freqName = row.textOrNull("freq")
        val freq = freqName?.let { name -> Freq.entries.firstOrNull { it.name == name } }
        val monthlyMode = MonthlyMode.entries.firstOrNull { it.name == row.text("monthlyMode") }
        val endMode = EndMode.entries.firstOrNull { it.name == row.text("endMode") }
        val untilText = row.textOrNull("untilDate")
        val untilDate = untilText?.let { CalendarDates.parse(it) }
        val recurring = row.long("recurring") != 0L

        when {
            type == null || monthlyMode == null || endMode == null -> null
            startDate == null || endDate == null || endDate.isBefore(startDate) -> null
            untilText != null && untilDate == null -> null
            freqName != null && freq == null -> null
            recurring != (freq != null) -> null
            else -> Event(
                id = row.text("id"),
                type = type,
                title = row.text("title"),
                startDate = startDate,
                endDate = endDate,
                allDay = row.long("allDay") != 0L,
                startMinute = row.longOrNull("startMinute")?.toInt(),
                endMinute = row.longOrNull("endMinute")?.toInt(),
                recurrence = freq?.let {
                    RecurrenceRule(
                        freq = it,
                        interval = row.long("interval").toInt(),
                        weekdays = weekdays,
                        monthlyMode = monthlyMode,
                        endMode = endMode,
                        untilDate = untilDate,
                        endCount = row.longOrNull("endCount")?.toInt(),
                    )
                },
                exceptions = if (freq == null) emptySet() else exceptions,
                reminders = reminders,
                noteText = row.text("noteText"),
                noteWidth = row.real("noteWidth").toFloat(),
                noteHeight = row.real("noteHeight").toFloat(),
                createdAt = row.long("createdAt"),
                updatedAt = row.long("updatedAt"),
            )
        }
    } catch (e: Exception) {
        null
    }

    /** One `event_weekday` row's ISO weekday, or null when the cell is not one. */
    fun weekday(row: Row): Int? = try {
        row.long("weekday").toInt().takeIf { it in 1..7 }
    } catch (e: Exception) {
        null
    }

    /** One `event_exception` row's date. */
    fun exceptionDate(row: Row): LocalDate? = try {
        CalendarDates.parse(row.text("date"))
    } catch (e: Exception) {
        null
    }

    /** One `event_reminder` row. An amount below 1 is no lead at all, so the row is dropped. */
    fun reminder(row: Row): Reminder? = try {
        val amount = row.long("amount").toInt()
        val unit = ReminderUnit.entries.firstOrNull { it.name == row.text("unit") }
        if (unit == null || amount < 1) null else Reminder(amount, unit)
    } catch (e: Exception) {
        null
    }
}
