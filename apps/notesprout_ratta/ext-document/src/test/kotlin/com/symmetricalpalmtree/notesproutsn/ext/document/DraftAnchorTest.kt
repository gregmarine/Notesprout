package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.ext.document.DraftAnchor.Outcome
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft claim's rules. A wrong answer here is a document that lies about where it came from —
 * either claiming a page it was never drafted from, or losing the provenance of one it was.
 */
class DraftAnchorTest {

    @Test
    fun `nothing is claimed until a seed is adopted`() {
        val a = DraftAnchor()
        assertFalse(a.pending)
        a.arm()
        assertTrue(a.pending)
    }

    @Test
    fun `a drafted push that lands anchors the seed`() {
        val a = DraftAnchor()
        a.arm()
        assertEquals(Outcome.ANCHORED, a.onPushSucceeded(drafted = true))
        assertFalse(a.pending)
    }

    @Test
    fun `an ordinary push never touches the claim`() {
        val a = DraftAnchor()
        a.arm()
        assertEquals(Outcome.UNCHANGED, a.onPushSucceeded(drafted = false))
        assertTrue(a.pending)
        assertEquals(Outcome.UNCHANGED, a.onPushFailed(drafted = false, exceptionMessage = "boom"))
        assertTrue(a.pending)
        assertEquals(
            Outcome.UNCHANGED,
            a.onPushFailed(drafted = false, exceptionMessage = DocumentContract.NO_DRAFT_PENDING),
        )
        assertTrue(a.pending)
    }

    @Test
    fun `NO_DRAFT_PENDING downgrades — the words go again without the claim`() {
        val a = DraftAnchor()
        a.arm()
        assertEquals(
            Outcome.DOWNGRADED,
            a.onPushFailed(drafted = true, exceptionMessage = DocumentContract.NO_DRAFT_PENDING),
        )
        assertFalse(a.pending)
    }

    @Test
    fun `the downgrade matches the contract string exactly`() {
        // `==`, never `contains` and never a prefix: a message that merely mentions it is a
        // different failure, and dropping the claim over one would lose provenance for nothing.
        for (message in listOf(
            "NO_DRAFT_PENDING ",
            " NO_DRAFT_PENDING",
            "java.lang.IllegalStateException: NO_DRAFT_PENDING",
            "no_draft_pending",
            "",
            null,
        )) {
            val a = DraftAnchor()
            a.arm()
            assertEquals(
                "message: $message",
                Outcome.RETRY,
                a.onPushFailed(drafted = true, exceptionMessage = message),
            )
            assertTrue("message: $message", a.pending)
        }
    }

    @Test
    fun `a transport failure keeps the claim for the retry to carry`() {
        val a = DraftAnchor()
        a.arm()
        assertEquals(Outcome.RETRY, a.onPushFailed(drafted = true, exceptionMessage = "dead binder"))
        assertTrue(a.pending)
        // …and the retry, when it lands, anchors it exactly as the first attempt would have.
        assertEquals(Outcome.ANCHORED, a.onPushSucceeded(drafted = true))
        assertFalse(a.pending)
    }

    @Test
    fun `clear drops the claim — a flip onto a stored page`() {
        val a = DraftAnchor()
        a.arm()
        a.clear()
        assertFalse(a.pending)
        assertEquals(Outcome.UNCHANGED, a.onPushSucceeded(drafted = false))
    }
}
