package com.symmetricalpalmtree.notesproutsn.templates

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The stepper's arithmetic (arc 13 / G2) — pure, so the one thing a stepper can get wrong is
 * JVM-testable.
 *
 * A press moves to the **next multiple of the step**, not by the step. It matters because two
 * stock values are deliberately off-grid: the rule thickness is one mdpi pixel (0.15875 mm) and
 * the dot is four (0.635 mm), written that way so a stock generator renders bit-identically to
 * what the app drew before generators existed. Stepping *by* 0.05 from there would carry the
 * .00875 forever and show 0.21, 0.26, 0.31; stepping *to* the grid shows 0.20, 0.25, 0.30 and the
 * value the user sees is the value that is stored.
 */
object StepMath {

    /** Tolerance, in step units, for "this value is already on the grid". */
    const val EPS = 1e-3f

    /** The first multiple of [step] strictly above [v] (within a hair), clamped to [min]…[max]. */
    fun up(v: Float, step: Float, min: Float, max: Float): Float =
        if (step <= 0f) v else ((floor(v / step + EPS) + 1f) * step).coerceIn(min, max)

    /** The first multiple of [step] strictly below [v], clamped to [min]…[max]. */
    fun down(v: Float, step: Float, min: Float, max: Float): Float =
        if (step <= 0f) v else ((ceil(v / step - EPS) - 1f) * step).coerceIn(min, max)
}
