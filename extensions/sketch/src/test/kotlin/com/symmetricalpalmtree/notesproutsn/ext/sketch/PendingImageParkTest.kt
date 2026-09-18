package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a page's pixels live when a save could not be delivered (arc 43 / K5; **one slot per raster
 * since arc 45 "Ink" / G3**). The park is the only copy of an unwritten drawing, so every rule about
 * what it keeps and what it lets go is worth a laptop test — and now every one of them has to hold
 * *per raster*, because graphite and ink are two rows owed separately and a rule that leaked across
 * them would silently throw away half a page.
 */
class PendingImageParkTest {

    private val park = PendingImagePark()

    private val graphite = RasterLayer.GRAPHITE
    private val ink = RasterLayer.INK

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `nothing is parked to begin with`() {
        assertFalse(park.isParked)
        assertNull(park.parkedKey(graphite))
        assertNull(park.parkedKey(ink))
        assertEquals(0, park.parkedBytes(graphite))
        assertEquals(0, park.parkedBytes(ink))
        assertNull(park.take())
    }

    @Test
    fun `a failed push is held under its own key and its own raster`() {
        assertNull(park.park("p1", ink, bytes(1, 2, 3)))
        assertTrue(park.isParked)
        assertEquals("p1", park.parkedKey(ink))
        assertEquals(3, park.parkedBytes(ink))
        assertNull("the other raster owes nothing", park.parkedKey(graphite))
        assertEquals(0, park.parkedBytes(graphite))
    }

    @Test
    fun `both rasters of one page can be owed at once`() {
        // The case the single slot could not hold: a host that died between the two pushes of one
        // debounced save owes the page's graphite AND its ink, and neither may displace the other.
        assertNull(park.park("p1", graphite, bytes(1)))
        assertNull("a different raster displaces nothing", park.park("p1", ink, bytes(2, 2)))
        assertEquals("p1", park.parkedKey(graphite))
        assertEquals("p1", park.parkedKey(ink))
        assertEquals(1, park.parkedBytes(graphite))
        assertEquals(2, park.parkedBytes(ink))
    }

    @Test
    fun `a newer attempt on the same page and raster replaces the older one and displaces nothing`() {
        park.park("p1", graphite, bytes(1))
        assertNull("only the last snapshot of a raster is worth anything", park.park("p1", graphite, bytes(2, 2)))
        assertEquals(2, park.parkedBytes(graphite))
    }

    @Test
    fun `a park for another page displaces the first on that raster and says so`() {
        park.park("p1", graphite, bytes(1))
        assertEquals("the caller logs pixels going away", "p1", park.park("p2", graphite, bytes(2, 2)))
        assertEquals("p2", park.parkedKey(graphite))
    }

    @Test
    fun `displacement is per raster - a graphite park for another page leaves an ink park alone`() {
        park.park("p1", graphite, bytes(1))
        park.park("p1", ink, bytes(9, 9, 9))
        assertEquals("p1", park.park("p2", graphite, bytes(2, 2)))
        assertEquals("p2", park.parkedKey(graphite))
        assertEquals("page 1's ink is still owed a write", "p1", park.parkedKey(ink))
        assertEquals(3, park.parkedBytes(ink))
    }

    @Test
    fun `a landed push clears its own page's park on its own raster`() {
        park.park("p1", graphite, bytes(1))
        park.clear("p1", graphite)
        assertFalse(park.isParked)
    }

    @Test
    fun `a landed push on one raster leaves the other raster's park alone`() {
        park.park("p1", graphite, bytes(1))
        park.park("p1", ink, bytes(2))
        park.clear("p1", graphite)
        assertTrue(park.isParked)
        assertNull(park.parkedKey(graphite))
        assertEquals("the same page's other row is still owed a write", "p1", park.parkedKey(ink))
    }

    @Test
    fun `a landed push leaves another page's park alone`() {
        park.park("p1", graphite, bytes(1))
        park.clear("p2", graphite)
        assertTrue("that page is still owed a write", park.isParked)
        assertEquals("p1", park.parkedKey(graphite))
    }

    @Test
    fun `taking empties that slot, under the key and the raster it was parked with`() {
        park.park("p9", ink, bytes(7, 7, 7))
        val taken = park.take()!!
        assertEquals("p9", taken.pageKey)
        assertEquals(ink, taken.layer)
        assertArrayEquals(bytes(7, 7, 7), taken.bytes)
        assertFalse("a park that survived its own read would be pushed twice", park.isParked)
        assertNull(park.take())
    }

    @Test
    fun `a drain takes graphite first, then ink, then answers null`() {
        // Every caller drains rather than taking one — the order is SketchLayers.all's, so a
        // re-push always offers the two rasters the same way round.
        park.park("p1", ink, bytes(2, 2))
        park.park("p1", graphite, bytes(1))
        val first = park.take()!!
        assertEquals(graphite, first.layer)
        assertEquals(1, first.bytes.size)
        val second = park.take()!!
        assertEquals(ink, second.layer)
        assertEquals(2, second.bytes.size)
        assertNull("the drain has to end", park.take())
        assertFalse(park.isParked)
    }

    @Test
    fun `an empty image parks like any other - it is the wire form of clearing a raster`() {
        park.park("p1", graphite, ByteArray(0))
        assertTrue(park.isParked)
        assertEquals(0, park.parkedBytes(graphite))
        val taken = park.take()!!
        assertEquals("p1", taken.pageKey)
        assertEquals(graphite, taken.layer)
        assertEquals(0, taken.bytes.size)
    }
}
