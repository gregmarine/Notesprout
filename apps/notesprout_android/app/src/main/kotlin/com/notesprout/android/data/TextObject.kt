package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TextObject(
    val text: String = "",
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): TextObject = Json.decodeFromString(serializer(), json)
    }
}
