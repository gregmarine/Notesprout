package com.symmetricalpalmtree.notesproutsn.ext.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to words that could not be delivered. The mismatch rule is the one worth having a
 * test for: dropping a snapshot loses a minute of typing, and writing it to the wrong document
 * damages a file — so a park whose target is not what the host came back showing is always dropped.
 */
class PendingParkTest {

    @Test
    fun `nothing is parked to begin with`() {
        val park = PendingPark()
        assertFalse(park.isParked)
        assertNull(park.parkedKey)
        assertNull(park.take())
        assertEquals(PendingPark.Resolution.Nothing, park.resolve("page-1"))
    }

    @Test
    fun `a failed push parks its snapshot with the target it was for`() {
        val park = PendingPark()
        park.park("page-1", "unsaved words")
        assertTrue(park.isParked)
        assertEquals("page-1", park.parkedKey)
    }

    @Test
    fun `a later failure for the same target replaces the older snapshot`() {
        val park = PendingPark()
        park.park("page-1", "first")
        park.park("page-1", "second")
        assertEquals(PendingPark.Resolution.Push("page-1", "second"), park.resolve("page-1"))
    }

    @Test
    fun `a successful push of that target clears the park`() {
        val park = PendingPark()
        park.park("page-1", "unsaved")
        park.clear("page-1")
        assertFalse(park.isParked)
    }

    @Test
    fun `a successful push of another target leaves the park alone`() {
        val park = PendingPark()
        park.park("page-1", "unsaved")
        park.clear("page-2")
        assertTrue(park.isParked)
        assertEquals(PendingPark.Resolution.Push("page-1", "unsaved"), park.resolve("page-1"))
    }

    @Test
    fun `a matching key is pushed and a mismatched key is dropped, both emptying the park`() {
        val matching = PendingPark().apply { park("page-1", "words") }
        assertEquals(PendingPark.Resolution.Push("page-1", "words"), matching.resolve("page-1"))
        assertFalse(matching.isParked)

        val mismatched = PendingPark().apply { park("page-1", "words") }
        assertEquals(PendingPark.Resolution.Drop, mismatched.resolve("page-2"))
        assertFalse(mismatched.isParked)
    }

    @Test
    fun `resolve is one-shot — a park cannot be pushed twice`() {
        val park = PendingPark()
        park.park("page-1", "words")
        assertEquals(PendingPark.Resolution.Push("page-1", "words"), park.resolve("page-1"))
        assertEquals(PendingPark.Resolution.Nothing, park.resolve("page-1"))
    }

    @Test
    fun `take hands the park over and empties it — the teardown backstop's read`() {
        val park = PendingPark()
        park.park("page-1", "words")
        assertEquals("page-1" to "words", park.take())
        assertNull(park.take())
    }

    @Test
    fun `an empty document parks like any other — clearing a document is a save`() {
        val park = PendingPark()
        park.park("page-1", "")
        assertTrue(park.isParked)
        assertEquals(PendingPark.Resolution.Push("page-1", ""), park.resolve("page-1"))
    }
}
