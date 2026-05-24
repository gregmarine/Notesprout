package com.notesprout.android.data

import android.graphics.PointF

/**
 * An in-memory stroke with a stable UUID.
 *
 * The [id] matches the `NotebookObject.id` of the corresponding row in the
 * `notebook` table, enabling incremental saves (INSERT OR IGNORE) and targeted
 * soft-deletes when a stroke is erased without a full page re-write.
 *
 * UUID is assigned at stroke creation time inside the drawing view.
 */
data class LiveStroke(
    /** UUID matching the notebook table row for this stroke. */
    val id: String,

    /** Ordered (x, y) points in drawing-view coordinates. */
    val points: List<PointF>,
)
