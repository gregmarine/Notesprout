package com.notesprout.android.data.recents

import kotlinx.serialization.Serializable

/**
 * One device-local "recently opened notebook" record.
 *
 * [notebookId] is the index UUID (the same id used everywhere as `EXTRA_NOTEBOOK_ID`).
 * [timestamp] is set to *now* on open and *updated* to *now* on close; the recents store is
 * ordered by [timestamp] descending (most recent first).
 *
 * Stored — never in `notesprout.db` or any `.soil` file — as a JSON-serialized
 * `List<RecentEntry>` in `SharedPreferences`. See [RecentsManager].
 */
@Serializable
data class RecentEntry(
    val notebookId: String,
    val timestamp: Long,
)
