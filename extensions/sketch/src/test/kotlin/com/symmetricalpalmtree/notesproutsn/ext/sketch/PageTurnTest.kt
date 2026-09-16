package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-turn arithmetic (arc 43 / K5), including the one rule the seam is built on: **at an edge
 * the host answers the same page**, so the screen compares keys and stays put. A screen that read an
 * edge as a failure would show a dialog for a tap that means "there is nothing that way".
 */
class PageTurnTest {

    @Test
    fun `only the contract's two directions are directions`() {
        assertTrue(PageTurn.isDirection(SketchContract.PAGE_PREV))
        assertTrue(PageTurn.isDirection(SketchContract.PAGE_NEXT))
        assertFalse(PageTurn.isDirection(0))
        assertFalse(PageTurn.isDirection(99))
    }

    @Test
    fun `a turn in the middle of a notebook lands one page along`() {
        assertEquals(3, PageTurn.targetIndex(2, 10, SketchContract.PAGE_NEXT))
        assertEquals(1, PageTurn.targetIndex(2, 10, SketchContract.PAGE_PREV))
        assertTrue(PageTurn.canTurn(2, 10, SketchContract.PAGE_NEXT))
    }

    @Test
    fun `an edge has nowhere to go`() {
        assertNull(PageTurn.targetIndex(0, 10, SketchContract.PAGE_PREV))
        assertNull(PageTurn.targetIndex(9, 10, SketchContract.PAGE_NEXT))
        assertFalse(PageTurn.canTurn(0, 10, SketchContract.PAGE_PREV))
        assertFalse(PageTurn.canTurn(9, 10, SketchContract.PAGE_NEXT))
    }

    @Test
    fun `a one-page notebook cannot turn either way`() {
        assertFalse(PageTurn.canTurn(0, 1, SketchContract.PAGE_PREV))
        assertFalse(PageTurn.canTurn(0, 1, SketchContract.PAGE_NEXT))
    }

    @Test
    fun `nonsense in is null out rather than an index off the end`() {
        assertNull(PageTurn.targetIndex(5, 3, SketchContract.PAGE_NEXT))
        assertNull(PageTurn.targetIndex(-1, 3, SketchContract.PAGE_NEXT))
        assertNull(PageTurn.targetIndex(0, 0, SketchContract.PAGE_NEXT))
        assertNull(PageTurn.targetIndex(0, 3, 42))
    }

    @Test
    fun `the same key back is the edge, and nothing else is`() {
        assertTrue(PageTurn.isEdge("page-a", "page-a"))
        assertFalse(PageTurn.isEdge("page-a", "page-b"))
    }

    @Test
    fun `a replay walks towards the page its entry names`() {
        assertEquals(SketchContract.PAGE_PREV, PageTurn.directionTowards(5, 2))
        assertEquals(SketchContract.PAGE_NEXT, PageTurn.directionTowards(2, 5))
        assertNull("already there", PageTurn.directionTowards(3, 3))
    }

    @Test
    fun `the walk is bounded by the distance the entry recorded, plus slack`() {
        assertEquals(4, PageTurn.maxSteps(5, 2))
        assertEquals(4, PageTurn.maxSteps(2, 5))
        assertEquals("a bound of zero would never move at all", 1, PageTurn.maxSteps(3, 3))
    }

    // ── Re-indexing after an insert or a delete (K5b) ──────

    @Test
    fun `an insert pushes the pages at and after it one along`() {
        // A page inserted at 2: the entries for pages 0 and 1 have not moved; 2 is now 3, 3 is 4.
        assertEquals(0, PageTurn.reindexAfterInsert(0, 2))
        assertEquals(1, PageTurn.reindexAfterInsert(1, 2))
        assertEquals(3, PageTurn.reindexAfterInsert(2, 2))
        assertEquals(4, PageTurn.reindexAfterInsert(3, 2))
    }

    @Test
    fun `an insert at the front moves everything and one at the end moves nothing`() {
        assertEquals(1, PageTurn.reindexAfterInsert(0, 0))
        assertEquals(3, PageTurn.reindexAfterInsert(2, 0))
        // Appending past the last entry's page leaves every entry exactly where it was.
        assertEquals(0, PageTurn.reindexAfterInsert(0, 5))
        assertEquals(4, PageTurn.reindexAfterInsert(4, 5))
    }

    @Test
    fun `a delete pulls the pages after it back one`() {
        // Page 2 deleted: 0 and 1 stand, 3 becomes 2, 4 becomes 3.
        assertEquals(0, PageTurn.reindexAfterDelete(0, 2))
        assertEquals(1, PageTurn.reindexAfterDelete(1, 2))
        assertEquals(2, PageTurn.reindexAfterDelete(3, 2))
        assertEquals(3, PageTurn.reindexAfterDelete(4, 2))
    }

    @Test
    fun `the deleted page's own index is left alone — the drop is by key`() {
        // Total on purpose: an entry ON the deleted page is dropped at the call site by pageKey,
        // which is the only thing that certainly names the page that went. If this returned null (or
        // shifted) for `index == at`, a STALE index on an entry belonging to a page still in the
        // notebook would quietly take that entry away.
        assertEquals(2, PageTurn.reindexAfterDelete(2, 2))
        assertEquals(0, PageTurn.reindexAfterDelete(0, 0))
    }

    @Test
    fun `an insert then a delete of the same position is the identity`() {
        // The round trip the notebook's own undo makes: insert at p, take it back. Every entry must
        // be exactly where it started, or the replay's walk turns to the wrong page.
        for (at in 0..4) {
            for (index in 0..5) {
                val inserted = PageTurn.reindexAfterInsert(index, at)
                assertEquals(index, PageTurn.reindexAfterDelete(inserted, at))
            }
        }
    }
}
