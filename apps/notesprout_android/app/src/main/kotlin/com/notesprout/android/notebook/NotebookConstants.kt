package com.notesprout.android.notebook

const val DRAG_THRESHOLD_DP = 8f

// ── Shape object constants ────────────────────────────────────────────────────

/** Inner-to-outer radius ratio for STAR shape points. */
const val STAR_INNER_RATIO = 0.5f

// ── Shape recognizer constants ────────────────────────────────────────────────

/** Minimum stroke diagonal (dp) for a stroke to qualify for shape recognition. */
const val SHAPE_MIN_DIAGONAL_DP = 24f

/** Max first-to-last / diagonal ratio for a stroke to be considered closed. */
const val SHAPE_CLOSURE_RATIO = 0.35f

/** Max perpendicular deviation / length ratio for a straight line classification. */
const val SHAPE_LINE_STRAIGHTNESS = 0.10f

/**
 * Max *average* (across polygon sides) of per-side max-deviation/length for the
 * sidesAreStraight polygon gate. Averaging tolerates one wobbly side without failing
 * the whole polygon. Circle arcs average 0.134 (6-corner) to 0.207 (4-corner); a
 * hexagon with one 0.20-ratio side and five 0.04-ratio sides averages ~0.07.
 */
const val SHAPE_SIDE_STRAIGHTNESS_AVG = 0.12f

/** Ramer–Douglas–Peucker epsilon as a fraction of the stroke diagonal. */
const val SHAPE_RDP_EPS_RATIO = 0.08f

/** Max radial coefficient-of-variation to classify a closed shape as an ellipse. */
const val SHAPE_ELLIPSE_CV = 0.18f

/**
 * Max mean normalized ellipse-fit error for the fit-quality gate.
 * When radial CV is high (elongated ovals have inherently high CV from center),
 * the recognizer falls back to checking how well the points actually fit an ellipse.
 * A perfect ellipse scores 0; hand-drawn ovals typically score < 0.08;
 * true polygons with flat sides score > 0.12.
 */
const val SHAPE_ELLIPSE_FIT_THRESHOLD = 0.08f

/** Max (|w−h|/max(w,h)) ratio for snapping to a square/circle/regular polygon. */
const val SHAPE_SQUARE_SNAP = 0.15f

/** Orientation angle (°) within which a shape is snapped upright (always 0 in v1). */
const val SHAPE_UPRIGHT_SNAP_DEG = 8f

/** Minimum recognizer confidence score [0,1] required to emit a non-null result. */
const val SHAPE_MIN_CONFIDENCE = 0.55f

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

// ── Shape transform constants ─────────────────────────────────────────────────

/** Visual size (dp) of each resize handle square. */
const val SHAPE_HANDLE_SIZE_DP = 10f

/** Touch hit radius (dp) for handle + rotate-knob hit tests. */
const val SHAPE_HANDLE_TOUCH_DP = 22f

/** Distance (dp) the rotate knob sits above the top-center resize handle. */
const val SHAPE_ROTATE_OFFSET_DP = 36f

/** Within this many degrees of 0/90/180/270 the rotation snaps to the axis. */
const val SHAPE_ROTATE_SNAP_DEG = 5f

/** Minimum width or height (dp) a shape can be resized to. */
const val SHAPE_MIN_SIZE_DP = 24f

// ── Shape dwell-trigger constants ────────────────────────────────────────────

/** Minimum dwell time (ms) at pen lift to trigger shape recognition. */
const val SHAPE_DWELL_MS = 700L

/** Radius (dp) within which stylus jitter is ignored when tracking dwell stillness. */
const val SHAPE_DWELL_RADIUS_DP = 6f

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
