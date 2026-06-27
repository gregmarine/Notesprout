package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * Render-time representation of a shape object row — NOT stored in the DB.
 * Built at load time from `type = "shape"` rows in the notebook table.
 *
 * [boundingBox] is the AABB of the rotated outline, inflated by max(strokeWidthPx/2, 4dp).
 *
 * [@Serializable] so shape data can be carried in undo/redo actions and the clipboard.
 * [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class ShapeRender(
    val id: String,
    @Serializable(with = RectFSerializer::class) val boundingBox: RectF,
    val type: ShapeType,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDeg: Float,
    val strokeWidthPx: Float,   // px (strokeWidthDp × density), inherited from the drawn stroke
    val aspectLocked: Boolean,
    val pointCount: Int = 5,
) {
    companion object {
        fun from(id: String, obj: ShapeObject, density: Float): ShapeRender {
            val strokeWidthPx = obj.strokeWidthDp * density
            val inflateBy = maxOf(strokeWidthPx / 2f, 4f * density)
            // Build a preliminary render to compute the bounding box via ShapeGeometry
            val provisional = ShapeRender(
                id = id,
                boundingBox = RectF(),
                type = obj.type,
                centerX = obj.centerX,
                centerY = obj.centerY,
                width = obj.width,
                height = obj.height,
                rotationDeg = obj.rotationDeg,
                strokeWidthPx = strokeWidthPx,
                aspectLocked = obj.aspectLocked,
                pointCount = obj.pointCount,
            )
            val pathBounds = RectF()
            com.notesprout.android.notebook.ShapeGeometry.pathFor(provisional).computeBounds(pathBounds, true)
            pathBounds.inset(-inflateBy, -inflateBy)
            return provisional.copy(boundingBox = pathBounds)
        }
    }

    fun toShapeObject(density: Float): ShapeObject = ShapeObject(
        type = type,
        centerX = centerX,
        centerY = centerY,
        width = width,
        height = height,
        rotationDeg = rotationDeg,
        strokeWidthDp = strokeWidthPx / density,
        aspectLocked = aspectLocked,
        pointCount = pointCount,
    )
}
