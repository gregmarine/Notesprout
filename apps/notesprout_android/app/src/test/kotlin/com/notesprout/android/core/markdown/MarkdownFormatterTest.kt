package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
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
}
