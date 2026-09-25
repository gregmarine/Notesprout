package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rub arms on the first reversal of travel while the finger is still near where it
 * landed, delivers everything since the down as its first batch, and on nothing else: a
 * one-way drag is a swipe's business, a far-off turn-back is a bounced swipe, a second
 * finger ends it, and the gate is honoured at the turn and on every batch.
 */
class SmudgeRubTest {

    private class Spy : SmudgeRub.Listener {
        val armed = ArrayList<List<StrokePoint>>()
        val rubs = ArrayList<List<StrokePoint>>()
        var ended = 0
        override fun onArmed(points: List<StrokePoint>) { armed += points }
        override fun onRub(points: List<StrokePoint>) { rubs += points }
        override fun onEnded() { ended++ }
    }

    private fun p(x: Float, y: Float) = StrokePoint(x, y, pressure = 0.5f)

    private var gateOpen = true
    private val spy = Spy()
    private val rub = SmudgeRub(hopPx = 10f, armWithinPx = 200f, gate = { gateOpen }, listener = spy)

    /** A rub: right 60 px in 15 px steps, then back left. */
    private fun rightThenLeft() {
        rub.move(listOf(p(115f, 100f), p(130f, 100f), p(145f, 100f), p(160f, 100f)))
        rub.move(listOf(p(145f, 100f), p(130f, 100f)))
    }

    @Test
    fun `a reversal near the landing arms, delivering every sample since the down`() {
        rub.down(100f, 100f, 0L, allowed = true)
        rightThenLeft()
        assertTrue(rub.active)
        assertEquals(1, spy.armed.size)
        // The down sample, the four out, the two back — every one, in order.
        assertEquals(7, spy.armed[0].size)
        assertEquals(100f, spy.armed[0].first().x)
        assertEquals(130f, spy.armed[0].last().x)
        assertTrue(spy.rubs.isEmpty())
    }

    @Test
    fun `once armed every move is a batch and the lift ends it`() {
        rub.down(100f, 100f, 0L, allowed = true)
        rightThenLeft()
        rub.move(listOf(p(115f, 100f), p(100f, 100f)))
        rub.move(listOf(p(120f, 100f)))
        assertEquals(2, spy.rubs.size)
        rub.up()
        assertFalse(rub.active)
        assertEquals(1, spy.ended)
        // Nothing after the lift.
        rub.move(listOf(p(130f, 100f)))
        assertEquals(2, spy.rubs.size)
        assertEquals(1, spy.ended)
    }

    @Test
    fun `a one-way drag never arms`() {
        rub.down(100f, 100f, 0L, allowed = true)
        rub.move((1..40).map { p(100f + 15f * it, 100f) })
        rub.up()
        assertFalse(rub.active)
        assertTrue(spy.armed.isEmpty())
        assertEquals(0, spy.ended)
    }

    @Test
    fun `a turn-back far from the landing is a bounced swipe, not a rub`() {
        rub.down(100f, 100f, 0L, allowed = true)
        rub.move((1..20).map { p(100f, 100f + 15f * it) })   // 300 px down
        rub.move(listOf(p(100f, 385f), p(100f, 370f)))       // then back up
        assertFalse(rub.active)
        assertTrue(spy.armed.isEmpty())
        // And the sequence is dead: a later reversal nearer home does not revive it.
        rub.move(listOf(p(100f, 385f), p(100f, 370f)))
        assertFalse(rub.active)
    }

    @Test
    fun `a gentle curve is not a reversal`() {
        rub.down(100f, 100f, 0L, allowed = true)
        // A 90° turn: right, then down.
        rub.move(listOf(p(115f, 100f), p(130f, 100f), p(130f, 115f), p(130f, 130f)))
        assertFalse(rub.active)
    }

    @Test
    fun `a refused down ignores the whole sequence`() {
        rub.down(100f, 100f, 0L, allowed = false)
        rightThenLeft()
        assertFalse(rub.tracking)
        assertFalse(rub.active)
        assertTrue(spy.armed.isEmpty())
    }

    @Test
    fun `a second finger ends an armed rub and kills an unarmed one`() {
        rub.down(100f, 100f, 0L, allowed = true)
        rightThenLeft()
        assertTrue(rub.active)
        rub.secondFinger()
        assertFalse(rub.active)
        assertEquals(1, spy.ended)

        rub.down(100f, 100f, 0L, allowed = true)
        rub.move(listOf(p(115f, 100f), p(130f, 100f)))
        rub.secondFinger()
        rub.move(listOf(p(115f, 100f), p(100f, 100f)))
        assertFalse(rub.active)
        assertEquals(1, spy.armed.size)
    }

    @Test
    fun `the gate is asked at the turn and on every batch`() {
        gateOpen = false
        rub.down(100f, 100f, 0L, allowed = true)
        rightThenLeft()
        assertFalse(rub.active)
        assertTrue(spy.armed.isEmpty())

        gateOpen = true
        rub.down(100f, 100f, 0L, allowed = true)
        rightThenLeft()
        assertTrue(rub.active)
        gateOpen = false
        rub.move(listOf(p(115f, 100f)))
        assertFalse(rub.active)
        assertEquals(1, spy.ended)
        assertTrue(spy.rubs.isEmpty())
    }
}
