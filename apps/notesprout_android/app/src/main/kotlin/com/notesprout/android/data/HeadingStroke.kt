package com.notesprout.android.data

import android.graphics.RectF

/**
 * Render-time representation of a heading row — NOT stored in the DB.
 * Built at load time from `type = "heading"` rows in the notebook table.
 *
 * The grey background is drawn using [boundingBox]; the embedded [strokes]
 * are replayed on top of it using the normal stroke rendering path.
 */
data class HeadingStroke(
    val id: String,
    val boundingBox: RectF,
    val strokes: List<LiveStroke>,
)
