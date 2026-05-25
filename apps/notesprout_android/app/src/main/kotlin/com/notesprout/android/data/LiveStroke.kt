package com.notesprout.android.data

import android.graphics.PointF
import android.graphics.RectF

/**
 * An in-memory stroke with a stable UUID.
 *
 * The [id] matches the `NotebookObject.id` of the corresponding row in the
 * `notebook` table, enabling incremental saves (INSERT OR IGNORE) and targeted
 * soft-deletes when a stroke is erased without a full page re-write.
 *
 * UUID is assigned at stroke creation time inside the drawing view.
 *
 * [boundingBox] is pre-computed at construction time for O(1) AABB pre-filtering
 * during eraser hit tests — avoids the full per-point geometry check on strokes
 * that are nowhere near the eraser position.
 */
data class LiveStroke(
    /** UUID matching the notebook table row for this stroke. */
    val id: String,

    /** Ordered (x, y) points in drawing-view coordinates. */
    val points: List<PointF>,
) {
    /**
     * Axis-aligned bounding box of all stroke points, computed once at construction.
     * Used as a fast rejection test in eraseAtPath() before the expensive per-point check.
     */
    val boundingBox: RectF = if (points.isEmpty()) RectF() else run {
        var minX = points[0].x; var minY = points[0].y
        var maxX = minX;        var maxY = minY
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.x < minX) minX = p.x else if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y else if (p.y > maxY) maxY = p.y
        }
        RectF(minX, minY, maxX, maxY)
    }
}
