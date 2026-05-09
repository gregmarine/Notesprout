package com.notesprout.android.data

import org.json.JSONObject

data class BoundingBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    fun toJson(): String = JSONObject().apply {
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
    }.toString()

    companion object {
        fun fromJson(json: String): BoundingBox {
            val obj = JSONObject(json)
            return BoundingBox(
                x = obj.getDouble("x"),
                y = obj.getDouble("y"),
                width = obj.getDouble("width"),
                height = obj.getDouble("height")
            )
        }

        fun empty(): BoundingBox = BoundingBox(0.0, 0.0, 0.0, 0.0)
    }
}
