package com.notesprout.android.data.index

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per logged notebook activity event — powers the Day Detail "Notebooks" and "History"
 * views (notebooks Opened / Edited on a given calendar day).
 *
 * Lives in the plaintext global index (`notesprout.db`), never in a `.soil` file. Distinct from the
 * future *calendar events* system (birthdays/anniversaries/appointments) — this is app-activity
 * telemetry, hence `notebook_activity`, not `events`.
 *
 * Only [OPENED] and [EDITED] are logged here (forward-only). "Created" is derived retroactively from
 * [ObjectEntity.createdAt] and is never written as a row. See [ActivityType].
 */
@Entity(
    tableName = "notebook_activity",
    indices = [
        Index(value = ["activityType", "timestamp"]),
        Index(value = ["notebookId"]),
    ]
)
data class NotebookActivityEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val activityType: String,
    val timestamp: Long,
)

/** The kinds of activity that can be recorded in [NotebookActivityEntity]. */
object ActivityType {
    const val OPENED = "OPENED"
    const val EDITED = "EDITED"
}
