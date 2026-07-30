package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers the renderer will draw for ordered lists.
 *
 * These exist because the parser used to ignore a list's written start number and always count from 1,
 * so `3. a` previewed as `1. a` — the source and the Preview disagreeing about a document that was fine.
 * Honouring the first item's number is also what CommonMark does (`<ol start>`), which is what keeps a
 * renumbered document rendering identically here and outside the app.
 */
class MarkdownParserOrderedListTest {

    private fun numbers(markdown: String): List<Int> =
        MarkdownParser.parse(markdown)
            .filterIsInstance<Block.ListItem>()
            .filter { it.ordered }
            .map { it.displayNumber }

    @Test
    fun `a plain list counts from one`() {
        assertEquals(listOf(1, 2, 3), numbers("1. a\n2. b\n3. c"))
    }

    @Test
    fun `the first item sets where the numbering starts`() {
        assertEquals(listOf(3, 4, 5), numbers("3. a\n4. b\n5. c"))
    }

    @Test
    fun `items after the first count on whatever they claim`() {
        // Markdown ignores the later numbers — which is why the editor renumbers the source to match.
        assertEquals(listOf(1, 2, 3), numbers("1. a\n1. b\n1. c"))
        assertEquals(listOf(3, 4), numbers("3. a\n9. b"))
    }

    @Test
    fun `a paragraph between two lists starts the second afresh`() {
        assertEquals(listOf(1, 2, 7), numbers("1. a\n2. b\n\nA paragraph.\n\n7. c"))
    }

    @Test
    fun `nesting counts separately`() {
        // Two-space indent is one depth level to this parser.
        assertEquals(listOf(1, 1, 2, 2), numbers("1. a\n  1. sub\n  2. sub two\n2. b"))
    }
}
