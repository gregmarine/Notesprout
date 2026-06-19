package com.notesprout.android.data.recents

import kotlinx.serialization.Serializable

/**
 * One device-local "recently used library template" record.
 *
 * [templateId] is the index UUID of the TemplateObject (the same id used when selecting a template
 * from the template library). [timestamp] is set to *now* when the template is used; the recents
 * store is ordered by [timestamp] descending (most recent first).
 *
 * Stored — never in `notesprout.db` or any `.soil` file — as a JSON-serialized
 * `List<TemplateRecentEntry>` in `SharedPreferences`. See [TemplateRecentsManager].
 */
@Serializable
data class TemplateRecentEntry(
    val templateId: String,
    val timestamp: Long,
)
