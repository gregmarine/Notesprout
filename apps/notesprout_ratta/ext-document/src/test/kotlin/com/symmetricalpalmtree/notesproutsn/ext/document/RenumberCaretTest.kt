package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the caret lands when the editor settles an ordered list's numbers underneath it.
 *
 * Renumbering is rendering-neutral and runs without asking — from `Ctrl+Shift+F` and from every
 * Enter inside a list — so the caret is the only thing the writer can notice it by. The cases that
 * matter are the ones where the marker under the caret is itself being rewritten, and shrinking
 * ones most of all: `10.` becoming `3.` takes a character out from under the caret.
 *
 * The rewrites are the real ones, taken from [MarkdownFormatter.renumberOrderedLists], so a change
 * to how a marker is written shows up here rather than in a hand-built fixture that agrees with
 * nothing.
 */
class RenumberCaretTest {

    private fun caret(text: String, at: Int) =
        EditorTools.caretAfterRenumber(MarkdownFormatter.renumberOrderedLists(text), at)

    /** [text] with the rewrites applied, so an offset can be read against what the writer sees. */
    private fun renumbered(text: String): String {
        val sb = StringBuilder(text)
        for (c in MarkdownFormatter.renumberOrderedLists(text).asReversed()) {
            sb.replace(c.at, c.at + c.length, c.marker)
        }
        return sb.toString()
    }

    // "1. x\n3. y\n10. z" → "1. x\n2. y\n3. z": the second marker keeps its width, the third loses one.
    private val gapped = "1. x\n3. y\n10. z"

    @Test
    fun `a caret in the content is carried by the markers above it`() {
        assertEquals("1. x\n2. y\n3. z", renumbered(gapped))
        assertEquals(3, caret(gapped, 3))       // "x", above every rewrite
        assertEquals(8, caret(gapped, 8))       // "y", past one same-width rewrite
        assertEquals(13, caret(gapped, 14))     // "z", past the one-character shrink
    }

    @Test
    fun `a caret inside a shrinking marker lands at the end of the new one`() {
        // Offsets 10..13 are inside "10. "; the marker those characters belonged to no longer
        // exists, and the end of its replacement is where the item's text now starts.
        for (inside in 11..13) {
            assertEquals("caret at $inside", 13, caret(gapped, inside))
        }
        assertEquals('z', renumbered(gapped)[13])
    }

    @Test
    fun `a caret at the very start of a rewritten marker stays put`() {
        // It is before the marker, not in it — the line it opens has not moved.
        assertEquals(10, caret(gapped, 10))
        assertEquals(5, caret(gapped, 5))
    }

    @Test
    fun `a caret inside a widening marker lands at the end of the new one too`() {
        val widening = "9. a\n1. b\n1. c"   // → "9. a\n10. b\n11. c"
        assertEquals("9. a\n10. b\n11. c", renumbered(widening))
        assertEquals(9, caret(widening, 6))
        assertEquals('b', renumbered(widening)[9])
    }

    @Test
    fun `nothing to renumber leaves the caret exactly where it was`() {
        val settled = "1. x\n2. y\n3. z"
        for (at in settled.indices) assertEquals(at, caret(settled, at))
    }
}
