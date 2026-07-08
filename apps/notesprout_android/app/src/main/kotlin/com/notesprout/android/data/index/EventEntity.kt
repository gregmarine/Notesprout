package com.notesprout.android.data.index

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One calendar **Event** — birthday / anniversary / vacation / meeting / appointment / other.
 *
 * Lives in the plaintext global index (`notesprout.db`), never in a `.soil` file. Queryable fields
 * are promoted to columns so a given day's events can be found by SQL range overlap; the recurrence
 * rule + notes ride as JSON in [data] (see `EventPayload`).
 *
 * Dates are local `epochDay` (device-default zone); [endEpochDay] == [startEpochDay] for a single-day
 * event, otherwise the inclusive multi-day span. Times are minute-of-day (0–1439), null when the
 * event is [allDay] or has no time.
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["startEpochDay", "endEpochDay"]),
        Index(value = ["recurring"]),
        Index(value = ["deletedAt"]),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val allDay: Boolean,
    val startMinute: Int?,
    val endMinute: Int?,
    /** Mirrors `data.recurrence != null` — lets the DAO cheaply pull only the rows needing expansion. */
    val recurring: Boolean,
    val data: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)
