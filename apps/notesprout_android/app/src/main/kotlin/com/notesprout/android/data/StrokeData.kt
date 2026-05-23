package com.notesprout.android.data

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

/**
 * The full data payload for a stroke row in the `notebook` table.
 *
 * Serialized to/from JSON and stored in [NotebookObject.data].
 * Uses Android's built-in [org.json] — no additional dependency required.
 *
 * JSON shape:
 * ```json
 * {
 *   "color": "#000000",
 *   "strokeWidth": 3.0,
 *   "points": [
 *     { "x": 100.0, "y": 200.0, "ts": 1716000000000 },
 *     { "x": 110.0, "y": 205.0, "pressure": 0.8, "tilt": 0.1, "ts": 1716000000016 }
 *   ]
 * }
 * ```
 * `pressure` and `tilt` are omitted from JSON when null to keep row size minimal.
 */
data class StrokeData(
    val color: String,
    val strokeWidth: Float,
    val points: List<StrokePoint>,
) {
    /** Serialize to JSON string for storage in the `data` column. */
    fun toJson(): String {
        val pointsArray = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("x", p.x.toDouble())
            obj.put("y", p.y.toDouble())
            if (p.pressure != null) obj.put("pressure", p.pressure.toDouble())
            if (p.tilt != null) obj.put("tilt", p.tilt.toDouble())
            obj.put("ts", p.timestamp)
            pointsArray.put(obj)
        }
        return JSONObject().apply {
            put("color", color)
            put("strokeWidth", strokeWidth.toDouble())
            put("points", pointsArray)
        }.toString()
    }

    /**
     * Extract (x, y) pairs for rendering.
     * Pressure and tilt are not needed by the canvas drawing path.
     */
    fun toPointFs(): List<PointF> = points.map { PointF(it.x, it.y) }

    companion object {
        /** Deserialize from a JSON string read out of the `data` column. */
        fun fromJson(json: String): StrokeData {
            val obj = JSONObject(json)
            val color = obj.getString("color")
            val strokeWidth = obj.getDouble("strokeWidth").toFloat()
            val pointsArray = obj.getJSONArray("points")
            val points = (0 until pointsArray.length()).map { i ->
                val p = pointsArray.getJSONObject(i)
                StrokePoint(
                    x         = p.getDouble("x").toFloat(),
                    y         = p.getDouble("y").toFloat(),
                    pressure  = if (p.has("pressure")) p.getDouble("pressure").toFloat() else null,
                    tilt      = if (p.has("tilt")) p.getDouble("tilt").toFloat() else null,
                    timestamp = p.getLong("ts"),
                )
            }
            return StrokeData(color, strokeWidth, points)
        }
    }
}
