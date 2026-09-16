package com.symmetricalpalmtree.notesproutsn.ext.sketch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a page's pixels live when a save could not be delivered (arc 43 / K5). The park is the only
 * copy of an unwritten drawing, so every rule about what it keeps and what it lets go is worth a
 * laptop test.
 */
class PendingPngParkTest {

    private val park = PendingPngPark()

    private fun png(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `nothing is parked to begin with`() {
        assertFalse(park.isParked)
        assertNull(park.parkedKey)
        assertEquals(0, park.parkedBytes)
        assertNull(park.take())
    }

    @Test
    fun `a failed push is held under its own key`() {
        assertNull(park.park("p1", png(1, 2, 3)))
        assertTrue(park.isParked)
        assertEquals("p1", park.parkedKey)
        assertEquals(3, park.parkedBytes)
    }

    @Test
    fun `a newer attempt on the same page replaces the older one and displaces nothing`() {
        park.park("p1", png(1))
        assertNull("only the last snapshot of a page is worth anything", park.park("p1", png(2, 2)))
        assertEquals(2, park.parkedBytes)
    }

    @Test
    fun `a park for another page displaces the first and says so`() {
        park.park("p1", png(1))
        assertEquals("the caller logs pixels going away", "p1", park.park("p2", png(2, 2)))
        assertEquals("p2", park.parkedKey)
    }

    @Test
    fun `a landed push clears its own page's park`() {
        park.park("p1", png(1))
        park.clear("p1")
        assertFalse(park.isParked)
    }

    @Test
    fun `a landed push leaves another page's park alone`() {
        park.park("p1", png(1))
        park.clear("p2")
        assertTrue("that page is still owed a write", park.isParked)
        assertEquals("p1", park.parkedKey)
    }

    @Test
    fun `taking empties the park, under the key it was parked with`() {
        park.park("p9", png(7, 7, 7))
        val taken = park.take()!!
        assertEquals("p9", taken.pageKey)
        assertArrayEquals(png(7, 7, 7), taken.png)
        assertFalse("a park that survived its own read would be pushed twice", park.isParked)
        assertNull(park.take())
    }

    @Test
    fun `an empty image parks like any other - it is the wire form of clearing a page`() {
        park.park("p1", ByteArray(0))
        assertTrue(park.isParked)
        assertEquals(0, park.parkedBytes)
        val taken = park.take()!!
        assertEquals("p1", taken.pageKey)
        assertEquals(0, taken.png.size)
    }
}
