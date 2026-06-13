package com.notesprout.android.notebook

const val DRAG_THRESHOLD_DP = 8f

/** Grey fill drawn behind all strokes that belong to a heading object.
 *  Must be dark enough to be visible on e-ink (0xE8 washes out completely on BOOX). */
const val HEADING_BACKGROUND_COLOR = 0xFFBBBBBB.toInt()

// ── Scribble-to-Erase detection constants ────────────────────────────────────

/**
 * Minimum ratio of total path length to bounding-box diagonal for a stroke to
 * qualify as a scribble candidate. A value of 4.0 means the pen traveled at least
 * 4× the straight-line extent of the gesture, indicating a dense back-and-forth.
 */
const val SCRIBBLE_DENSITY_RATIO = 4.0f

/**
 * Minimum number of significant direction reversals (consecutive movement vectors
 * whose dot product is negative) in a scribble candidate gesture.
 */
const val SCRIBBLE_MIN_DIRECTION_REVERSALS = 3

/**
 * Minimum distance (dp) the scribble path must travel INSIDE a heading or text
 * object's bounding box to count as "touching" that object. Prevents corner-grazes
 * from triggering an accidental erase.
 */
const val SCRIBBLE_BBOX_PENETRATION_DP = 14f

/**
 * Proximity radius (dp) for the scribble-to-stroke touch test. A stroke is hit if
 * any of its points falls within this radius of any scribble path segment.
 * Chosen to be close to the eraser radius used in eraseAtPath().
 */
const val SCRIBBLE_STROKE_TOUCH_RADIUS_DP = 8f
