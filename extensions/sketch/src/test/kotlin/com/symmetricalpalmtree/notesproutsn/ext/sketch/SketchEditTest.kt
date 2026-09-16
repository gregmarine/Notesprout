package com.symmetricalpalmtree.notesproutsn.ext.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one history entry costs (arc 43 / K5). The number matters because it is the only thing
 * standing between a sitting of sketching and a dead process: `UndoRedoStack`'s byte budget evicts
 * against it, and an entry that under-reported its size would let the history grow past the budget
 * without the stack ever noticing.
 */
class SketchEditTest {

    private fun tile(w: Int, h: Int) = RasterTile(0, 0, w, h, IntArray(w * h))

    @Test
    fun `an entry costs four bytes a pixel, summed over its tiles`() {
        val edit = SketchEdit.RasterChanged("p1", 0, listOf(tile(64, 64), tile(64, 64), tile(10, 4)))
        assertEquals((64 * 64 + 64 * 64 + 10 * 4) * 4L, edit.bytes)
    }

    @Test
    fun `an entry with no tiles costs nothing`() {
        assertEquals(0L, SketchEdit.RasterChanged("p1", 0, emptyList()).bytes)
    }

    @Test
    fun `one tile's cost is its own pixels`() {
        assertEquals(64L * 64L * 4L, tile(64, 64).bytes)
        assertEquals(0L, tile(0, 0).bytes)
    }

    @Test
    fun `the budget holds several page-wide entries on a real page`() {
        // The Nomad's page is 1404 x 1685: about 9.5 MB as ARGB. A budget that could not hold a few
        // of those would throw away the undo of a page-wide rub the moment it was made.
        val page = 1404L * 1685L * 4L
        assertTrue("the budget must hold more than one page-wide entry", SketchEdit.UNDO_BUDGET_BYTES > page * 4)
    }
}
