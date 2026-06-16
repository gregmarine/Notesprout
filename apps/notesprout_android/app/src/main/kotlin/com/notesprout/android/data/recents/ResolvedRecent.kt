package com.notesprout.android.data.recents

/**
 * A [RecentEntry] resolved against the index into a display-ready model.
 *
 * Produced by [RecentsManager.resolve]; consumed by both recents UIs (the MainActivity recents
 * cards and the NotebookActivity recents dialog). Entries that no longer resolve — missing or
 * soft-deleted notebooks — are pruned during resolution and never appear here.
 *
 * [folderPath] is the full breadcrumb (e.g. `"Notebooks › A › B"`), matching the search/pinned
 * label convention; the immediate parent is `folderPath.substringAfterLast(" › ")`.
 */
data class ResolvedRecent(
    val notebookId: String,
    val notebookName: String,
    val folderPath: String,
    val timestamp: Long,
)
