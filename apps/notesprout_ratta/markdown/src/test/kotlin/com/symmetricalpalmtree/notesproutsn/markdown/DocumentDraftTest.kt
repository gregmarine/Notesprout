package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seed condition, the append join, and the staleness rule behind a page's document. */
class DocumentDraftTest {

    // ── isUndrafted — the seed condition ──────────────────────────────────────

    @Test
    fun absentOrEmptyTextIsUndrafted() {
        assertTrue(DocumentDraft.isUndrafted(null))
        assertTrue(DocumentDraft.isUndrafted(""))
        assertTrue(DocumentDraft.isUndrafted("   \n\t\n "))
    }

    @Test
    fun anythingWrittenCountsAsDrafted() {
        assertFalse(DocumentDraft.isUndrafted("a"))
        assertFalse(DocumentDraft.isUndrafted("\n# Heading\n"))
    }

    // ── append ────────────────────────────────────────────────────────────────

    @Test
    fun appendJoinsUnderARule() {
        assertEquals(
            "My edited text\n\n---\n\nFresh from the page",
            DocumentDraft.append("My edited text", "Fresh from the page"),
        )
    }

    @Test
    fun theWhitespaceAroundTheJoinIsTheJoinsOwn() {
        // Trailing blank lines in the buffer must not stack on top of the rule's own spacing, and a
        // draft arriving with its own padding must not push the rule away from it.
        assertEquals(
            "Edited\n\n---\n\nDraft",
            DocumentDraft.append("Edited\n\n\n", "\n\n  Draft  \n"),
        )
    }

    @Test
    fun appendingIntoAnEmptyDocumentAddsNoRule() {
        assertEquals("Draft", DocumentDraft.append("", "Draft"))
        assertEquals("Draft", DocumentDraft.append("   \n ", "Draft"))
    }

    @Test
    fun appendingNothingLeavesTheDocumentAlone() {
        assertEquals("Edited", DocumentDraft.append("Edited", ""))
        assertEquals("Edited", DocumentDraft.append("Edited", "  \n "))
    }

    @Test
    fun appendingNothingToNothingIsNothing() {
        assertEquals("", DocumentDraft.append("", ""))
        assertEquals("", DocumentDraft.append("  \n ", "  "))
    }

    @Test
    fun repeatedAppendsStackOneRulePerDraft() {
        val once = DocumentDraft.append("", "One")
        val twice = DocumentDraft.append(once, "Two")
        assertEquals("One\n\n---\n\nTwo\n\n---\n\nThree", DocumentDraft.append(twice, "Three"))
    }

    // ── isStale ───────────────────────────────────────────────────────────────

    @Test
    fun aPageWrittenOnAfterTheDraftIsStale() {
        assertTrue(DocumentDraft.isStale(srcUpdatedAt = 100L, layerMaxUpdatedAt = 101L))
    }

    @Test
    fun aPageAtOrBehindTheDraftIsNotStale() {
        assertFalse(DocumentDraft.isStale(srcUpdatedAt = 100L, layerMaxUpdatedAt = 100L))
        assertFalse(DocumentDraft.isStale(srcUpdatedAt = 100L, layerMaxUpdatedAt = 99L))
    }

    @Test
    fun aHandAuthoredDocumentIsNeverStale() {
        // No watermark means it was never drafted from the page, so there is no earlier state for
        // the page to have moved on from and nothing to report.
        assertFalse(DocumentDraft.isStale(srcUpdatedAt = null, layerMaxUpdatedAt = Long.MAX_VALUE))
    }
}
