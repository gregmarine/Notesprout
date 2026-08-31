package com.symmetricalpalmtree.notesproutsn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded poll behind `DocumentHostHooks`' wait for a `.soil` that is still opening. A fake
 * clock advanced by the fake sleep is the whole harness — the real thing blocks, and the failures
 * worth catching (a happy path that sleeps, a wait that overshoots its deadline) are invisible on
 * device.
 */
class BoundedWaitTest {

    /** A clock that only moves when something sleeps, and a record of every sleep. */
    private class Fake {
        var now = 1_000L
        val sleeps = mutableListOf<Long>()
        val clock: () -> Long = { now }
        val sleep: (Long) -> Unit = { sleeps += it; now += it }
    }

    @Test
    fun conditionAlreadyTrueNeverSleeps() {
        val f = Fake()
        assertTrue(BoundedWait.until(8_000L, 200L, f.clock, f.sleep) { true })
        assertEquals(emptyList<Long>(), f.sleeps)
        assertEquals(1_000L, f.now)
    }

    @Test
    fun conditionThatNeverHoldsTimesOut() {
        val f = Fake()
        assertFalse(BoundedWait.until(8_000L, 200L, f.clock, f.sleep) { false })
        assertEquals(40, f.sleeps.size)
        assertEquals(8_000L, f.sleeps.sum())
    }

    @Test
    fun conditionBecomingTrueMidWaitReturnsTrue() {
        val f = Fake()
        var calls = 0
        val held = BoundedWait.until(8_000L, 200L, f.clock, f.sleep) { calls++ >= 3 }
        assertTrue(held)
        // The pre-check plus three polls: it stops the moment the condition holds.
        assertEquals(3, f.sleeps.size)
        assertEquals(600L, f.now - 1_000L)
    }

    @Test
    fun theLastSleepIsClippedToTheDeadline() {
        val f = Fake()
        assertFalse(BoundedWait.until(500L, 200L, f.clock, f.sleep) { false })
        assertEquals(listOf(200L, 200L, 100L), f.sleeps)
    }

    @Test
    fun aZeroTimeoutStillChecksOnce() {
        val f = Fake()
        var checks = 0
        assertFalse(BoundedWait.until(0L, 200L, f.clock, f.sleep) { checks++; false })
        assertEquals(1, checks)
        assertEquals(emptyList<Long>(), f.sleeps)
    }
}
