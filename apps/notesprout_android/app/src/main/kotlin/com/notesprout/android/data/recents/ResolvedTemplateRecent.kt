package com.notesprout.android.data.recents

/**
 * A [TemplateRecentEntry] resolved against the index into a display-ready model.
 *
 * Produced by [TemplateRecentsManager.resolve]; consumed by the template recents UI (Session 10).
 * Entries that no longer resolve — missing or soft-deleted templates — are pruned during resolution
 * and never appear here.
 *
 * [folderPath] is the full breadcrumb (e.g. `"Templates › A › B"`), matching the search/pinned
 * label convention; the breadcrumb root is `"Templates"`. The immediate parent is
 * `folderPath.substringAfterLast(" › ")`.
 */
data class ResolvedTemplateRecent(
    val templateId: String,
    val templateName: String,
    val folderPath: String,
    val timestamp: Long,
)
