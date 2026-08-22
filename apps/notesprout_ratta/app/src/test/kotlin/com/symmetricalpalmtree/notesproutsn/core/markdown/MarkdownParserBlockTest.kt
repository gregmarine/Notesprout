package com.symmetricalpalmtree.notesproutsn.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Block-level parsing: what counts as a heading, a rule, a quote, a list item, a paragraph. */
class MarkdownParserBlockTest {

    // ── Headings ──────────────────────────────────────────────────────────────

    @Test
    fun headings_oneThroughSix() {
        for (level in 1..6) {
            val block = MarkdownParser.parse("${"#".repeat(level)} Title").single()
            assertTrue("level $level", block is Block.Heading)
            block as Block.Heading
            assertEquals(level, block.level)
            assertEquals("Title", flatten(block.inlines))
        }
    }

    @Test
    fun sevenHashes_isNotAHeading() {
        // Markdown stops at six; a seventh hash makes it ordinary text, hashes and all.
        val block = MarkdownParser.parse("####### Title").single()
        assertTrue(block is Block.Paragraph)
        assertEquals("####### Title", flatten((block as Block.Paragraph).inlines))
    }

    @Test
    fun hashWithoutSpace_isNotAHeading() {
        val block = MarkdownParser.parse("#Title").single()
        assertTrue(block is Block.Paragraph)
        assertEquals("#Title", flatten((block as Block.Paragraph).inlines))
    }

    @Test
    fun headingText_carriesInlines() {
        val heading = MarkdownParser.parse("# **bold** title").single() as Block.Heading
        assertEquals(1, heading.level)
        assertEquals(
            listOf(Inline.Bold(listOf(Inline.Text("bold"))), Inline.Text(" title")),
            heading.inlines,
        )
    }

    // ── Horizontal rules ──────────────────────────────────────────────────────

    @Test
    fun rules_threeOfTheSameChar() {
        for (source in listOf("---", "***", "___", "- - -", "*****")) {
            assertEquals(source, listOf(Block.HorizontalRule), MarkdownParser.parse(source))
        }
    }

    @Test
    fun twoDashes_isNotARule() {
        assertTrue(MarkdownParser.parse("--").single() is Block.Paragraph)
    }

    @Test
    fun mixedChars_areNotARule() {
        // Only one repeated character makes a rule; "-*-" is just text.
        val block = MarkdownParser.parse("-*-").single()
        assertTrue(block is Block.Paragraph)
    }

    // ── Blockquotes ───────────────────────────────────────────────────────────

    @Test
    fun blockquote_withAndWithoutTheSpace() {
        assertEquals("quoted", flatten((MarkdownParser.parse("> quoted").single() as Block.Blockquote).inlines))
        assertEquals("quoted", flatten((MarkdownParser.parse(">quoted").single() as Block.Blockquote).inlines))
    }

    @Test
    fun consecutiveQuoteLines_mergeSpaceJoined() {
        val quote = MarkdownParser.parse("> one\n> two\n>three").single() as Block.Blockquote
        assertEquals("one two three", flatten(quote.inlines))
    }

    @Test
    fun blankLine_endsTheQuote() {
        val blocks = MarkdownParser.parse("> one\n\n> two")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is Block.Blockquote })
    }

    // ── Lists ─────────────────────────────────────────────────────────────────

    @Test
    fun tasks_checkedIsCaseInsensitive() {
        for (source in listOf("- [x] done", "- [X] done")) {
            val item = MarkdownParser.parse(source).single() as Block.ListItem
            assertTrue(source, item.isTask)
            assertTrue(source, item.checked)
            assertEquals("done", flatten(item.inlines))
        }
    }

    @Test
    fun tasks_uncheckedBox() {
        val item = MarkdownParser.parse("- [ ] todo").single() as Block.ListItem
        assertTrue(item.isTask)
        assertFalse(item.checked)
        assertEquals("todo", flatten(item.inlines))
    }

    @Test
    fun tasks_winOverPlainBullets() {
        // "- [x] a" also matches the bullet pattern; the task test has to run first or the
        // checkbox ends up as literal text inside a bullet.
        for (marker in listOf("-", "*", "+")) {
            val item = MarkdownParser.parse("$marker [x] a").single() as Block.ListItem
            assertTrue(marker, item.isTask)
        }
    }

    @Test
    fun bullets_allThreeMarkers() {
        for (marker in listOf("-", "*", "+")) {
            val item = MarkdownParser.parse("$marker item").single() as Block.ListItem
            assertFalse(marker, item.ordered)
            assertFalse(marker, item.isTask)
            assertEquals(marker, 0, item.displayNumber)
            assertEquals(marker, "item", flatten(item.inlines))
        }
    }

    @Test
    fun bulletDepth_isTwoSpacesPerLevel() {
        val depths = MarkdownParser.parse("- a\n  - b\n    - c")
            .filterIsInstance<Block.ListItem>()
            .map { it.depth }
        assertEquals(listOf(0, 1, 2), depths)
    }

    // ── Paragraphs ────────────────────────────────────────────────────────────

    @Test
    fun consecutiveLines_joinWithASpace() {
        val block = MarkdownParser.parse("one\ntwo\nthree").single() as Block.Paragraph
        assertEquals("one two three", flatten(block.inlines))
    }

    @Test
    fun blockStart_endsTheParagraph() {
        val blocks = MarkdownParser.parse("one\n# Heading\ntwo")
        assertEquals(3, blocks.size)
        assertEquals("one", flatten((blocks[0] as Block.Paragraph).inlines))
        assertEquals(1, (blocks[1] as Block.Heading).level)
        assertEquals("two", flatten((blocks[2] as Block.Paragraph).inlines))
    }

    @Test
    fun blankLines_produceNoBlocks() {
        assertEquals(emptyList<Block>(), MarkdownParser.parse("\n\n   \n"))
    }
}
