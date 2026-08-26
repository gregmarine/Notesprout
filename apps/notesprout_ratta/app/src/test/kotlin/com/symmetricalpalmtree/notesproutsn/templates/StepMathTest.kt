package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.template.TemplateSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StepMath] — the stepper's arithmetic (arc 13 / G2). Small, and worth its own file: a stepper
 * that drifts off its grid is the one bug the user notices every single press.
 */
class StepMathTest {

    private val eps = 1e-5f

    @Test
    fun aPressMovesToTheNextMultipleNotByTheStep() {
        assertEquals(8.5f, StepMath.up(8f, 0.5f, 1f, 50f), eps)
        assertEquals(7.5f, StepMath.down(8f, 0.5f, 1f, 50f), eps)
    }

    /**
     * The stock rule thickness is one mdpi pixel — 0.15875 mm, deliberately off any round grid,
     * because that is what makes a stock render bit-identical to the old one. The first press must
     * land on the grid rather than carrying the remainder forever.
     */
    @Test
    fun anOffGridStartLandsOnTheGrid() {
        val stock = TemplateSpec.STOCK_THICKNESS_MM
        assertEquals(0.20f, StepMath.up(stock, 0.05f, 0.05f, 2f), eps)
        assertEquals(0.15f, StepMath.down(stock, 0.05f, 0.05f, 2f), eps)
        assertEquals(0.65f, StepMath.up(TemplateSpec.STOCK_DOT_MM, 0.05f, 0.1f, 3f), eps)
        assertEquals(0.60f, StepMath.down(TemplateSpec.STOCK_DOT_MM, 0.05f, 0.1f, 3f), eps)
    }

    @Test
    fun aValueAlreadyOnTheGridMovesExactlyOneStep() {
        var v = 0.2f
        repeat(5) { v = StepMath.up(v, 0.05f, 0.05f, 2f) }
        assertEquals(0.45f, v, eps)
        repeat(5) { v = StepMath.down(v, 0.05f, 0.05f, 2f) }
        assertEquals(0.20f, v, eps)
    }

    @Test
    fun aLongRunDoesNotDriftOffTheGrid() {
        // Every value is recomputed from a whole multiple, so 200 presses is not 200 additions.
        var v = 1f
        repeat(200) { v = StepMath.up(v, 0.5f, 1f, 200f) }
        assertEquals(101f, v, eps)
    }

    @Test
    fun theEndsAreClampedNotWrapped() {
        assertEquals(50f, StepMath.up(50f, 0.5f, 1f, 50f), eps)
        assertEquals(1f, StepMath.down(1f, 0.5f, 1f, 50f), eps)
        assertEquals(1f, StepMath.down(1.2f, 0.5f, 1f, 50f), eps)
    }

    @Test
    fun aZeroStepIsANoOpRatherThanADivideByZero() {
        assertEquals(4f, StepMath.up(4f, 0f, 0f, 10f), eps)
        assertEquals(4f, StepMath.down(4f, -1f, 0f, 10f), eps)
    }

    @Test
    fun integerValuedSteppersBehaveTheSameWay() {
        assertEquals(15f, StepMath.up(14f, 1f, 1f, 15f), eps)
        assertEquals(15f, StepMath.up(15f, 1f, 1f, 15f), eps)
        assertEquals(14f, StepMath.down(15f, 1f, 1f, 15f), eps)
    }
}
