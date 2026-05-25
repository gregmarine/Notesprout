package com.notesprout.android.data

import android.graphics.PointF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The full data payload for a stroke row in the `notebook` table.
 *
 * Serialized to/from JSON and stored in [NotebookObject.data].
 * Uses [kotlinx.serialization] — code-generated, no reflection, significantly
 * faster than [org.json] for large point arrays (the previous bottleneck).
 * Wire format is identical to the original org.json output so no DB migration
 * is required.
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
 * `pressure` and `tilt` are omitted from JSON when null ([explicitNulls] = false).
 */
@Serializable
data class StrokeData(
    val color: String,
    val strokeWidth: Float,
    val points: List<StrokePoint>,
) {
    /** Serialize to JSON string for storage in the `data` column. */
    fun toJson(): String = codec.encodeToString(serializer(), this)

    /**
     * Extract (x, y) pairs for rendering.
     * Pressure and tilt are not needed by the canvas drawing path.
     */
    fun toPointFs(): List<PointF> = points.map { PointF(it.x, it.y) }

    companion object {
        /**
         * Shared Json instance.
         * - [explicitNulls] = false  → null pressure/tilt are omitted from output,
         *   matching the format written by the original org.json implementation.
         * - [ignoreUnknownKeys] = true → forward-compatible with future schema additions.
         */
        private val codec = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        /** Deserialize from a JSON string read out of the `data` column. */
        fun fromJson(json: String): StrokeData = codec.decodeFromString(json)
    }
}
