package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDraftTest {

    // ── isUndrafted (the seed condition) ──────────────────────────────────────

    @Test
    fun absentOrEmptyTextIsUndrafted() {
        assertTrue(DocumentDraft.isUndrafted(null))
        assertTrue(DocumentDraft.isUndrafted(""))
        assertTrue(DocumentDraft.isUndrafted("   \n\t\n "))
    }

    @Test
    fun anyRealTextIsDrafted() {
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
    fun appendCollapsesSurroundingWhitespaceIntoTheJoin() {
        // Trailing blank lines in the buffer must not stack on top of the rule's own spacing.
        assertEquals(
            "Edited\n\n---\n\nDraft",
            DocumentDraft.append("Edited\n\n\n", "\n\n  Draft  \n"),
        )
    }

    @Test
    fun appendIntoAnEmptyDocumentAddsNoRule() {
        assertEquals("Draft", DocumentDraft.append("", "Draft"))
        assertEquals("Draft", DocumentDraft.append("   \n ", "Draft"))
    }

    @Test
    fun appendingNothingLeavesTheDocumentAlone() {
        assertEquals("Edited", DocumentDraft.append("Edited", ""))
        assertEquals("Edited", DocumentDraft.append("Edited", "  \n "))
    }

    // ── isStale ───────────────────────────────────────────────────────────────

    @Test
    fun pageWrittenOnAfterTheDraftIsStale() {
        assertTrue(DocumentDraft.isStale(srcUpdatedAt = 100L, layerMaxUpdatedAt = 101L))
    }

    @Test
    fun pageAtOrBehindTheDraftIsNotStale() {
        assertFalse(DocumentDraft.isStale(srcUpdatedAt = 100L, layerMaxUpdatedAt = 100L))
        assertFalse(DocumentDraft.isStale(srcUpdatedAt = 100L, layerMaxUpdatedAt = 99L))
    }

    @Test
    fun handAuthoredDocumentIsNeverStale() {
        // No watermark = never drafted from the page, so there is no earlier state to report on.
        assertFalse(DocumentDraft.isStale(srcUpdatedAt = null, layerMaxUpdatedAt = Long.MAX_VALUE))
    }
}
