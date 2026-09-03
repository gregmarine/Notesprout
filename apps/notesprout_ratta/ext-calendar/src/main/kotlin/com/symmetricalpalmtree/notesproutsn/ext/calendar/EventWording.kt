package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate

/**
 * How an event reads (arc 24 / Z1) — pure, shared by the list, the editor and the grid, so one
 * event can never be described two different ways on two surfaces.
 *
 * **Never a formatter.** Every string here is built from integers and the hand lists in
 * [CalendarDates] — arc 5's rule, and the calendar's own: CLDR data drifts between devices, an
 * Eastern-Arabic digit is not what a page title wants, and a time badge on a paper page is chrome,
 * not locale data. Times are 12-hour everywhere, which is what the Day page's own row labels use.
 */
object EventWording {

    /** A minute of day as a 12-hour clock time: `0` → "12:00 AM", `750` → "12:30 PM", `1439` → "11:59 PM". */
    fun minute(m: Int): String {
        val clamped = m.coerceIn(EventRules.MINUTE_RANGE)
        val hour24 = clamped / 60
        val minutes = clamped % 60
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        val suffix = if (hour24 < 12) "AM" else "PM"
        return "$hour12:${if (minutes < 10) "0$minutes" else "$minutes"} $suffix"
    }

    /** "Sep 3" — a date inside the year being looked at. */
    fun date(d: LocalDate): String = "${CalendarDates.MONTH_NAMES_SHORT[d.monthValue - 1]} ${d.dayOfMonth}"

    /** "Jan 1, 2027" — a date far enough away that the year is part of the answer. */
    fun dateWithYear(d: LocalDate): String = "${date(d)}, ${d.year}"

    /** The row's leading badge: "All day", the start time, or an em dash when there is neither. */
    fun timeBadge(e: Event): String =
        if (e.allDay) "All day" else e.startMinute?.let(::minute) ?: "—"

    /**
     * The row's meta line: what this event is, then only what distinguishes it — when it ends, the
     * span it covers if it is multi-day, how it repeats. A plain one-off appointment says just
     * "Appointment", which is the point: the line grows with the event, not with the schema.
     */
    fun meta(e: Event): String {
        val parts = ArrayList<String>(4)
        parts += e.type.label
        if (!e.allDay && e.endMinute != null) parts += "ends ${minute(e.endMinute)}"
        if (e.startDate != e.endDate) parts += span(e.startDate, e.endDate)
        e.recurrence?.let { parts += recurrenceSummary(it) }
        return parts.joinToString(" · ")
    }

    /** "Sep 3 – Sep 7", or "Dec 28, 2026 – Jan 3, 2027" when the two sides fall in different years —
     *  the year joins **both** sides then, so neither date has to be read as "the same year as the other". */
    fun span(from: LocalDate, to: LocalDate): String =
        if (from.year != to.year) "${dateWithYear(from)} – ${dateWithYear(to)}" else "${date(from)} – ${date(to)}"

    /** "Every 2 weeks on Sun, Wed · until Jan 1, 2027". */
    fun recurrenceSummary(rule: RecurrenceRule): String {
        val n = rule.interval.coerceAtLeast(1)
        val base = when (rule.freq) {
            Freq.DAILY -> if (n == 1) "Every day" else "Every $n days"
            Freq.WEEKLY -> {
                val unit = if (n == 1) "Every week" else "Every $n weeks"
                // Sun-first, the order the grid's columns and the editor's latches are in.
                val days = rule.weekdays.sortedBy { it % 7 }.joinToString(", ") { CalendarDates.DAY_NAMES[it % 7] }
                if (days.isEmpty()) unit else "$unit on $days"
            }
            Freq.MONTHLY -> if (n == 1) "Every month" else "Every $n months"
            Freq.YEARLY -> if (n == 1) "Every year" else "Every $n years"
        }
        val end = when (rule.endMode) {
            EndMode.NEVER -> null
            EndMode.UNTIL -> rule.untilDate?.let { "until ${dateWithYear(it)}" }
            EndMode.COUNT -> rule.endCount?.let { "for $it times" }
        }
        return if (end == null) base else "$base · $end"
    }

    /** The Upcoming row's countdown badge. */
    fun upcomingBadge(daysUntil: Int): String = if (daysUntil == 1) "Tomorrow" else "In $daysUntil days"

    /** The Upcoming row's meta line: what it is, the day it lands on, and the time it starts. */
    fun upcomingMeta(u: UpcomingEvent): String =
        "${u.event.type.label} · ${date(u.occurrenceStart)} · ${timeBadge(u.event)}"

    /** "1 week before" / "3 days before" — the editor's reminder chip. */
    fun reminderLabel(r: Reminder): String {
        val unit = when {
            r.unit == ReminderUnit.WEEKS && r.amount == 1 -> "week"
            r.unit == ReminderUnit.WEEKS -> "weeks"
            r.amount == 1 -> "day"
            else -> "days"
        }
        return "${r.amount} $unit before"
    }

    /** What one row of the **Day page** says: the title when it holds one event, a count when it
     *  holds more. The row already names the time, so a single event needs only its name. */
    fun dayRowLabel(count: Int, title: String): String = if (count == 1) title else "$count events"
}
