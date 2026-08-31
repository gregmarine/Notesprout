package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Reflow's conservative table: what it joins, and — more importantly — everything it refuses to.
 * A wrongly removed break destroys structure the writer put there, so most of this suite is about
 * breaks staying put.
 */
class MarkdownReflowTest {

    // ── Joining ───────────────────────────────────────────────────────────────

    @Test
    fun wrappedLinesBecomeOneParagraph() {
        val recognized = "The morning was quiet and the\nlight came in sideways across\nthe table."
        assertEquals(
            "The morning was quiet and the light came in sideways across the table.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun blankLinesStayAsParagraphBreaks() {
        val recognized = "First paragraph one\nsecond line.\n\nSecond paragraph\nkeeps its own break."
        assertEquals(
            "First paragraph one second line.\n\nSecond paragraph keeps its own break.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun runsOfBlankLinesCollapseToOneBreak() {
        assertEquals("One\n\nTwo", MarkdownReflow.reflow("One\n\n\n\nTwo"))
    }

    @Test
    fun leadingAndTrailingBlankLinesAreDropped() {
        assertEquals("One", MarkdownReflow.reflow("\n\nOne\n\n\n"))
    }

    // ── Lines that stand alone ────────────────────────────────────────────────

    @Test
    fun headingsJoinInNeitherDirection() {
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
    fun aLineThatMerelyStartsWithADashIsNotARule() {
        // RULE must match the *whole* line, and the bullet pattern needs whitespace after its
        // marker — so this is ordinary prose, free to absorb the wrap below it.
        assertEquals("--- and more of it", MarkdownReflow.reflow("--- and more\nof it"))
    }

    @Test
    fun tableRowsAreNeverJoined() {
        val table = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        assertEquals(table, MarkdownReflow.reflow(table))
    }

    @Test
    fun aTableRowDoesNotAbsorbTheLineBelowIt() {
        assertEquals("| a | b |\nloose text", MarkdownReflow.reflow("| a | b |\nloose text"))
    }

    @Test
    fun indentedLinesStandAloneSoCodeBlocksSurvive() {
        val src = "text\n    indented code\n    more code"
        assertEquals(src, MarkdownReflow.reflow(src))
    }

    @Test
    fun aTabIndentIsAnIndentToo() {
        assertEquals("text\n\tindented\nplain", MarkdownReflow.reflow("text\n\tindented\nplain"))
    }

    // ── Lines that open but absorb ────────────────────────────────────────────

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
        assertEquals("1) first\n2) second", MarkdownReflow.reflow("1) first\n2) second"))
        assertEquals("- [ ] a task\n- [x] done", MarkdownReflow.reflow("- [ ] a task\n- [x] done"))
    }

    @Test
    fun blockquotesAbsorbWrapsButStartTheirOwnLine() {
        assertEquals(
            "text\n> quoted line that wraps here",
            MarkdownReflow.reflow("text\n> quoted line that\nwraps here"),
        )
        assertEquals("> one\n> two", MarkdownReflow.reflow("> one\n> two"))
    }

    // ── Fenced code ───────────────────────────────────────────────────────────

    @Test
    fun fencedCodeIsUntouchedBlankLinesIncluded() {
        val src = "before\n```\nfun main() {\n\n    println(1)\n}\n```\nafter one\nafter two"
        assertEquals(
            "before\n```\nfun main() {\n\n    println(1)\n}\n```\nafter one after two",
            MarkdownReflow.reflow(src),
        )
    }

    @Test
    fun tildeFencesCountAsFencesAsWell() {
        val src = "~~~\none\n\ntwo\n~~~"
        assertEquals(src, MarkdownReflow.reflow(src))
    }

    // ── Whitespace ────────────────────────────────────────────────────────────

    @Test
    fun theLeadingSpaceRecognitionLeavesOnEveryLineIsDropped() {
        // A recognizer hands back a leading space on most lines. Once the wrap breaks are gone it
        // reads as a dent at the start of every paragraph.
        val recognized = " First paragraph starts\n here and wraps.\n\n Second one\n does too."
        assertEquals(
            "First paragraph starts here and wraps.\n\nSecond one does too.",
            MarkdownReflow.reflow(recognized),
        )
    }

    @Test
    fun nestedListIndentationSurvives() {
        // Up to three leading spaces on a list marker are nesting — structure, not noise. Trimming
        // them would flatten the list.
        val nested = "- top item\n  - nested item\n  1. nested ordered"
        assertEquals(nested, MarkdownReflow.reflow(nested))
    }

    @Test
    fun leadingSpaceOnAHeadingOrRuleIsDropped() {
        assertEquals("# Heading\n---", MarkdownReflow.reflow("  # Heading\n  ---"))
    }

    @Test
    fun aHardBreakIsHonoredAndKeptAsExactlyTwoSpaces() {
        assertEquals("line one  \nline two", MarkdownReflow.reflow("line one  \nline two"))
        // More trailing spaces still mean one hard break, and are normalised to the two that say so.
        assertEquals("line one  \nline two", MarkdownReflow.reflow("line one     \nline two"))
    }

    @Test
    fun aSingleTrailingSpaceIsNotAHardBreak() {
        assertEquals("line one line two", MarkdownReflow.reflow("line one \nline two"))
    }

    @Test
    fun aJoinedLineKeepsItsOwnHardBreak() {
        // "beta  " is a wrap onto "alpha" *and* a hard break before "gamma". Joining must not be
        // what deletes the break — the joined line still ends the paragraph line it joined into.
        assertEquals("alpha beta  \ngamma", MarkdownReflow.reflow("alpha\nbeta  \ngamma"))
        assertEquals("- item wraps  \nnext", MarkdownReflow.reflow("- item\nwraps  \nnext"))
    }

    @Test
    fun aJoinedHardBreakSurvivesASecondPass() {
        // The break the first pass keeps is the break that stops the second pass joining "gamma".
        val once = MarkdownReflow.reflow("alpha\nbeta  \ngamma")
        assertEquals(once, MarkdownReflow.reflow(once))
    }

    // ── Settled input ─────────────────────────────────────────────────────────

    @Test
    fun reflowIsIdempotent() {
        val corpus = listOf(
            "a\nb\n\nc\nd",
            " First\n line.\n\n Second\n line.",
            "# Heading\ntext that\nwraps\n\n- item one\ncontinued\n- item two",
            "before\n```\ncode\n\nmore\n```\nafter one\nafter two",
            "| a | b |\n| 1 | 2 |\nprose after",
            "line one  \nline two\n\n    indented\nplain",
            "alpha\nbeta  \ngamma",
            "> quoted that\nwraps\n\n---\ntail",
        )
        for (src in corpus) {
            val once = MarkdownReflow.reflow(src)
            assertEquals("not idempotent for: $src", once, MarkdownReflow.reflow(once))
        }
    }

    @Test
    fun settledTextComesBackUnchanged() {
        // The editor compares input to output to decide whether to say "nothing to join", so an
        // exact match on already-settled text is part of the contract.
        for (settled in listOf("one two", "# Heading\n\nA paragraph.", "- a\n- b", "")) {
            assertEquals(settled, MarkdownReflow.reflow(settled))
        }
        assertNotEquals("one\ntwo", MarkdownReflow.reflow("one\ntwo"))
    }

    @Test
    fun emptyAndWhitespaceOnlyInputYieldNothing() {
        assertEquals("", MarkdownReflow.reflow(""))
        assertEquals("", MarkdownReflow.reflow("\n\n   \n"))
    }
}
