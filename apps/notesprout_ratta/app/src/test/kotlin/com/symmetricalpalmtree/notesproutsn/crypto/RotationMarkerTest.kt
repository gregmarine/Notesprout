package com.symmetricalpalmtree.notesproutsn.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rotation journal's wire form (arc 26 / U3): round-trips, tolerates growth, refuses garbage. */
class RotationMarkerTest {

    private val marker = RotationMarker(
        pendingIds = listOf("nb-1", "nb-2", "ext:com.example.pad", RotationPlan.INDEX_ID),
        newPassphrase = "NSPT-AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH",
        minted = true,
        total = 4,
        notebookCount = 2,
    )

    @Test
    fun roundTrip() {
        assertEquals(marker, RotationMarker.decode(RotationMarker.encode(marker)))
        val typed = marker.copy(newPassphrase = "my own passphrase", minted = false, quarantined = listOf("nb-9"))
        assertEquals(typed, RotationMarker.decode(RotationMarker.encode(typed)))
    }

    @Test
    fun unreadableIsNoMarker() {
        assertNull(RotationMarker.decode(null))
        assertNull(RotationMarker.decode(""))
        assertNull(RotationMarker.decode("{not json"))
        assertNull(RotationMarker.decode("""{"pendingIds":[],"newPassphrase":"","minted":false}"""))
    }

    @Test
    fun unknownKeysAndDefaultsTolerated() {
        val grown = """{"pendingIds":["a"],"newPassphrase":"p","minted":false,"future":1}"""
        val m = RotationMarker.decode(grown)!!
        assertEquals(listOf("a"), m.pendingIds)
        assertEquals(1, m.total)
        assertEquals(0, m.notebookCount)
        assertTrue(m.quarantined.isEmpty())
    }

    @Test
    fun augmentedAddsNotebooksAndReplacesStores() {
        val half = marker.without("nb-1") // nb-2, the pad store, the index remain; 1 of 4 done
        val grown = half.augmented(extraNotebooks = listOf("nb-new"), stores = listOf("com.example.pad", "com.example.cal"))
        assertEquals(
            listOf("nb-2", "nb-new", "ext:com.example.pad", "ext:com.example.cal", RotationPlan.INDEX_ID),
            grown.pendingIds,
        )
        assertEquals(6, grown.total)        // 4 + one notebook + one store
        assertEquals(3, grown.notebookCount)
        assertEquals(1, grown.completed)    // what was done stays done
        assertEquals(RotationPlan.INDEX_ID, grown.pendingIds.last())
    }

    @Test
    fun augmentedIsIdentityWhenNothingChanged() {
        assertEquals(marker, marker.augmented(emptyList(), listOf("com.example.pad")))
        // An extra notebook already pending is not added twice.
        assertEquals(marker, marker.augmented(listOf("nb-2"), listOf("com.example.pad")))
    }

    @Test
    fun augmentedDropsAStoreThatVanished() {
        // The Garden listing is the truth for stores: a store deleted between Cancel and Resume is
        // not walked (its file is gone), and the total shrinks with it so "n of t" stays honest.
        val grown = marker.augmented(emptyList(), emptyList())
        assertEquals(listOf("nb-1", "nb-2", RotationPlan.INDEX_ID), grown.pendingIds)
        assertEquals(3, grown.total)
        assertEquals(0, grown.completed)
    }

    @Test
    fun progressArithmetic() {
        assertEquals(0, marker.completed)
        val one = marker.without("nb-1")
        assertEquals(1, one.completed)
        assertEquals(4, one.total)
        val q = one.quarantine("nb-2")
        assertEquals(2, q.completed)
        assertEquals(listOf("nb-2"), q.quarantined)
        assertFalse("nb-2" in q.pendingIds)
        // Idempotent removal: a second pass over a done id changes nothing.
        assertEquals(q, q.without("nb-1"))
    }
}
