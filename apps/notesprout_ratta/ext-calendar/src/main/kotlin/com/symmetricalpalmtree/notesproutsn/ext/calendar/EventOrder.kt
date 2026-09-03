package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * The two orders an event list is ever in (arc 24 / Z1) — one copy, so the day list, the Upcoming
 * section and the grid's marks cannot drift into sorting the same events three ways.
 *
 * Both are total and stable: every tiebreak ends at the title, compared case-insensitively so two
 * rows never swap places because one was typed in capitals.
 */
object EventOrder {

    /** A day's own events: **all-day first**, then by start minute (a timeless event last), then
     *  title. The order the wizard asked for, and the order the Day page's rows are filled in. */
    val DAY: Comparator<Event> = compareByDescending<Event> { it.allDay }
        .thenBy { it.startMinute ?: Int.MAX_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

    /** The Upcoming look-ahead: **nearest first**, then all-day, then title. */
    val UPCOMING: Comparator<UpcomingEvent> = compareBy<UpcomingEvent> { it.daysUntil }
        .thenByDescending { it.event.allDay }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.event.title }
}
