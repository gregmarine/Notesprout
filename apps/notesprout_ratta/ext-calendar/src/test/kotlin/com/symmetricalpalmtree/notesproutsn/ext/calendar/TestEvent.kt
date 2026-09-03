package com.symmetricalpalmtree.notesproutsn.ext.calendar

import java.time.LocalDate

/**
 * One event builder for every events test (arc 24 / Z1). [Event] has sixteen fields and almost
 * every test cares about two of them; spelling the other fourteen out per case would bury what each
 * test is actually pinning, and a default drifting between five copies is exactly how two tests come
 * to disagree about what an ordinary event looks like.
 */
internal fun testEvent(
    id: String = "e1",
    type: EventType = EventType.OTHER,
    title: String = "Thing",
    start: LocalDate = LocalDate.of(2026, 9, 1),
    end: LocalDate = start,
    allDay: Boolean = true,
    startMinute: Int? = null,
    endMinute: Int? = null,
    recurrence: RecurrenceRule? = null,
    exceptions: Set<LocalDate> = emptySet(),
    reminders: List<Reminder> = emptyList(),
    noteText: String = "",
    noteWidth: Float = 0f,
    noteHeight: Float = 0f,
    createdAt: Long = 1_000L,
    updatedAt: Long = 1_000L,
): Event = Event(
    id = id,
    type = type,
    title = title,
    startDate = start,
    endDate = end,
    allDay = allDay,
    startMinute = startMinute,
    endMinute = endMinute,
    recurrence = recurrence,
    exceptions = exceptions,
    reminders = reminders,
    noteText = noteText,
    noteWidth = noteWidth,
    noteHeight = noteHeight,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
