package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.ext.document.FlipRules.Outcome
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole flip decision table. Each of these shows itself on a device as either a dead arrow or a
 * save landing on the wrong page, so none of them may live only in the controller.
 */
class FlipRulesTest {

    private fun check(
        busy: Boolean = false,
        leaving: Boolean = false,
        hasTarget: Boolean = true,
        direction: Int = DocumentContract.PAGE_NEXT,
        pageIndex: Int = 1,
        pageCount: Int = 3,
    ) = FlipRules.check(busy, leaving, hasTarget, direction, pageIndex, pageCount)

    @Test
    fun `a middle page flips both ways`() {
        assertEquals(Outcome.GO, check(direction = DocumentContract.PAGE_NEXT))
        assertEquals(Outcome.GO, check(direction = DocumentContract.PAGE_PREV))
    }

    @Test
    fun `the first page has no previous`() {
        assertEquals(Outcome.AT_FIRST, check(direction = DocumentContract.PAGE_PREV, pageIndex = 0))
        assertEquals(Outcome.GO, check(direction = DocumentContract.PAGE_NEXT, pageIndex = 0))
    }

    @Test
    fun `the last page has no next`() {
        assertEquals(Outcome.AT_LAST, check(direction = DocumentContract.PAGE_NEXT, pageIndex = 2))
        assertEquals(Outcome.GO, check(direction = DocumentContract.PAGE_PREV, pageIndex = 2))
    }

    @Test
    fun `a one-page notebook is both edges at once`() {
        assertEquals(
            Outcome.AT_FIRST,
            check(direction = DocumentContract.PAGE_PREV, pageIndex = 0, pageCount = 1),
        )
        assertEquals(
            Outcome.AT_LAST,
            check(direction = DocumentContract.PAGE_NEXT, pageIndex = 0, pageCount = 1),
        )
    }

    @Test
    fun `busy blocks — a flip over a flip, or over a bring in`() {
        assertEquals(Outcome.BLOCKED, check(busy = true))
        assertEquals(Outcome.BLOCKED, check(busy = true, direction = DocumentContract.PAGE_PREV))
        // Blocked wins over an edge: nothing should be said about a tap that was never allowed.
        assertEquals(Outcome.BLOCKED, check(busy = true, pageIndex = 2))
    }

    @Test
    fun `a leaving screen never flips`() {
        assertEquals(Outcome.BLOCKED, check(leaving = true))
    }

    @Test
    fun `no adopted target, nothing to flip from`() {
        assertEquals(Outcome.BLOCKED, check(hasTarget = false))
        assertEquals(Outcome.BLOCKED, check(hasTarget = false, pageIndex = 0))
    }

    @Test
    fun `the notebook scope is not a page`() {
        // −1 is the notebook target (M7): it has no neighbours, and the chords no-op there.
        assertEquals(Outcome.BLOCKED, check(pageIndex = -1, direction = DocumentContract.PAGE_PREV))
        assertEquals(Outcome.BLOCKED, check(pageIndex = -1, direction = DocumentContract.PAGE_NEXT))
    }

    @Test
    fun `an unknown direction moves nothing`() {
        assertEquals(Outcome.BLOCKED, check(direction = 0))
        assertEquals(Outcome.BLOCKED, check(direction = 2))
    }
}
