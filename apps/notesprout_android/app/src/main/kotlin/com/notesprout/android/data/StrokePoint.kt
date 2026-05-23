package com.notesprout.android.data

/**
 * A single input sample within a stroke.
 *
 * [pressure] and [tilt] are nullable — hardware capture is not implemented yet.
 * They are present in the schema so future devices can populate them without a
 * data migration.  Serialized to/from JSON via [StrokeData].
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float?,
    val tilt: Float?,
    val timestamp: Long,
)
