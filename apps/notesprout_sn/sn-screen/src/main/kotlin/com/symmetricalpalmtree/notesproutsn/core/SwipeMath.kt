package com.symmetricalpalmtree.notesproutsn.core

import kotlin.math.abs

/**
 * The one horizontal-flip rule, in pure arithmetic — shared by the notebook's `PageGestures` (where
 * it decides a page flip and a two-finger insert) and by [ListSwipe] (where it decides a paginated
 * list's flip). Extracted so the hand learns **one** gesture: whatever surface is on screen, the
 * same travel across the same fraction of it means the same thing.
 *
 * Nothing here touches Android, so it is JVM-tested.
 */
object SwipeMath {

    /** Minimum horizontal travel, as a fraction of the surface's width, before anything counts. */
    const val MIN_DISTANCE_FRAC = 0.30f

    /** Travel that qualifies on its own, however slowly the finger moved. */
    const val LONG_DISTANCE_FRAC = 0.50f

    /** Fling threshold as a multiple of `ViewConfiguration.scaledMinimumFlingVelocity`. */
    const val MIN_VELOCITY_MULT = 1.0f

    /** Nothing happened. */
    const val NONE = 0

    /** Leftward travel: forward — the next page. */
    const val FORWARD = 1

    /** Rightward travel: back — the previous page. */
    const val BACK = -1

    /** Downward travel: a swipe down — the Contents, the Recents, the Bible's Contents and Recents. */
    const val DOWN = 1

    /** Upward travel: a swipe up — the notebook's trail walk-back. */
    const val UP = -1

    /**
     * [FORWARD], [BACK] or [NONE] for one completed drag.
     *
     * Horizontal-dominant, far enough, and either fast enough or simply long enough — the last
     * clause is what lets a slow, deliberate drag work on paper that hates being flung.
     *
     * **Direction comes from displacement, never velocity**: a decelerating finger can report a
     * velocity of the opposite sign at the lift, which would flip the page the wrong way.
     */
    fun flip(dx: Float, dy: Float, vx: Float, width: Float, minVelocity: Float): Int {
        if (width <= 0f) return NONE
        val absDx = abs(dx)
        if (absDx <= abs(dy)) return NONE
        if (absDx < MIN_DISTANCE_FRAC * width) return NONE
        val fast = abs(vx) >= minVelocity
        val long = absDx >= LONG_DISTANCE_FRAC * width
        if (!fast && !long) return NONE
        return if (dx < 0f) FORWARD else BACK
    }

    /**
     * [flip] rotated 90°: [DOWN], [UP] or [NONE] for one completed drag, judged against the
     * surface's **height**. Vertical-dominant, far enough, and either fast enough or simply long
     * enough — the same three constants, so the hand learns one travel for both axes. The two
     * dominance tests are mutually exclusive: at most one of [flip] and this fires for a drag.
     *
     * Written once here for the notebook's `PageGestures` (the Contents swipe-down and the trail
     * swipe-up) and for [ListSwipe]'s optional vertical callbacks (the Bible's Contents and Recents panels).
     */
    fun vertical(dx: Float, dy: Float, vy: Float, height: Float, minVelocity: Float): Int {
        if (height <= 0f) return NONE
        val absDy = abs(dy)
        if (absDy <= abs(dx)) return NONE
        if (absDy < MIN_DISTANCE_FRAC * height) return NONE
        val fast = abs(vy) >= minVelocity
        val long = absDy >= LONG_DISTANCE_FRAC * height
        if (!fast && !long) return NONE
        return if (dy > 0f) DOWN else UP
    }
}
