package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ShapeObject(
    val type: ShapeType,
    val centerX: Float,        // absolute page/canvas coords
    val centerY: Float,
    val width: Float,          // un-rotated local extents (the oriented box)
    val height: Float,
    val rotationDeg: Float = 0f,
    val strokeWidthDp: Float = 1f,
    val aspectLocked: Boolean = false,
    val pointCount: Int = 5,   // STAR only; ignored otherwise
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): ShapeObject = Json.decodeFromString(serializer(), json)
    }
}
