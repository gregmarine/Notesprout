package com.notesprout.android.data

import org.json.JSONObject

/**
 * Parsed representation of the `data` column in the notebook metadata row
 * (type = "notebook", parentId = "").
 *
 * This is NOT a Room entity — it is a plain Kotlin data class deserialized from
 * [NotebookObject.data] when the notebook metadata row is loaded.
 *
 * JSON shape stored in the `data` column:
 * ```json
 * {
 *   "title": "My Journal",
 *   "cover": "",
 *   "last_opened_page": "uuid-of-last-page"
 * }
 * ```
 *
 * [id] is the UUID of the notebook row itself; it is used as [NotebookObject.parentId]
 * for all page objects in this notebook.
 */
data class NotebookMetadata(
    /** UUID of the notebook metadata row — the parentId for all pages in this notebook. */
    val id: String,
    val title: String,
    val cover: String,
    /** UUID of the last page the user was on, or null if never navigated. */
    val lastOpenedPage: String?,
) {
    /** Serialize to the JSON string stored in [NotebookObject.data]. */
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("cover", cover)
        if (lastOpenedPage != null) put("last_opened_page", lastOpenedPage)
    }.toString()

    companion object {
        /**
         * Deserialize from the JSON string in [NotebookObject.data].
         * [rowId] is the `id` column of the notebook row (not stored inside the JSON).
         */
        fun fromJson(rowId: String, json: String): NotebookMetadata {
            val obj = JSONObject(json)
            return NotebookMetadata(
                id             = rowId,
                title          = obj.optString("title", ""),
                cover          = obj.optString("cover", ""),
                lastOpenedPage = if (obj.has("last_opened_page")) obj.getString("last_opened_page") else null,
            )
        }
    }
}
