package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers an ordered list will actually draw.
 *
 * A run keeps the number its first item was written with (CommonMark's `<ol start>`) and counts on
 * from there. Counting from 1 regardless would make `3. a` render as `1. a` — the source and the
 * rendering disagreeing about a document that is perfectly correct.
 */
class MarkdownParserOrderedListTest {

    private fun numbers(markdown: String): List<Int> =
        MarkdownParser.parse(markdown)
            .filterIsInstance<Block.ListItem>()
            .filter { it.ordered }
            .map { it.displayNumber }

    @Test
    fun plainRun_countsFromOne() {
        assertEquals(listOf(1, 2, 3), numbers("1. a\n2. b\n3. c"))
    }

    @Test
    fun firstItem_setsTheStart() {
        assertEquals(listOf(3, 4, 5), numbers("3. a\n4. b\n5. c"))
    }

    @Test
    fun laterItems_countOnWhateverTheyClaim() {
        assertEquals(listOf(1, 2, 3), numbers("1. a\n1. b\n1. c"))
        assertEquals(listOf(3, 4), numbers("3. a\n9. b"))
    }

    @Test
    fun paragraphBetweenRuns_startsTheSecondAfresh() {
        assertEquals(listOf(1, 2, 7), numbers("1. a\n2. b\n\nA paragraph.\n\n7. c"))
    }

    @Test
    fun nestedDepths_countSeparately() {
        // Two spaces of indent is one level to this parser.
        assertEquals(listOf(1, 1, 2, 2), numbers("1. a\n  1. sub\n  2. sub two\n2. b"))
    }
}
