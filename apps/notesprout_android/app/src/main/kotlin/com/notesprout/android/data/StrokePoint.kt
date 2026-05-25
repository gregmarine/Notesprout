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
 * The `timestamp` field is stored under the key `"ts"` in JSON to keep row size
 * minimal.  Default values of null on [pressure] and [tilt] allow them to be
 * omitted from JSON output (governed by [StrokeData]'s Json config) and
 * silently absent during deserialization of rows that never recorded them.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float? = null,
    val tilt: Float? = null,
    @SerialName("ts") val timestamp: Long,
)
