package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the document editor's formatting operations. [MarkdownFormatter] is free of
 * `android.text` (it edits through [TextBuffer]) precisely so the caret arithmetic — where the
 * off-by-ones live — can be verified without a device.
 */
class MarkdownFormatterTest {

    /** JVM [TextBuffer] over a [StringBuilder]; behaves like an Editable for these operations. */
    private class StringBuffer(initial: String) : TextBuffer {
        val sb = StringBuilder(initial)
        override val length: Int get() = sb.length
        override fun get(index: Int): Char = sb[index]
        override fun substring(start: Int, end: Int): String = sb.substring(start, end)
        override fun replace(start: Int, end: Int, replacement: String) {
            sb.replace(start, end, replacement)
        }
    }

    // ── Inline wrapping ───────────────────────────────────────────────────────

    @Test
    fun `bold wraps the selection and keeps it selected`() {
        val buf = StringBuffer("hello world")
        val sel = MarkdownFormatter.toggleInline(buf, 6, 11, "**")

        assertEquals("hello **world**", buf.sb.toString())
        assertEquals("world", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `bold a second time unwraps it`() {
        val buf = StringBuffer("hello **world**")
        // Selection sits on the inner text, with the markers just outside it.
        val sel = MarkdownFormatter.toggleInline(buf, 8, 13, "**")

        assertEquals("hello world", buf.sb.toString())
        assertEquals("world", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `unwrapping works when the markers are inside the selection`() {
        val buf = StringBuffer("hello **world**")
        val sel = MarkdownFormatter.toggleInline(buf, 6, 15, "**")

        assertEquals("hello world", buf.sb.toString())
        assertEquals("world", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `with no selection the word under the caret is wrapped`() {
        val buf = StringBuffer("hello world")
        val sel = MarkdownFormatter.toggleInline(buf, 8, 8, "*")

        assertEquals("hello *world*", buf.sb.toString())
        assertEquals("world", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `with no word the caret is parked between fresh markers`() {
        val buf = StringBuffer("hello ")
        val sel = MarkdownFormatter.toggleInline(buf, 6, 6, "**")

        assertEquals("hello ****", buf.sb.toString())
        assertEquals(8, sel.start)
        assertEquals(8, sel.end)
    }

    // ── Block markers ─────────────────────────────────────────────────────────

    @Test
    fun `heading prefixes the line and carries the caret past the marker`() {
        val buf = StringBuffer("Title")
        val sel = MarkdownFormatter.toggleBlock(buf, 5, 5, MarkdownFormatter.Block.HEADING, 1)

        assertEquals("# Title", buf.sb.toString())
        assertEquals(7, sel.start)
    }

    @Test
    fun `the same heading level toggles off`() {
        val buf = StringBuffer("## Title")
        MarkdownFormatter.toggleBlock(buf, 3, 3, MarkdownFormatter.Block.HEADING, 2)

        assertEquals("Title", buf.sb.toString())
    }

    @Test
    fun `a different heading level replaces rather than toggles`() {
        val buf = StringBuffer("## Title")
        MarkdownFormatter.toggleBlock(buf, 3, 3, MarkdownFormatter.Block.HEADING, 1)

        assertEquals("# Title", buf.sb.toString())
    }

    @Test
    fun `bullets apply to every line the selection touches`() {
        val buf = StringBuffer("one\ntwo\nthree")
        MarkdownFormatter.toggleBlock(buf, 0, 13, MarkdownFormatter.Block.BULLET)

        assertEquals("- one\n- two\n- three", buf.sb.toString())
    }

    @Test
    fun `ordered lists number sequentially`() {
        val buf = StringBuffer("one\ntwo\nthree")
        MarkdownFormatter.toggleBlock(buf, 0, 13, MarkdownFormatter.Block.ORDERED)

        assertEquals("1. one\n2. two\n3. three", buf.sb.toString())
    }

    @Test
    fun `switching list kind replaces the existing marker`() {
        val buf = StringBuffer("- one\n- two")
        MarkdownFormatter.toggleBlock(buf, 0, 11, MarkdownFormatter.Block.ORDERED)

        assertEquals("1. one\n2. two", buf.sb.toString())
    }

    @Test
    fun `tasks toggle off back to a bare line`() {
        val buf = StringBuffer("- [ ] milk")
        MarkdownFormatter.toggleBlock(buf, 6, 6, MarkdownFormatter.Block.TASK)

        assertEquals("milk", buf.sb.toString())
    }

    @Test
    fun `a checked task is still recognised as a task`() {
        val buf = StringBuffer("- [x] milk")
        MarkdownFormatter.toggleBlock(buf, 6, 6, MarkdownFormatter.Block.TASK)

        assertEquals("milk", buf.sb.toString())
    }

    @Test
    fun `block markers preserve leading indentation`() {
        val buf = StringBuffer("  nested")
        MarkdownFormatter.toggleBlock(buf, 8, 8, MarkdownFormatter.Block.BULLET)

        assertEquals("  - nested", buf.sb.toString())
    }

    @Test
    fun `quote toggles off`() {
        val buf = StringBuffer("> quoted")
        MarkdownFormatter.toggleBlock(buf, 4, 4, MarkdownFormatter.Block.QUOTE)

        assertEquals("quoted", buf.sb.toString())
    }

    // ── Insertions ────────────────────────────────────────────────────────────

    @Test
    fun `link keeps the selection as the label and selects the url placeholder`() {
        val buf = StringBuffer("see docs here")
        val sel = MarkdownFormatter.insertLink(buf, 4, 8)

        assertEquals("see [docs](url) here", buf.sb.toString())
        assertEquals("url", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `link with no selection selects its own placeholder text`() {
        val buf = StringBuffer("")
        val sel = MarkdownFormatter.insertLink(buf, 0, 0)

        assertEquals("[text](url)", buf.sb.toString())
        assertEquals("text", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `a rule on a blank line reuses that line`() {
        val buf = StringBuffer("above\n\n")
        MarkdownFormatter.insertRule(buf, 6, 6)

        assertEquals("above\n---\n", buf.sb.toString())
    }

    @Test
    fun `a rule on a written line goes below it`() {
        val buf = StringBuffer("above")
        val sel = MarkdownFormatter.insertRule(buf, 5, 5)

        assertEquals("above\n---\n", buf.sb.toString())
        assertEquals(buf.sb.length, sel.start)
    }

    // ── Images ────────────────────────────────────────────────────────────────

    @Test
    fun `an image with no selection lands with its description selected`() {
        val buf = StringBuffer("")
        val sel = MarkdownFormatter.insertImage(buf, 0, 0)

        assertEquals("![description](url)", buf.sb.toString())
        assertEquals("description", buf.sb.substring(sel.start, sel.end))
    }

    @Test
    fun `a selection becomes the alt text and the url is left selected`() {
        val buf = StringBuffer("the barn")
        val sel = MarkdownFormatter.insertImage(buf, 0, 8)

        assertEquals("![the barn](url)", buf.sb.toString())
        assertEquals("url", buf.sb.substring(sel.start, sel.end))
    }

    // ── Enter inside a list ───────────────────────────────────────────────────

    private fun enter(before: String, after: String = "") =
        MarkdownFormatter.listEnter(before, after)

    @Test
    fun `enter after a bullet writes the next bullet`() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("- "), enter("- milk"))
    }

    @Test
    fun `the bullet character carries over`() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("* "), enter("* milk"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("+ "), enter("+ milk"))
    }

    @Test
    fun `enter after a numbered item counts on`() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("2. "), enter("1. first"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("10. "), enter("9. ninth"))
    }

    @Test
    fun `enter after a task writes an unchecked task`() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("- [ ] "), enter("- [ ] buy milk"))
    }

    @Test
    fun `a finished task still yields an unfinished one`() {
        // The next thing you write is not already done.
        assertEquals(MarkdownFormatter.ListEnter.Continue("- [ ] "), enter("- [x] done"))
    }

    @Test
    fun `indentation carries over so a nested list stays nested`() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("  - "), enter("  - nested"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("  3. "), enter("  2. nested"))
    }

    @Test
    fun `a second enter ends the series and takes the marker with it`() {
        assertEquals(MarkdownFormatter.ListEnter.End(2), enter("- "))
        assertEquals(MarkdownFormatter.ListEnter.End(3), enter("1. "))
        assertEquals(MarkdownFormatter.ListEnter.End(6), enter("- [ ] "))
        // Indentation goes too, or the new paragraph starts indented.
        assertEquals(MarkdownFormatter.ListEnter.End(4), enter("  - "))
    }

    @Test
    fun `splitting an item mid-way carries on rather than ending`() {
        // Caret sits right after the marker of an item that still has content: the text moved down
        // keeps its place in the list instead of losing its marker.
        assertEquals(MarkdownFormatter.ListEnter.Continue("- "), enter("- ", after = "milk"))
    }

    // ── Renumbering ordered lists ─────────────────────────────────────────────

    /** Apply the rewrites back-to-front so earlier offsets stay valid — as the editor does. */
    private fun renumbered(text: String): String {
        val sb = StringBuilder(text)
        for (change in MarkdownFormatter.renumberOrderedLists(text).asReversed()) {
            sb.replace(change.at, change.at + change.length, change.marker)
        }
        return sb.toString()
    }

    @Test
    fun `an item inserted in the middle renumbers what follows`() {
        // What the editor holds right after Enter in the middle of 1-2-3.
        assertEquals(
            "1. a\n2. b\n3. \n4. c",
            renumbered("1. a\n2. b\n3. \n3. c"),
        )
    }

    @Test
    fun `a gap left by a deleted item closes up`() {
        assertEquals("1. a\n2. c", renumbered("1. a\n3. c"))
    }

    @Test
    fun `a list that starts at three keeps starting at three`() {
        // Markdown renders 3, 4 — so that is what the source should say. Never change the output.
        assertEquals("3. a\n4. b", renumbered("3. a\n9. b"))
    }

    @Test
    fun `all-ones counts up`() {
        assertEquals("1. a\n2. b\n3. c", renumbered("1. a\n1. b\n1. c"))
    }

    @Test
    fun `nested runs count separately`() {
        assertEquals(
            "1. a\n   1. sub\n   2. sub two\n2. b",
            renumbered("1. a\n   1. sub\n   1. sub two\n5. b"),
        )
    }

    @Test
    fun `a wrapped continuation line does not break the run`() {
        assertEquals(
            "1. a\n   still item a\n2. b",
            renumbered("1. a\n   still item a\n7. b"),
        )
    }

    @Test
    fun `a paragraph between two lists starts the second afresh`() {
        val text = "1. a\n\nA paragraph.\n\n1. b"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun `one blank line keeps the list going but two end it`() {
        // A loose list is still one list, and Markdown renders it 1, 2.
        assertEquals("1. a\n\n2. b", renumbered("1. a\n\n6. b"))
        // Two blank lines are a break; the second list keeps its own start.
        assertEquals("1. a\n\n\n6. b", renumbered("1. a\n\n\n6. b"))
    }

    @Test
    fun `bullets and tasks are left alone`() {
        val text = "- a\n- b\n- [ ] c"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun `a bullet in the middle ends the ordered run`() {
        assertEquals("1. a\n- b\n4. c", renumbered("1. a\n- b\n4. c"))
    }

    @Test
    fun `fenced code is not renumbered`() {
        val text = "1. a\n\n```\n1. not a list\n1. still not\n```\n\n1. b"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun `an indented block with no list above it is left alone`() {
        val text = "Some prose.\n\n    1. looks like code\n    1. also code"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun `an already-correct list costs no edits`() {
        assertEquals(emptyList<MarkdownFormatter.Renumber>(), MarkdownFormatter.renumberOrderedLists("1. a\n2. b"))
    }

    @Test
    fun `spacing after the dot is preserved`() {
        assertEquals("1. a\n2.   wide", renumbered("1. a\n9.   wide"))
    }

    @Test
    fun `enter outside a list is left alone`() {
        assertNull(enter("just a paragraph"))
        assertNull(enter("# A heading"))
        assertNull(enter("> quoted"))
        assertNull(enter(""))
        assertNull(enter("---"))
    }
}
