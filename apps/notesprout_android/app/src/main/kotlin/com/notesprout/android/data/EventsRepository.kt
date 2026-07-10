package com.notesprout.android.data

import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.EventPayload
import com.notesprout.android.data.events.EventRecurrence
import com.notesprout.android.data.index.EventDao
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.data.index.NotesproutIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * CRUD + day-scoped query layer for calendar [EventEntity] rows (plaintext global index).
 *
 * [eventsForDay] is the workhorse: non-recurring events whose span covers the day (SQL) unioned with
 * recurring events the engine says land on the day, sorted **all-day first, then timed by start
 * time** (the ordering the user asked for), title as the final tiebreak.
 */
class EventsRepository(
    private val dao: EventDao = NotesproutIndex.eventDao(),
) {

    suspend fun eventsForDay(date: LocalDate): List<EventEntity> = withContext(Dispatchers.IO) {
        val day = date.toEpochDay()
        val direct = dao.nonRecurringOnDay(day)
        val recurring = dao.allRecurring().filter { row ->
            val rule = EventPayload.fromJson(row.data).recurrence ?: return@filter false
            EventRecurrence.occursOn(rule, row.startEpochDay, row.endEpochDay, day)
        }
        (direct + recurring).sortedWith(dayOrder)
    }

    /**
     * Every day in `[startDate, endDateInclusive]` mapped to its events (direct + recurring
     * occurrences), each list sorted all-day-first then by start time. Loads the recurring set and the
     * range's non-recurring set once, then expands per day — cheap enough for a month grid (42 days).
     */
    suspend fun eventsForRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ): Map<LocalDate, List<EventEntity>> = withContext(Dispatchers.IO) {
        val startDay = startDate.toEpochDay()
        val endDay = endDateInclusive.toEpochDay()
        val direct = dao.nonRecurringInRange(startDay, endDay)
        val recurring = dao.allRecurring().map { it to (EventPayload.fromJson(it.data).recurrence) }
        val out = LinkedHashMap<LocalDate, List<EventEntity>>()
        var day = startDay
        while (day <= endDay) {
            val date = LocalDate.ofEpochDay(day)
            val onDay = direct.filter { it.startEpochDay <= day && it.endEpochDay >= day } +
                recurring.filter { (row, rule) ->
                    rule != null && EventRecurrence.occursOn(rule, row.startEpochDay, row.endEpochDay, day)
                }.map { it.first }
            if (onDay.isNotEmpty()) out[date] = onDay.sortedWith(dayOrder)
            day++
        }
        out
    }

    suspend fun get(id: String): EventEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    suspend fun save(event: EventEntity) = withContext(Dispatchers.IO) { dao.upsert(event) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    /**
     * "Delete this occurrence" of a recurring [event] as seen on [viewedDay]: add the covering
     * occurrence's start to the rule's exception list. For a non-recurring event (or if the day maps
     * to no occurrence) this falls back to a whole-event soft delete. Returns the number of remaining
     * (non-excluded) occurrences is not tracked — callers treat this as a mutate-in-place.
     */
    suspend fun deleteOccurrence(event: EventEntity, viewedDay: Long) = withContext(Dispatchers.IO) {
        val payload = EventPayload.fromJson(event.data)
        val rule = payload.recurrence
            ?: return@withContext dao.softDelete(event.id, System.currentTimeMillis())
        val occStart = EventRecurrence.occurrenceStartCovering(
            rule, event.startEpochDay, event.endEpochDay, viewedDay,
        ) ?: return@withContext
        val newRule = rule.copy(exceptionDates = (rule.exceptionDates + occStart).distinct())
        dao.upsert(
            event.copy(
                data = payload.copy(recurrence = newRule).toJson(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * "Delete this and following": end the recurring [event]'s series just before the occurrence
     * covering [viewedDay]. If that is the first occurrence (or earlier), the whole series is
     * soft-deleted instead.
     */
    suspend fun deleteThisAndFollowing(event: EventEntity, viewedDay: Long) = withContext(Dispatchers.IO) {
        val payload = EventPayload.fromJson(event.data)
        val rule = payload.recurrence
            ?: return@withContext dao.softDelete(event.id, System.currentTimeMillis())
        val occStart = EventRecurrence.occurrenceStartCovering(
            rule, event.startEpochDay, event.endEpochDay, viewedDay,
        ) ?: return@withContext
        if (occStart <= event.startEpochDay) {
            return@withContext dao.softDelete(event.id, System.currentTimeMillis())
        }
        val newRule = rule.copy(endMode = EndMode.UNTIL, endEpochDay = occStart - 1, endCount = null)
        dao.upsert(
            event.copy(
                data = payload.copy(recurrence = newRule).toJson(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * "Edit this occurrence": exception the tapped occurrence out of the recurring [original] and drop
     * a standalone one-off carrying [edited]'s fields on that occurrence's own dates. Non-date edits
     * (title/type/all-day/times) apply; the occurrence keeps its date + span (moving a single
     * occurrence's day is deliberately out of scope for now — use delete-this + add).
     */
    suspend fun editOccurrence(original: EventEntity, edited: EventEntity, viewedDay: Long) =
        withContext(Dispatchers.IO) {
            val payload = EventPayload.fromJson(original.data)
            val rule = payload.recurrence ?: return@withContext dao.upsert(edited)
            val occStart = EventRecurrence.occurrenceStartCovering(
                rule, original.startEpochDay, original.endEpochDay, viewedDay,
            ) ?: return@withContext
            val span = original.endEpochDay - original.startEpochDay
            val now = System.currentTimeMillis()
            // 1) exception the parent at the occurrence start
            val parentRule = rule.copy(exceptionDates = (rule.exceptionDates + occStart).distinct())
            dao.upsert(original.copy(data = payload.copy(recurrence = parentRule).toJson(), updatedAt = now))
            // 2) one-off override on the occurrence's own dates with the edited fields
            dao.upsert(
                edited.copy(
                    id = UUID.randomUUID().toString(),
                    startEpochDay = occStart,
                    endEpochDay = occStart + span,
                    recurring = false,
                    data = EventPayload(recurrence = null, notes = EventPayload.fromJson(edited.data).notes).toJson(),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

    /**
     * "Edit this and following": end [original] just before the tapped occurrence and start a fresh
     * series at that occurrence carrying [edited]'s fields + rule (re-anchored, no inherited
     * exceptions). Splitting at the first occurrence collapses to a whole-series edit.
     */
    suspend fun editThisAndFollowing(original: EventEntity, edited: EventEntity, viewedDay: Long) =
        withContext(Dispatchers.IO) {
            val payload = EventPayload.fromJson(original.data)
            val rule = payload.recurrence ?: return@withContext dao.upsert(edited)
            val occStart = EventRecurrence.occurrenceStartCovering(
                rule, original.startEpochDay, original.endEpochDay, viewedDay,
            ) ?: return@withContext
            val now = System.currentTimeMillis()
            if (occStart <= original.startEpochDay) {
                return@withContext editSeries(edited, original)
            }
            val span = original.endEpochDay - original.startEpochDay
            val editedPayload = EventPayload.fromJson(edited.data)
            val editedRule = editedPayload.recurrence
            // 1) truncate the original series to end before the split
            val truncated = rule.copy(endMode = EndMode.UNTIL, endEpochDay = occStart - 1, endCount = null)
            dao.upsert(original.copy(data = payload.copy(recurrence = truncated).toJson(), updatedAt = now))
            // 2) new series anchored at the occurrence with the edited fields + rule
            dao.upsert(
                edited.copy(
                    id = UUID.randomUUID().toString(),
                    startEpochDay = occStart,
                    endEpochDay = occStart + span,
                    recurring = editedRule != null,
                    data = EventPayload(
                        recurrence = editedRule?.copy(exceptionDates = emptyList()),
                        notes = editedPayload.notes,
                    ).toJson(),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

    /** "Edit all events": save the whole series (anchor preserved), carrying forward [original]'s
     *  exception dates so previously-removed occurrences stay removed. */
    suspend fun editSeries(edited: EventEntity, original: EventEntity) = withContext(Dispatchers.IO) {
        val editedPayload = EventPayload.fromJson(edited.data)
        val originalRule = EventPayload.fromJson(original.data).recurrence
        val mergedRule = editedPayload.recurrence?.copy(
            exceptionDates = originalRule?.exceptionDates ?: emptyList(),
        )
        dao.upsert(edited.copy(data = editedPayload.copy(recurrence = mergedRule).toJson()))
    }

    private companion object {
        /** All-day events first; then timed by start minute; then title (case-insensitive). */
        val dayOrder: Comparator<EventEntity> = compareByDescending<EventEntity> { it.allDay }
            .thenBy { it.startMinute ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    }
}
