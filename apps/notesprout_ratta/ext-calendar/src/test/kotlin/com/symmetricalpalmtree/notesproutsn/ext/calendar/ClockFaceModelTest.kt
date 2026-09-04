package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.ext.calendar.ClockFaceModel.Face
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The clock face's geometry (arc 24 / Z5b): where a number is drawn, and which number a tap on
 *  that spot names. The round trip is the whole point — a face that draws "3" at one angle and
 *  reads "4" back from it is a picker that lies. */
class ClockFaceModelTest {

    private val radius = 100f

    /** A point [fraction] of the way out along [degrees], clockwise from 12 o'clock — the same
     *  convention `labelOffset` writes and `hit` reads, expressed independently so the two are
     *  actually checked against something rather than against each other. */
    private fun point(degrees: Float, fraction: Float): Pair<Float, Float> {
        val r = radius * fraction
        val radians = Math.toRadians(degrees.toDouble())
        return (r * sin(radians)).toFloat() to (-r * cos(radians)).toFloat()
    }

    @Test
    fun everyDrawnPositionReadsBackAsTheNumberDrawnThere() {
        for (face in Face.entries) {
            val values = ClockFaceModel.values(face)
            for (index in values.indices) {
                val (dx, dy) = ClockFaceModel.labelOffset(index, radius * 0.78f)
                assertEquals(
                    "face $face index $index",
                    values[index],
                    ClockFaceModel.hit(face, dx, dy, radius),
                )
            }
        }
    }

    @Test
    fun theTopOfTheDialIsTwelveAndZero() {
        val (dx, dy) = point(0f, 0.78f)
        assertEquals(12, ClockFaceModel.hit(Face.HOURS, dx, dy, radius))
        assertEquals(0, ClockFaceModel.hit(Face.MINUTES, dx, dy, radius))
        // And the position after it is one step clockwise on both faces.
        val (nx, ny) = point(30f, 0.78f)
        assertEquals(1, ClockFaceModel.hit(Face.HOURS, nx, ny, radius))
        assertEquals(TimeMath.MINUTE_STEP, ClockFaceModel.hit(Face.MINUTES, nx, ny, radius))
    }

    @Test
    fun theSeamBetweenTwoPositionsFallsExactlyHalfway() {
        val (bx, by) = point(14.9f, 0.78f)
        assertEquals(12, ClockFaceModel.hit(Face.HOURS, bx, by, radius))
        val (ax, ay) = point(15.1f, 0.78f)
        assertEquals(1, ClockFaceModel.hit(Face.HOURS, ax, ay, radius))
    }

    @Test
    fun theDeadCentreAndTheOutsidePickNothing() {
        assertNull(ClockFaceModel.hit(Face.HOURS, 0f, 0f, radius))
        val (ix, iy) = point(75f, 0.2f)
        assertNull(ClockFaceModel.hit(Face.HOURS, ix, iy, radius))
        val (ox, oy) = point(75f, 1.4f)
        assertNull(ClockFaceModel.hit(Face.HOURS, ox, oy, radius))
        // A radius of nothing is a view that has not been laid out yet; it names nothing either.
        assertNull(ClockFaceModel.hit(Face.HOURS, 0f, -10f, 0f))
    }

    @Test
    fun onlyTheMinuteFaceZeroPadsItsLabels() {
        assertEquals("12", ClockFaceModel.label(Face.HOURS, 12))
        assertEquals("5", ClockFaceModel.label(Face.HOURS, 5))
        assertEquals("00", ClockFaceModel.label(Face.MINUTES, 0))
        assertEquals("05", ClockFaceModel.label(Face.MINUTES, 5))
        assertEquals("55", ClockFaceModel.label(Face.MINUTES, 55))
    }

    @Test
    fun bothFacesHoldTwelvePositionsClockwiseFromTheTop() {
        assertEquals(listOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), ClockFaceModel.values(Face.HOURS))
        assertEquals(
            List(TimeMath.MINUTE_POSITIONS) { it * TimeMath.MINUTE_STEP },
            ClockFaceModel.values(Face.MINUTES),
        )
        assertEquals(0f, ClockFaceModel.angleDegrees(0), 0.001f)
        assertEquals(90f, ClockFaceModel.angleDegrees(3), 0.001f)
        assertEquals(330f, ClockFaceModel.angleDegrees(11), 0.001f)
    }
}
