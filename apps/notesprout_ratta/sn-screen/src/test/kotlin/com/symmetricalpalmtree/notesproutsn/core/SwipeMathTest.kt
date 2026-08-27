package com.symmetricalpalmtree.notesproutsn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one horizontal-flip rule — the notebook's page turn and every paginated list's. */
class SwipeMathTest {

    private val w = 1000f
    private val minVel = 500f

    private fun flip(dx: Float, dy: Float = 0f, vx: Float = 0f, width: Float = w) =
        SwipeMath.flip(dx, dy, vx, width, minVel)

    @Test fun `a long slow drag left is forward`() {
        assertEquals(SwipeMath.FORWARD, flip(dx = -600f))
    }

    @Test fun `a long slow drag right is back`() {
        assertEquals(SwipeMath.BACK, flip(dx = 600f))
    }

    @Test fun `a short fast fling still counts`() {
        assertEquals(SwipeMath.FORWARD, flip(dx = -350f, vx = -900f))
    }

    @Test fun `a short slow drag is nothing`() {
        assertEquals(SwipeMath.NONE, flip(dx = -350f, vx = -100f))
    }

    @Test fun `under the minimum distance is nothing however fast`() {
        assertEquals(SwipeMath.NONE, flip(dx = -299f, vx = -5000f))
    }

    @Test fun `exactly the minimum distance with enough speed qualifies`() {
        assertEquals(SwipeMath.FORWARD, flip(dx = -300f, vx = -500f))
    }

    @Test fun `a vertical-dominant drag is nothing`() {
        assertEquals(SwipeMath.NONE, flip(dx = -600f, dy = -700f, vx = -900f))
    }

    @Test fun `direction comes from displacement, not velocity`() {
        // A decelerating finger can report the opposite sign at the lift; the page must not
        // turn the wrong way.
        assertEquals(SwipeMath.FORWARD, flip(dx = -600f, vx = 900f))
        assertEquals(SwipeMath.BACK, flip(dx = 600f, vx = -900f))
    }

    @Test fun `the rule scales to the region, not the screen`() {
        // 250 px is a third of a 600-wide sidebar panel, but a quarter of the glass.
        assertEquals(SwipeMath.FORWARD, flip(dx = -250f, vx = -900f, width = 600f))
        assertEquals(SwipeMath.NONE, flip(dx = -250f, vx = -900f, width = 1000f))
    }

    @Test fun `an unmeasured region can never flip`() {
        assertEquals(SwipeMath.NONE, flip(dx = -900f, vx = -5000f, width = 0f))
    }

    @Test fun `no travel is nothing`() {
        assertEquals(SwipeMath.NONE, flip(dx = 0f, dy = 0f))
    }
}
