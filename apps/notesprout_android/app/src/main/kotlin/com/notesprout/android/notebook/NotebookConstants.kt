package com.notesprout.android.notebook

const val DRAG_THRESHOLD_DP = 8f

// ── Shape object constants ────────────────────────────────────────────────────

/** Inner-to-outer radius ratio for STAR shape points. */
const val STAR_INNER_RATIO = 0.5f

/** Fixed icon size (dp) for a sticky note on the page. Tunable after device testing. */
const val STICKY_NOTE_ICON_SIZE_DP = 72f

// ── Snap-to-guide constants ───────────────────────────────────────────────────

/** Margin guide distance from each page edge (dp). Matches the standard toolbar button size. */
const val SNAP_MARGIN_DP = 44f

/** Maximum distance (dp) between a selection anchor and a guide for snapping to engage. */
const val SNAP_THRESHOLD_DP = 20f

// ── Scribble-to-Erase detection constants ────────────────────────────────────

/**
 * Minimum ratio of total path length to bounding-box diagonal for a stroke to
 * qualify as a scribble candidate. A value of 3.0 means the pen traveled at least
 * 3× the straight-line extent of the gesture — satisfied by a natural 3-pass scribble.
 */
const val SCRIBBLE_DENSITY_RATIO = 3.0f

/**
 * Minimum number of significant direction reversals (consecutive movement vectors
 * whose dot product is negative) in a scribble candidate gesture.
 */
const val SCRIBBLE_MIN_DIRECTION_REVERSALS = 2

/**
 * Minimum bounding-box diagonal (dp) for a stroke to qualify as a scribble candidate.
 * Prevents tiny jitter-heavy micro-strokes from accidentally satisfying the density ratio.
 */
const val SCRIBBLE_MIN_DIAGONAL_DP = 40f

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

// ── Smart Lasso detection constants ──────────────────────────────────────────

/**
 * Minimum gesture velocity (px/ms) for a stroke to qualify as a smart-lasso candidate.
 * A typical purposeful quick-select circle (~600px circumference) drawn in 1.2 s gives
 * 0.5 px/ms; casual note-taking strokes at similar lengths are usually slower.
 * Tunable — lower to accept slower circles, raise to demand faster gestures.
 */
const val SMART_LASSO_MIN_VELOCITY = 0.5f

/**
 * Maximum distance (dp) between the first and last point of a gesture for it to be
 * considered "closed" and therefore a smart-lasso candidate.
 * Deliberately larger than [SCRIBBLE_STROKE_TOUCH_RADIUS_DP] — it measures intentional
 * path closure, not eraser proximity.
 */
const val SMART_LASSO_CLOSURE_DISTANCE_DP = 50f

/**
 * Minimum total signed angular sweep (degrees) that a gesture must accumulate around
 * its own centroid to qualify as a smart-lasso candidate.
 * A full circle sweeps ±360°; requiring ≥270° accepts quick/imperfect loops while
 * rejecting letters and non-circular strokes that never wind around a central point.
 */
const val SMART_LASSO_MIN_WINDING_DEGREES = 270f
