package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text document's chrome table (M8). Each of these shows itself on a device as a control that is
 * there when it should not be — a "Show pages" button on an ordinary notebook opened from its
 * Document tool — or missing when it is the only way onward.
 */
class TextDocumentRulesTest {

    private val page = DocumentContract.SCOPE_PAGE
    private val notebook = DocumentContract.SCOPE_NOTEBOOK

    // ── Show pages ────────────────────────────────────────────────────────────

    @Test
    fun `a text document standing on its notebook document shows the button`() {
        assertTrue(TextDocumentRules.showsPages(textDocument = true, scope = notebook))
    }

    @Test
    fun `the page scope has no room for it — the flip cluster is there`() {
        assertFalse(TextDocumentRules.showsPages(textDocument = true, scope = page))
    }

    @Test
    fun `an ordinary notebook never shows it, in either scope`() {
        // Opened from the notebook's own Document tool: its pages are already on screen behind this
        // editor, and the back arrow is how the writer gets to them.
        assertFalse(TextDocumentRules.showsPages(textDocument = false, scope = notebook))
        assertFalse(TextDocumentRules.showsPages(textDocument = false, scope = page))
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    @Test
    fun `only a text document renames from its title`() {
        assertTrue(TextDocumentRules.offersRename(textDocument = true))
        assertFalse(TextDocumentRules.offersRename(textDocument = false))
    }

    @Test
    fun `the name belongs to the notebook, so the scope is irrelevant to it`() {
        // No scope argument at all: the same title is tappable standing on a page and standing on
        // the notebook document.
        assertTrue(TextDocumentRules.offersRename(true))
    }

    // ── Which names are worth asking the host about ───────────────────────────

    @Test
    fun `a new name goes to the host`() {
        assertTrue(TextDocumentRules.renameWorthAsking("Field notes", "Notes"))
    }

    @Test
    fun `blank is a silent no-op, never a refusal`() {
        assertFalse(TextDocumentRules.renameWorthAsking("", "Notes"))
        assertFalse(TextDocumentRules.renameWorthAsking("   \t ", "Notes"))
    }

    @Test
    fun `the name it already has is a silent no-op too`() {
        assertFalse(TextDocumentRules.renameWorthAsking("Notes", "Notes"))
        // Typed with the trailing space the field allows — still the same name.
        assertFalse(TextDocumentRules.renameWorthAsking("  Notes ", "Notes"))
    }

    @Test
    fun `re-casing is a real rename — the host, not this side, judges it`() {
        assertTrue(TextDocumentRules.renameWorthAsking("NOTES", "Notes"))
    }

    @Test
    fun `everything else is the host's to refuse`() {
        // Illegal characters, reserved words and sibling collisions all go across: only the host can
        // see the other notebooks, and its refusal carries the sentence the writer reads.
        assertTrue(TextDocumentRules.renameWorthAsking("a/b", "Notes"))
        assertTrue(TextDocumentRules.renameWorthAsking("Default", "Notes"))
    }
}
