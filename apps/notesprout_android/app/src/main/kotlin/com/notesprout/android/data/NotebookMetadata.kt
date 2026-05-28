package com.notesprout.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

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
 * for all page objects in this notebook. It is NOT stored in the JSON — it comes from
 * the row's `id` column and is passed in via [fromJson].
 */
@Serializable
data class NotebookMetadata(
    /** UUID of the notebook metadata row — the parentId for all pages in this notebook.
     *  Not stored in the JSON; injected from the row's `id` column by [fromJson]. */
    @Transient val id: String = "",
    val title: String = "",
    val cover: String = "",
    @SerialName("last_opened_page")
    val lastOpenedPage: String? = null,
) {
    /** Serialize to the JSON string stored in [NotebookObject.data]. */
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        /**
         * - [explicitNulls] = false  → null [lastOpenedPage] is omitted from output,
         *   matching the format written by the original org.json implementation.
         * - [ignoreUnknownKeys] = true → forward-compatible with future schema additions.
         */
        private val codec = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        /**
         * Deserialize from the JSON string in [NotebookObject.data].
         * [rowId] is the `id` column of the notebook row (not stored inside the JSON).
         */
        fun fromJson(rowId: String, json: String): NotebookMetadata =
            codec.decodeFromString<NotebookMetadata>(json).copy(id = rowId)
    }
}
