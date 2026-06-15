package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The data payload for a `type = "cover"` row in the `notebook` table.
 *
 * Serialized to/from JSON and stored in [NotebookObject.data].
 * [image] is a base64-encoded PNG or JPEG of the notebook cover art.
 */
@Serializable
data class CoverObject(
    val image: String,
) {
    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        /** Deserialize from a `type="cover"` row's `data`; returns null on malformed JSON. */
        fun fromJson(json: String): CoverObject? =
            try { codec.decodeFromString(serializer(), json) } catch (e: Exception) { null }
    }
}
