package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TextObject(
    val text: String = "",
    // Embedded original strokes from lasso stroke→text conversion. Null for insert-flow objects.
    val strokes: List<LiveStroke>? = null,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        // Lenient decode (codebase convention): a future added field must degrade gracefully
        // on older builds, not make the object undecodable.
        fun fromJson(json: String): TextObject = lenientJson.decodeFromString(serializer(), json)

        private val lenientJson = Json { ignoreUnknownKeys = true }
    }
}
