package com.symmetricalpalmtree.notesproutsn.ext.sketch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PushTracker] — the rule that lets a page turn stop awaiting the WebP encode (2026-09-19):
 * a *read* of a page waits for that page's own pushes, and never for another page's.
 *
 * Pure JUnit with plain [Job]s standing in for the saver's real push coroutines — no Android, no
 * bitmap, no binder, which is the whole reason this bookkeeping lives in a class of its own.
 */
class PushTrackerTest {

    /** A bound on every await here: a test that hangs is a termination bug, not a slow machine. */
    private fun await(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }

    @Test
    fun `a page with nothing in the air never waits`() {
        val tracker = PushTracker()
        assertFalse(tracker.isPending("p1"))
        await { tracker.await("p1") }
        assertEquals(0, tracker.pendingKeys())
    }

    @Test
    fun `a tracked push is pending until it finishes`() {
        val tracker = PushTracker()
        val job = Job()
        tracker.track("p1", job)
        assertTrue(tracker.isPending("p1"))
        assertEquals(1, tracker.pendingKeys())
        job.complete()
        assertFalse(tracker.isPending("p1"))
        assertEquals(0, tracker.pendingKeys())
    }

    @Test
    fun `awaiting a key waits for that key's push`() {
        val tracker = PushTracker()
        val job = Job()
        tracker.track("p1", job)
        val landed = CompletableDeferred<Boolean>()
        await {
            val waiter = launch(Dispatchers.Default) {
                tracker.await("p1")
                landed.complete(true)
            }
            assertFalse(landed.isCompleted)
            job.complete()
            waiter.join()
            assertTrue(landed.await())
        }
    }

    @Test
    fun `another page's push is never waited for`() {
        val tracker = PushTracker()
        val other = Job()
        tracker.track("p1", other)
        // p2 owes nothing: this returns although p1's push is still in the air.
        await { tracker.await("p2") }
        assertTrue(tracker.isPending("p1"))
        other.complete()
    }

    @Test
    fun `both rasters of a page are waited for, whatever order they land in`() {
        val tracker = PushTracker()
        val graphite = Job()
        val ink = Job()
        tracker.track("p1", graphite)
        tracker.track("p1", ink)
        await {
            val waiter = launch(Dispatchers.Default) { tracker.await("p1") }
            ink.complete()          // the second-tracked one first
            assertTrue(tracker.isPending("p1"))
            assertFalse(waiter.isCompleted)
            graphite.complete()
            waiter.join()
        }
        assertFalse(tracker.isPending("p1"))
    }

    @Test
    fun `awaitAll drains every page`() {
        val tracker = PushTracker()
        val first = Job()
        val second = Job()
        tracker.track("p1", first)
        tracker.track("p2", second)
        assertEquals(2, tracker.pendingKeys())
        await {
            val waiter = launch(Dispatchers.Default) { tracker.awaitAll() }
            first.complete()
            second.complete()
            waiter.join()
        }
        assertEquals(0, tracker.pendingKeys())
    }

    @Test
    fun `a push tracked after an earlier one finished does not resurrect it`() {
        val tracker = PushTracker()
        val first = Job()
        tracker.track("p1", first)
        first.complete()
        val second = Job()
        tracker.track("p1", second)
        assertEquals(1, tracker.pendingKeys())
        await {
            val waiter = launch(Dispatchers.Default) { tracker.await("p1") }
            second.complete()
            waiter.join()
        }
        assertFalse(tracker.isPending("p1"))
    }
}
