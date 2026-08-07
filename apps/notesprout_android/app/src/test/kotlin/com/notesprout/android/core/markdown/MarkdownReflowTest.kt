package com.notesprout.android.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownReflowTest {

    @Test
    fun joinsWrappedLinesIntoOneParagraph() {
        val recognized = """
            The morning was quiet and the
            light came in sideways across
            the table.
        """.trimIndent()
        assertEquals(
            "The morning was quiet and the light came in sideways across the table.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun keepsBlankLineParagraphBreaks() {
        val recognized = "First paragraph one\nsecond line.\n\nSecond paragraph\nkeeps its own break."
        assertEquals(
            "First paragraph one second line.\n\nSecond paragraph keeps its own break.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun collapsesRunsOfBlankLines() {
        assertEquals("One\n\nTwo", MarkdownReflow.reflow("One\n\n\n\nTwo"))
    }

    @Test
    fun headingsStandAlone() {
        // Neither absorbed into the paragraph above nor merged with the line below.
        val recognized = "Some prose here\n# A Heading\nThe body starts\nhere."
        assertEquals(
            "Some prose here\n# A Heading\nThe body starts here.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun rulesStandAlone() {
        assertEquals("Above\n---\nBelow it", MarkdownReflow.reflow("Above\n---\nBelow it"))
    }

    @Test
    fun listItemsKeepTheirOwnLinesButAbsorbWraps() {
        val recognized = "- buy milk\n- call the bank about\nthe transfer\n- post the letter"
        assertEquals(
            "- buy milk\n- call the bank about the transfer\n- post the letter",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun orderedAndTaskListsAreListsToo() {
        assertEquals("1. first\n2. second", MarkdownReflow.reflow("1. first\n2. second"))
        assertEquals("- [ ] a task\n- [x] done", MarkdownReflow.reflow("- [ ] a task\n- [x] done"))
    }

    @Test
    fun blockquotesAbsorbWrapsButStartTheirOwnLine() {
        assertEquals(
            "text\n> quoted line that wraps here",
            MarkdownReflow.reflow("text\n> quoted line that\nwraps here"),
        )
    }

    @Test
    fun tableRowsAreNeverJoined() {
        val table = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        assertEquals(table, MarkdownReflow.reflow(table))
    }

    @Test
    fun fencedCodeIsUntouched() {
        val src = "before\n```\nfun main() {\n\n    println(1)\n}\n```\nafter one\nafter two"
        assertEquals(
            "before\n```\nfun main() {\n\n    println(1)\n}\n```\nafter one after two",
            MarkdownReflow.reflow(src),
        )
    }

    @Test
    fun dropsTheLeadingSpaceRecognitionLeavesOnParagraphStarts() {
        // Recognizers hand back a leading space on most lines. Once the wrap breaks are gone it shows
        // up as a dent at the start of every paragraph.
        val recognized = " First paragraph starts\n here and wraps.\n\n Second one\n does too."
        assertEquals(
            "First paragraph starts here and wraps.\n\nSecond one does too.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun nestedListIndentationSurvives() {
        // Up to three leading spaces on a list item mark nesting — that whitespace is structure, not
        // noise, and trimming it would flatten the list.
        val nested = "- top item\n  - nested item\n  1. nested ordered"
        assertEquals(nested, MarkdownReflow.reflow(nested))
    }

    @Test
    fun leadingSpaceOnAHeadingOrRuleIsDropped() {
        assertEquals("# Heading\n---", MarkdownReflow.reflow("  # Heading\n  ---"))
    }

    @Test
    fun indentedLinesStandAlone() {
        assertEquals("text\n    indented code\n    more code", MarkdownReflow.reflow("text\n    indented code\n    more code"))
    }

    @Test
    fun hardBreakIsHonored() {
        // Two trailing spaces are Markdown's explicit line break — not a wrap.
        assertEquals("line one  \nline two", MarkdownReflow.reflow("line one  \nline two"))
    }

    @Test
    fun idempotent() {
        val once = MarkdownReflow.reflow("a\nb\n\nc\nd")
        assertEquals(once, MarkdownReflow.reflow(once))
    }

    @Test
    fun alreadyReflowedTextIsReturnedUnchanged() {
        // The editor compares input to output to decide whether to report "nothing to reflow", so an
        // exact-identity result on settled text is part of the contract.
        for (settled in listOf("one two", "# Heading\n\nA paragraph.", "- a\n- b", "")) {
            assertEquals(settled, MarkdownReflow.reflow(settled))
        }
        assertTrue(MarkdownReflow.reflow("one\ntwo") != "one\ntwo")
    }

    @Test
    fun emptyAndWhitespaceOnly() {
        assertEquals("", MarkdownReflow.reflow(""))
        assertEquals("", MarkdownReflow.reflow("\n\n   \n"))
    }
}
