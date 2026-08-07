package com.notesprout.android.data.events

import android.content.Context
import android.text.format.DateFormat
import com.notesprout.android.data.index.EventEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * How an event row reads — its leading time badge and its meta line.
 *
 * Shared by the day window's Events list ([com.notesprout.android.EventsController]) and the Today
 * dashboard's Events section, which show the same events in the same row layout. Kept here rather
 * than in either screen so the two cannot drift into describing one event two different ways.
 *
 * Formatting only: nothing here reads or writes, and edit/delete scoping stays with the surface that
 * offers it.
 */
class EventRowFormat(context: Context) {

    private val timeFmt = DateFormat.getTimeFormat(context)
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    /** The leading badge: "All day", a start time, or an em-dash when neither is known. */
    fun time(event: EventEntity): String =
        if (event.allDay) "All day" else event.startMinute?.let(::minute) ?: "—"

    /**
     * The meta line: type, then whatever else distinguishes this event — when it ends, the span it
     * covers if it is multi-day, and how it repeats. Omits anything that doesn't apply, so a plain
     * one-off appointment says only what it is.
     */
    fun meta(event: EventEntity): String {
        val parts = mutableListOf(EventType.fromName(event.type).label)
        if (!event.allDay && event.endMinute != null) parts += "ends ${minute(event.endMinute)}"
        if (event.startEpochDay != event.endEpochDay) {
            val start = LocalDate.ofEpochDay(event.startEpochDay).format(dateFmt)
            val end = LocalDate.ofEpochDay(event.endEpochDay).format(dateFmt)
            parts += "$start – $end"
        }
        EventPayload.fromJson(event.data).recurrence?.let { parts += it.summary() }
        return parts.joinToString(" · ")
    }

    /** A minute-of-day in the device's own 12/24-hour preference. */
    fun minute(minuteOfDay: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }
        return timeFmt.format(cal.time)
    }
}
