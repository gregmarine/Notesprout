package com.symmetricalpalmtree.notesproutsn.ext.calendar

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The clock face's arithmetic (arc 24 / Z5b) — where each number sits on the dial, and which number
 * a tap landed on. Pure, so [ClockFaceView] is a canvas and a touch listener and nothing else.
 *
 * **Twelve positions, always.** Both faces have exactly twelve, which is what makes one set of
 * angles serve both: the hours are 12, 1 … 11 and the minutes are 0, 5 … 55 at
 * [TimeMath.MINUTE_STEP] apart. Five minutes is the grain a calendar entry is worth (the same call
 * the old stepper made), and a sixty-position dial is a dial nobody lands on with a pen.
 *
 * **The dead centre picks nothing.** A tap has to be out on the ring to mean a number: near the
 * middle every position is equally close, so the honest answer is null rather than whichever one the
 * angle happened to round to. The same goes for a tap well outside the dial — the dialog's own
 * padding is not part of the clock.
 *
 * Angles run **clockwise from 12 o'clock**, which is the one convention a clock has; the geometry is
 * therefore `sin` on x and `-cos` on y, not the mathematical `cos`/`sin` pair, and [hit] inverts
 * exactly that with `atan2(dx, -dy)`.
 */
object ClockFaceModel {

    /** Which of the two faces the dial is showing. */
    enum class Face { HOURS, MINUTES }

    /** How many numbers are on a face — the same twelve for both. */
    const val POSITIONS = 12

    /** The angle between two neighbouring positions, in degrees. */
    const val STEP_DEGREES = 360f / POSITIONS

    /** Where a tap stops being the ring and starts being the middle, as a fraction of the radius. */
    private const val INNER = 0.30f

    /** How far past the ring a tap still counts — a little, because a pen lands wide of a small
     *  target more often than it lands short of it. */
    private const val OUTER = 1.15f

    /** The face's twelve numbers, **clockwise from the top**: hours start at 12, minutes at 0. */
    fun values(face: Face): List<Int> = when (face) {
        Face.HOURS -> List(POSITIONS) { if (it == 0) POSITIONS else it }
        Face.MINUTES -> List(POSITIONS) { it * TimeMath.MINUTE_STEP }
    }

    /** What [value] reads as on [face]. Minutes are zero-padded — "05" is a minute, "5" is an hour. */
    fun label(face: Face, value: Int): String = when (face) {
        Face.HOURS -> value.toString()
        Face.MINUTES -> if (value < 10) "0$value" else value.toString()
    }

    /** The angle of position [index], clockwise from 12 o'clock. */
    fun angleDegrees(index: Int): Float = index * STEP_DEGREES

    /** Where position [index]'s label sits, as an offset from the dial's centre on a ring of
     *  [ringRadius]. A negative dy is **up** — screen coordinates, so the view can add these
     *  straight onto its centre. */
    fun labelOffset(index: Int, ringRadius: Float): Pair<Float, Float> {
        val radians = Math.toRadians(angleDegrees(index).toDouble())
        return (ringRadius * sin(radians)).toFloat() to (-ringRadius * cos(radians)).toFloat()
    }

    /**
     * The number under a tap at ([dx], [dy]) from the centre of a dial of [radius], or null when the
     * tap means nothing — too near the middle to name a position, or too far outside the dial to be
     * on it at all.
     */
    fun hit(face: Face, dx: Float, dy: Float, radius: Float): Int? {
        if (radius <= 0f) return null
        val distance = hypot(dx, dy)
        if (distance < INNER * radius || distance > OUTER * radius) return null
        val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        val normalized = ((degrees % 360f) + 360f) % 360f
        val index = (normalized / STEP_DEGREES).roundToInt() % POSITIONS
        return values(face)[index]
    }
}
