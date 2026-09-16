package com.symmetricalpalmtree.notesproutsn.ext.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun `an entry re-indexed keeps its key, its pixels and its cost`() {
        // K5b: a page inserted or deleted beneath the history moves an entry's page WITHOUT touching
        // what the entry holds. The tiles are shared rather than copied on purpose — they are the
        // whole cost of an entry, and nothing about them changed.
        val tiles = listOf(tile(64, 64), tile(8, 8))
        val edit = SketchEdit.RasterChanged("p1", 3, tiles)
        val moved = edit.withIndex(4)
        assertEquals(4, moved.pageIndex)
        assertEquals("p1", moved.pageKey)
        assertEquals(edit.bytes, moved.bytes)
        assertSame(tiles, moved.tiles)
        // Re-indexing to where it already is hands back the very same entry — no allocation at all
        // for the pages an insert did not move, which is most of them.
        assertSame(edit, edit.withIndex(3))
    }

    // ── The structural entries (K5b's second half) ──────

    @Test
    fun `a structural entry holds a name and costs nothing`() {
        // It is a REFERENCE into the host's ledger, not a record: the snapshot that makes a page
        // insert or delete reversible is the notebook's and stays on the notebook's stack. Zero
        // bytes is load-bearing — the byte budget evicts the oldest entry that costs something, so
        // a page entry can never be thrown away to make room for pixels.
        val inserted = SketchEdit.PageInserted("s3", "p9", 4)
        val deleted = SketchEdit.PageDeleted("s4", "p2", 1)
        assertEquals(0L, inserted.bytes)
        assertEquals(0L, deleted.bytes)
        assertEquals("s3", inserted.token)
        assertEquals("p9", inserted.pageKey)
        assertEquals(4, inserted.pageIndex)
        assertEquals("s4", deleted.token)
        assertEquals("p2", deleted.pageKey)
        assertEquals(1, deleted.pageIndex)
    }

    @Test
    fun `a structural entry re-indexed keeps its name and its page, and its kind`() {
        // `withIndex` is abstract on SketchEdit precisely so the history's re-index can run over a
        // mixed stack without knowing what is in it. A page entry moves like any other — it names a
        // page, and a page moves when another is inserted before it.
        val inserted: SketchEdit = SketchEdit.PageInserted("s3", "p9", 4)
        val moved = inserted.withIndex(5)
        assertTrue(moved is SketchEdit.PageInserted)
        assertEquals(5, moved.pageIndex)
        assertEquals("s3", (moved as SketchEdit.PageInserted).token)
        assertEquals("p9", moved.pageKey)
        assertEquals(0L, moved.bytes)
        // Where it already is: the very same entry, no allocation — RasterChanged's rule, shared.
        assertSame(inserted, inserted.withIndex(4))
        val deleted: SketchEdit = SketchEdit.PageDeleted("s4", "p2", 1)
        assertSame(deleted, deleted.withIndex(1))
        assertEquals(0, deleted.withIndex(0).pageIndex)
    }
}
