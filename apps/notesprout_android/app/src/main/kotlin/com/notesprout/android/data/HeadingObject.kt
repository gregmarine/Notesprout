package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HeadingObject(
    val strokes: List<LiveStroke>,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): HeadingObject = Json.decodeFromString(serializer(), json)
    }
}
