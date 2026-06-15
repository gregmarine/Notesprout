package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * Render-time representation of a line object row — NOT stored in the DB.
 * Built at load time from `type = "line"` rows in the notebook table.
 *
 * [boundingBox] is the exact line extent, inflated by half the stroke width (min 4dp) on the
 * perpendicular axis so center-point containment hit tests work correctly for lasso selection.
 *
 * [@Serializable] so line data can be carried in undo/redo actions.
 * [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class LineRender(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val style: LineStyle,
    val orientation: LineOrientation,
    val strokeWidthDp: Float = 1f,
    val dotSpacingPx: Float = 0f,
)
