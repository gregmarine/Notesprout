package com.notesprout.android.data

import com.notesprout.android.data.events.EventPayload
import com.notesprout.android.data.events.EventRecurrence
import com.notesprout.android.data.index.EventDao
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.data.index.NotesproutIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

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

    suspend fun get(id: String): EventEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    suspend fun save(event: EventEntity) = withContext(Dispatchers.IO) { dao.upsert(event) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    private companion object {
        /** All-day events first; then timed by start minute; then title (case-insensitive). */
        val dayOrder: Comparator<EventEntity> = compareByDescending<EventEntity> { it.allDay }
            .thenBy { it.startMinute ?: Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    }
}
