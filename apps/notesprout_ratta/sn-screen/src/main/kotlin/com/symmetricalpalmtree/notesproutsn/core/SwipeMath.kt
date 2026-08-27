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
}
