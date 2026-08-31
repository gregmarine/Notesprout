package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.ext.document.ScopeRules.SourceLine
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook document's whole decision table (M7). Each of these shows itself on a device as a
 * line that reads wrong, a button with the wrong word on it, a document blanked by a merge that had
 * nothing to give — or, the one that cannot be seen at all until it is too late, a buffer pushed
 * under the wrong key.
 */
class ScopeRulesTest {

    private val page = DocumentContract.SCOPE_PAGE
    private val notebook = DocumentContract.SCOPE_NOTEBOOK

    // ── May the toggle run ────────────────────────────────────────────────────

    @Test
    fun `an adopted, idle, staying screen may toggle`() {
        assertTrue(ScopeRules.mayToggle(busy = false, leaving = false, hasTarget = true))
    }

    @Test
    fun `busy blocks — a switch over a flip, or over a bring in`() {
        assertFalse(ScopeRules.mayToggle(busy = true, leaving = false, hasTarget = true))
    }

    @Test
    fun `a leaving screen never switches scope`() {
        assertFalse(ScopeRules.mayToggle(busy = false, leaving = true, hasTarget = true))
    }

    @Test
    fun `no state yet, nothing to switch from`() {
        assertFalse(ScopeRules.mayToggle(busy = false, leaving = false, hasTarget = false))
    }

    // ── Which way the tap goes ────────────────────────────────────────────────

    @Test
    fun `the toggle is its own inverse`() {
        assertEquals(notebook, ScopeRules.other(page))
        assertEquals(page, ScopeRules.other(notebook))
        assertEquals(page, ScopeRules.other(ScopeRules.other(page)))
    }

    @Test
    fun `only the notebook scope is the notebook scope`() {
        assertTrue(ScopeRules.isNotebook(notebook))
        assertFalse(ScopeRules.isNotebook(page))
    }

    // ── What the strip says ───────────────────────────────────────────────────

    @Test
    fun `a page's document keeps M6's three lines`() {
        assertEquals(SourceLine.DRAFTED, ScopeRules.provenance(page, DocumentContract.SOURCE_DRAFTED))
        assertEquals(SourceLine.STALE, ScopeRules.provenance(page, DocumentContract.SOURCE_STALE))
        assertEquals(SourceLine.NONE, ScopeRules.provenance(page, DocumentContract.SOURCE_NONE))
    }

    @Test
    fun `the notebook document reads its own two, and stays silent about the third`() {
        assertEquals(SourceLine.MERGED, ScopeRules.provenance(notebook, DocumentContract.SOURCE_DRAFTED))
        assertEquals(SourceLine.MERGE_STALE, ScopeRules.provenance(notebook, DocumentContract.SOURCE_STALE))
        // Not "Not merged from these pages": the notebook document exists because the writer asked
        // for it, and naming the absence would be noise about something deliberate.
        assertEquals(SourceLine.SILENT, ScopeRules.provenance(notebook, DocumentContract.SOURCE_NONE))
    }

    @Test
    fun `the two scopes never share a line`() {
        for (source in 0..2) {
            assertTrue(
                "source $source read the same in both scopes",
                ScopeRules.provenance(page, source) != ScopeRules.provenance(notebook, source),
            )
        }
    }

    // ── Whether a merge may touch the buffer ──────────────────────────────────

    @Test
    fun `a blank merge is a silent no-op in the notebook scope`() {
        // The pages had nothing to give. A Replace here would blank a hand-authored document in
        // exchange for nothing.
        assertFalse(ScopeRules.mergeLands(notebook, ""))
        assertFalse(ScopeRules.mergeLands(notebook, "   \n\n \t "))
    }

    @Test
    fun `a merge with words in it lands`() {
        assertTrue(ScopeRules.mergeLands(notebook, "the first page"))
        assertTrue(ScopeRules.mergeLands(notebook, "\n\n# Heading\n"))
    }

    @Test
    fun `the page scope is unchanged — a blank bring in still applies`() {
        assertTrue(ScopeRules.mergeLands(page, ""))
        assertTrue(ScopeRules.mergeLands(page, "  \n "))
    }

    // ── The mode-routing guard ────────────────────────────────────────────────

    @Test
    fun `a bundle from the same target is kept`() {
        assertTrue(ScopeRules.restoredBufferApplies("pg:7", "pg:7"))
    }

    @Test
    fun `a bundle from the notebook document never lands on a page`() {
        assertFalse(ScopeRules.restoredBufferApplies("nb:abc", "pg:7"))
        assertFalse(ScopeRules.restoredBufferApplies("pg:7", "nb:abc"))
    }

    @Test
    fun `a bundle from another page never lands either`() {
        assertFalse(ScopeRules.restoredBufferApplies("pg:6", "pg:7"))
    }

    @Test
    fun `no key at all is a mismatch, not a pass`() {
        // An older bundle, or one written before the load ever landed: there is nothing to match
        // against, and the cost of dropping is at worst one debounce of typing.
        assertFalse(ScopeRules.restoredBufferApplies(null, "pg:7"))
    }

    @Test
    fun `the match is exact — never a prefix`() {
        assertFalse(ScopeRules.restoredBufferApplies("pg:7", "pg:70"))
        assertFalse(ScopeRules.restoredBufferApplies("pg:70", "pg:7"))
        assertFalse(ScopeRules.restoredBufferApplies("PG:7", "pg:7"))
    }
}
