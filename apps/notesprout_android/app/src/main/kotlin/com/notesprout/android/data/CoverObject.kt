package com.notesprout.android.data

import kotlinx.serialization.Serializable

/**
 * The data payload for a `type = "cover"` row in the `notebook` table.
 *
 * Serialized to/from JSON and stored in [NotebookObject.data].
 * [image] is a base64-encoded PNG or JPEG of the notebook cover art.
 */
@Serializable
data class CoverObject(
    val image: String,
)
