package com.symmetricalpalmtree.notesprout.ext.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser's block + inline coverage the port needs beyond the two ported suites
 * ([MarkdownParserImageTest], [MarkdownParserOrderedListTest]): heading levels 1–6, emphasis, code,
 * list glyph inputs (unordered / task / depth), quote, rule, blank source.
 */
class MarkdownParserTest {

    private fun text(inlines: List<Inline>): String = inlines.joinToString("") {
        when (it) {
            is Inline.Text -> it.text
            is Inline.Bold -> "<b>" + text(it.children) + "</b>"
            is Inline.Italic -> "<i>" + text(it.children) + "</i>"
            is Inline.Strikethrough -> "<s>" + text(it.children) + "</s>"
            is Inline.Code -> "<code>" + it.text + "</code>"
            is Inline.Link -> "<a>" + it.displayText + "</a>"
        }
    }

    @Test
    fun `heading levels one to six`() {
        for (level in 1..6) {
            val block = MarkdownParser.parse("#".repeat(level) + " Title $level").single() as Block.Heading
            assertEquals(level, block.level)
            assertEquals("Title $level", text(block.inlines))
        }
    }

    @Test
    fun `seven hashes is a paragraph, not a heading`() {
        assertTrue(MarkdownParser.parse("####### nope").single() is Block.Paragraph)
    }

    @Test
    fun `heading text keeps inline emphasis`() {
        val block = MarkdownParser.parse("## Meeting **notes** _today_").single() as Block.Heading
        assertEquals("Meeting <b>notes</b> <i>today</i>", text(block.inlines))
    }

    @Test
    fun `bold italic strike code and link inlines`() {
        val p = MarkdownParser.parse("a **b** *c* __d__ _e_ ~~f~~ `g *raw*` [h](u)").single() as Block.Paragraph
        assertEquals("a <b>b</b> <i>c</i> <b>d</b> <i>e</i> <s>f</s> <code>g *raw*</code> <a>h</a>", text(p.inlines))
    }

    @Test
    fun `unclosed markers are literal`() {
        val p = MarkdownParser.parse("a **b ~~c `d [e](f").single() as Block.Paragraph
        assertEquals("a **b ~~c `d [e](f", text(p.inlines))
    }

    @Test
    fun `unordered task and nested items`() {
        val items = MarkdownParser.parse("- one\n  * two\n    + three\n- [ ] todo\n- [x] done")
            .map { it as Block.ListItem }
        assertEquals(listOf(0, 1, 2, 0, 0), items.map { it.depth })
        assertEquals(listOf(false, false, false, true, true), items.map { it.isTask })
        assertEquals(listOf(false, false, false, false, true), items.map { it.checked })
        assertTrue(items.none { it.ordered })
        assertEquals("three", text(items[2].inlines))
    }

    @Test
    fun `blockquote joins its lines`() {
        val q = MarkdownParser.parse("> first\n> second").single() as Block.Blockquote
        assertEquals("first second", text(q.inlines))
    }

    @Test
    fun `horizontal rules`() {
        assertEquals(Block.HorizontalRule, MarkdownParser.parse("---").single())
        assertEquals(Block.HorizontalRule, MarkdownParser.parse("* * *").single())
        assertEquals(Block.HorizontalRule, MarkdownParser.parse("___").single())
        assertTrue(MarkdownParser.parse("--").single() is Block.Paragraph)
    }

    @Test
    fun `paragraph lines join and a block start ends them`() {
        val blocks = MarkdownParser.parse("one\ntwo\n# H\nthree")
        assertEquals(3, blocks.size)
        assertEquals("one two", text((blocks[0] as Block.Paragraph).inlines))
        assertTrue(blocks[1] is Block.Heading)
        assertEquals("three", text((blocks[2] as Block.Paragraph).inlines))
    }

    @Test
    fun `blank source parses to nothing`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("  \n\n\t").isEmpty())
    }
}
