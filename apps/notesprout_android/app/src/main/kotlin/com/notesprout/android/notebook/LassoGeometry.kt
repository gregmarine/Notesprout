package com.notesprout.android.notebook

import android.graphics.RectF
import android.graphics.Region
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Shared geometry helpers for lasso hit-testing.
 *
 * Used by both selection lasso (NotebookActivity) and lasso-eraser / smart-lasso
 * (Onyx/Generic drawing views) so the three hit-test sites cannot drift apart.
 */
object LassoGeometry {

    /**
     * True if [box] overlaps the filled lasso [region] at all — "touch" semantics, matching the
     * per-point stroke test. Used for atomic objects (headings, text, lines) so a lasso that
     * crosses any part of the object selects it, rather than requiring its exact center point.
     *
     * A line's bounding box is a thin, axis-aligned rectangle spanning its full length, so this
     * test reduces to "the lasso crosses any part of the line" for line objects.
     */
    fun regionIntersectsBox(region: Region, box: RectF): Boolean {
        val l = floor(box.left).toInt()
        val t = floor(box.top).toInt()
        // Guarantee a non-empty (≥1px) region even for hairline boxes — a Region with
        // left == right or top == bottom is empty and would never intersect.
        val r = ceil(box.right).toInt().coerceAtLeast(l + 1)
        val b = ceil(box.bottom).toInt().coerceAtLeast(t + 1)
        // Region.op returns true when the resulting region is non-empty.
        return Region(l, t, r, b).op(region, Region.Op.INTERSECT)
    }
}
