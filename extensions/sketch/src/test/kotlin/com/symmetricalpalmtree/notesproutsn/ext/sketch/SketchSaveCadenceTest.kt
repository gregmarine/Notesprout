package com.symmetricalpalmtree.notesproutsn.ext.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The save deadline (arc 50, walk 4): a hand that never pauses still gets its work written. */
class SketchSaveCadenceTest {

    @Test
    fun `a fresh change waits the plain debounce`() {
        assertEquals(SketchSaveCadence.DEBOUNCE_MS, SketchSaveCadence.debounceWait(1_000L, 1_000L))
        assertEquals(SketchSaveCadence.DEBOUNCE_MS, SketchSaveCadence.debounceWait(1_000L, 5_000L))
    }

    @Test
    fun `near the deadline the wait shrinks to what is left`() {
        val since = 1_000L
        val now = since + SketchSaveCadence.MAX_DIRTY_MS - 1_000L
        assertEquals(1_000L, SketchSaveCadence.debounceWait(since, now))
    }

    @Test
    fun `past the deadline the wait is nothing and the gate is bounded`() {
        val since = 1_000L
        val now = since + SketchSaveCadence.MAX_DIRTY_MS + 400L
        assertEquals(0L, SketchSaveCadence.debounceWait(since, now))
        assertTrue(SketchSaveCadence.pastDeadline(since, now))
        assertFalse(SketchSaveCadence.pastDeadline(since, now - 1_000L))
    }

    @Test
    fun `a change every 300 ms for a minute still reaches the deadline`() {
        // The walk's shape: the debounce is restarted at every change and never elapses on its
        // own; the deadline is what fires.
        val since = 0L
        var now = 0L
        var fired = false
        while (now < 60_000L) {
            val wait = SketchSaveCadence.debounceWait(since, now)
            if (wait == 0L) { fired = true; break }
            now += 300L
        }
        assertTrue(fired)
        assertTrue(now <= SketchSaveCadence.MAX_DIRTY_MS + 300L)
    }
}
