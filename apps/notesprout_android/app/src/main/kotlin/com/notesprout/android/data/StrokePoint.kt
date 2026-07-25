package com.notesprout.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single input sample within a stroke.
 *
 * [pressure] and [tilt] are nullable — hardware capture is not implemented yet.
 * They are present in the schema so future devices can populate them without a
 * data migration.  Serialized to/from JSON via [StrokeData].
 *
 * [timestamp] (JSON key `"ts"`) is **legacy** and no longer written. Per-point
 * timing was never read anywhere, and every point in a stroke was stamped with the
 * same save-time value — so it only bloated the row (~40% of stroke JSON) while a
 * stroke's real creation time already lives on its `NotebookObject.createdAt`. The
 * field is kept, nullable, purely so old rows that still carry `"ts"` deserialize;
 * it is dropped on any re-save. All of [pressure]/[tilt]/[timestamp] default to null
 * so [StrokeData]'s `explicitNulls = false` Json config omits them from output.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float? = null,
    val tilt: Float? = null,
    @SerialName("ts") val timestamp: Long? = null,
)
