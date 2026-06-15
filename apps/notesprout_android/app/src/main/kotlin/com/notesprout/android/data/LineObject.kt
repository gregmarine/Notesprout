package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class LineStyle { SOLID, DASHED, DOTTED }
enum class LineOrientation { HORIZONTAL, VERTICAL }

@Serializable
data class LineObject(
    val style: LineStyle,
    val orientation: LineOrientation,
    val strokeWidthDp: Float = 1f,
    val dotSpacingDp: Float = 0f,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): LineObject = Json.decodeFromString(serializer(), json)
    }
}
