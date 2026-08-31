package com.symmetricalpalmtree.notesproutsn.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document editor's formatting operations.
 *
 * [MarkdownFormatter] edits through [TextBuffer] rather than `android.text` precisely so this suite
 * can exist: the caret arithmetic is where the off-by-ones live, and it is checked here with no
 * device and no Robolectric. The buffer below is the whole of the test rig.
 */
class MarkdownFormatterTest {

    /** A [TextBuffer] over a StringBuilder — the same four operations an Editable would provide. */
    private class Buf(initial: String) : TextBuffer {
        val text = StringBuilder(initial)
        override val length: Int get() = text.length
        override fun get(index: Int): Char = text[index]
        override fun substring(start: Int, end: Int): String = text.substring(start, end)
        override fun replace(start: Int, end: Int, replacement: String) {
            text.replace(start, end, replacement)
        }
    }

    private fun Buf.str() = text.toString()
    private fun Buf.selected(sel: MarkdownFormatter.Selection) = text.substring(sel.start, sel.end)

    // ── Inline markers ────────────────────────────────────────────────────────

    @Test
    fun boldWrapsTheSelectionAndKeepsItSelected() {
        val buf = Buf("hello world")
        val sel = MarkdownFormatter.toggleInline(buf, 6, 11, "**")

        assertEquals("hello **world**", buf.str())
        assertEquals("world", buf.selected(sel))
    }

    @Test
    fun aSecondPressUnwrapsMarkersJustOutsideTheSelection() {
        val buf = Buf("hello **world**")
        val sel = MarkdownFormatter.toggleInline(buf, 8, 13, "**")

        assertEquals("hello world", buf.str())
        assertEquals("world", buf.selected(sel))
    }

    @Test
    fun markersDraggedIntoTheSelectionAreAlsoStripped() {
        val buf = Buf("hello **world**")
        val sel = MarkdownFormatter.toggleInline(buf, 6, 15, "**")

        assertEquals("hello world", buf.str())
        assertEquals("world", buf.selected(sel))
    }

    @Test
    fun aSelectionOfExactlyOneMarkerIsWrappedNotUnwrapped() {
        // Without the length guard, "**" would read as both halves of a wrap and delete itself.
        val buf = Buf("**")
        val sel = MarkdownFormatter.toggleInline(buf, 0, 2, "**")

        assertEquals("******", buf.str())
        assertEquals(2, sel.start)
        assertEquals(4, sel.end)
    }

    @Test
    fun withNoSelectionTheWordUnderTheCaretIsWrapped() {
        val buf = Buf("hello world")
        val sel = MarkdownFormatter.toggleInline(buf, 8, 8, "*")

        assertEquals("hello *world*", buf.str())
        assertEquals("world", buf.selected(sel))
    }

    @Test
    fun aContractionCountsAsOneWord() {
        val buf = Buf("don't stop")
        val sel = MarkdownFormatter.toggleInline(buf, 2, 2, "*")

        assertEquals("*don't* stop", buf.str())
        assertEquals("don't", buf.selected(sel))
    }

    @Test
    fun withNoWordTheCaretIsParkedBetweenFreshMarkers() {
        val buf = Buf("hello ")
        val sel = MarkdownFormatter.toggleInline(buf, 6, 6, "**")

        assertEquals("hello ****", buf.str())
        assertEquals(8, sel.start)
        assertEquals(8, sel.end)
    }

    @Test
    fun theOtherInlineMarkersBehaveTheSameWay() {
        for (marker in listOf("*", "~~", "`")) {
            val buf = Buf("word")
            val sel = MarkdownFormatter.toggleInline(buf, 0, 4, marker)
            assertEquals("${marker}word$marker", buf.str())
            assertEquals("word", buf.selected(sel))

            val off = MarkdownFormatter.toggleInline(buf, sel.start, sel.end, marker)
            assertEquals("word", buf.str())
            assertEquals("word", buf.selected(off))
        }
    }

    // ── Line markers ──────────────────────────────────────────────────────────

    @Test
    fun headingPrefixesTheLineAndCarriesTheCaretPastTheMarker() {
        val buf = Buf("Title")
        val sel = MarkdownFormatter.toggleBlock(buf, 5, 5, MarkdownFormatter.Block.HEADING, 1)

        assertEquals("# Title", buf.str())
        assertEquals(7, sel.start)
    }

    @Test
    fun theSameHeadingLevelTogglesOff() {
        val buf = Buf("## Title")
        MarkdownFormatter.toggleBlock(buf, 3, 3, MarkdownFormatter.Block.HEADING, 2)

        assertEquals("Title", buf.str())
    }

    @Test
    fun aDifferentHeadingLevelReplacesRatherThanToggles() {
        val buf = Buf("## Title")
        MarkdownFormatter.toggleBlock(buf, 3, 3, MarkdownFormatter.Block.HEADING, 1)

        assertEquals("# Title", buf.str())
    }

    @Test
    fun aCaretInsideTheRemovedMarkerIsClampedIntoTheLine() {
        val buf = Buf("## Title")
        val sel = MarkdownFormatter.toggleBlock(buf, 1, 1, MarkdownFormatter.Block.HEADING, 2)

        assertEquals("Title", buf.str())
        assertEquals(0, sel.start)
        assertEquals(0, sel.end)
    }

    @Test
    fun bulletsApplyToEveryLineTheSelectionTouches() {
        val buf = Buf("one\ntwo\nthree")
        MarkdownFormatter.toggleBlock(buf, 0, 13, MarkdownFormatter.Block.BULLET)

        assertEquals("- one\n- two\n- three", buf.str())
    }

    @Test
    fun orderedListsAreNumberedOneUpwardsAcrossTheSelection() {
        val buf = Buf("one\ntwo\nthree")
        MarkdownFormatter.toggleBlock(buf, 0, 13, MarkdownFormatter.Block.ORDERED)

        assertEquals("1. one\n2. two\n3. three", buf.str())
    }

    @Test
    fun switchingListKindReplacesTheExistingMarker() {
        val buf = Buf("- one\n- two")
        MarkdownFormatter.toggleBlock(buf, 0, 11, MarkdownFormatter.Block.ORDERED)

        assertEquals("1. one\n2. two", buf.str())
    }

    @Test
    fun aMixedSelectionIsMadeUniformRatherThanCleared() {
        // Only the first line is already a task, so the press applies instead of toggling off.
        val buf = Buf("- [ ] a\nb")
        MarkdownFormatter.toggleBlock(buf, 0, 9, MarkdownFormatter.Block.TASK)

        assertEquals("- [ ] a\n- [ ] b", buf.str())
    }

    @Test
    fun tasksToggleOffBackToABareLine() {
        val buf = Buf("- [ ] milk")
        MarkdownFormatter.toggleBlock(buf, 6, 6, MarkdownFormatter.Block.TASK)

        assertEquals("milk", buf.str())
    }

    @Test
    fun aTickedTaskIsStillATask() {
        val buf = Buf("- [x] milk")
        MarkdownFormatter.toggleBlock(buf, 6, 6, MarkdownFormatter.Block.TASK)

        assertEquals("milk", buf.str())
    }

    @Test
    fun blockMarkersPreserveLeadingIndentation() {
        val buf = Buf("  nested")
        MarkdownFormatter.toggleBlock(buf, 8, 8, MarkdownFormatter.Block.BULLET)

        assertEquals("  - nested", buf.str())
    }

    @Test
    fun quoteAppliesAndTogglesOff() {
        val buf = Buf("quoted")
        MarkdownFormatter.toggleBlock(buf, 0, 0, MarkdownFormatter.Block.QUOTE)
        assertEquals("> quoted", buf.str())

        MarkdownFormatter.toggleBlock(buf, 4, 4, MarkdownFormatter.Block.QUOTE)
        assertEquals("quoted", buf.str())
    }

    @Test
    fun blankSeparatorsInsideASelectionTakeNoMarkerAndNoOrdinal() {
        // A marker on the separator would mint an empty item and push every number below it along.
        val buf = Buf("alpha\n\nbeta")
        MarkdownFormatter.toggleBlock(buf, 0, 11, MarkdownFormatter.Block.ORDERED)

        assertEquals("1. alpha\n\n2. beta", buf.str())
    }

    @Test
    fun blankSeparatorsAreSkippedByEveryBlockKind() {
        for ((block, marked) in listOf(
            MarkdownFormatter.Block.BULLET to "- alpha\n\n- beta",
            MarkdownFormatter.Block.TASK to "- [ ] alpha\n\n- [ ] beta",
            MarkdownFormatter.Block.QUOTE to "> alpha\n\n> beta",
        )) {
            val buf = Buf("alpha\n\nbeta")
            MarkdownFormatter.toggleBlock(buf, 0, 11, block)
            assertEquals(marked, buf.str())
        }
    }

    @Test
    fun aBlankSeparatorDoesNotHoldTheSelectionOpen() {
        // The blank line is not "a line lacking the marker", so every real line already carrying it
        // means the press is a toggle *off* — otherwise the second press would be a no-op.
        for ((block, marked) in listOf(
            MarkdownFormatter.Block.ORDERED to "1. alpha\n\n2. beta",
            MarkdownFormatter.Block.BULLET to "- alpha\n\n- beta",
            MarkdownFormatter.Block.TASK to "- [ ] alpha\n\n- [ ] beta",
            MarkdownFormatter.Block.QUOTE to "> alpha\n\n> beta",
        )) {
            val buf = Buf(marked)
            MarkdownFormatter.toggleBlock(buf, 0, marked.length, block)
            assertEquals("alpha\n\nbeta", buf.str())
        }
    }

    @Test
    fun anAllBlankSelectionStillTakesTheMarker() {
        // The empty line a list is started on: there is no separator to protect, only an invitation.
        val buf = Buf("")
        MarkdownFormatter.toggleBlock(buf, 0, 0, MarkdownFormatter.Block.BULLET)

        assertEquals("- ", buf.str())
    }

    @Test
    fun aHeadingLevelOutsideOneToSixIsClamped() {
        val buf = Buf("Title")
        MarkdownFormatter.toggleBlock(buf, 0, 0, MarkdownFormatter.Block.HEADING, 9)

        assertEquals("###### Title", buf.str())
    }

    // ── Skeleton insertions ───────────────────────────────────────────────────

    @Test
    fun linkKeepsTheSelectionAsTheLabelAndSelectsTheUrlPlaceholder() {
        val buf = Buf("see docs here")
        val sel = MarkdownFormatter.insertLink(buf, 4, 8)

        assertEquals("see [docs](url) here", buf.str())
        assertEquals("url", buf.selected(sel))
    }

    @Test
    fun linkWithNoSelectionSelectsItsOwnPlaceholderText() {
        val buf = Buf("")
        val sel = MarkdownFormatter.insertLink(buf, 0, 0)

        assertEquals("[text](url)", buf.str())
        assertEquals("text", buf.selected(sel))
    }

    @Test
    fun anImageWithNoSelectionLandsWithItsDescriptionSelected() {
        val buf = Buf("")
        val sel = MarkdownFormatter.insertImage(buf, 0, 0)

        assertEquals("![description](url)", buf.str())
        assertEquals("description", buf.selected(sel))
    }

    @Test
    fun aSelectionBecomesTheAltTextAndTheUrlIsLeftSelected() {
        val buf = Buf("the barn")
        val sel = MarkdownFormatter.insertImage(buf, 0, 8)

        assertEquals("![the barn](url)", buf.str())
        assertEquals("url", buf.selected(sel))
    }

    @Test
    fun aRuleOnABlankLineReusesThatLine() {
        val buf = Buf("above\n\n")
        MarkdownFormatter.insertRule(buf, 6, 6)

        assertEquals("above\n---\n", buf.str())
    }

    @Test
    fun aRuleAtTheEndOfTheBufferGetsALineToLandOn() {
        val buf = Buf("")
        val sel = MarkdownFormatter.insertRule(buf, 0, 0)

        assertEquals("---\n", buf.str())
        assertEquals(4, sel.start)
    }

    @Test
    fun aRuleOnAWrittenLineGoesBelowIt() {
        val buf = Buf("above")
        val sel = MarkdownFormatter.insertRule(buf, 5, 5)

        assertEquals("above\n---\n", buf.str())
        assertEquals(buf.length, sel.start)
    }

    @Test
    fun aRuleInTheMiddleOpensItsOwnLineWithoutEatingTheNextOne() {
        val buf = Buf("above\nbelow")
        val sel = MarkdownFormatter.insertRule(buf, 2, 2)

        assertEquals("above\n---\n\nbelow", buf.str())
        assertEquals(10, sel.start)
    }

    // ── Enter inside a list ───────────────────────────────────────────────────

    private fun enter(before: String, after: String = "") =
        MarkdownFormatter.listEnter(before, after)

    @Test
    fun enterAfterABulletWritesTheNextBullet() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("- "), enter("- milk"))
    }

    @Test
    fun theBulletCharacterCarriesOver() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("* "), enter("* milk"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("+ "), enter("+ milk"))
    }

    @Test
    fun enterAfterANumberedItemCountsOn() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("2. "), enter("1. first"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("10. "), enter("9. ninth"))
    }

    @Test
    fun enterAfterATaskWritesAnUncheckedTask() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("- [ ] "), enter("- [ ] buy milk"))
    }

    @Test
    fun aFinishedTaskStillYieldsAnUnfinishedOne() {
        // Whatever is written next has not been done yet.
        assertEquals(MarkdownFormatter.ListEnter.Continue("- [ ] "), enter("- [x] done"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("* [ ] "), enter("* [X] done"))
    }

    @Test
    fun indentationCarriesOverSoANestedListStaysNested() {
        assertEquals(MarkdownFormatter.ListEnter.Continue("  - "), enter("  - nested"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("  3. "), enter("  2. nested"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("  - [ ] "), enter("  - [ ] nested"))
    }

    @Test
    fun aSecondEnterEndsTheSeriesAndTakesTheMarkerWithIt() {
        assertEquals(MarkdownFormatter.ListEnter.End(2), enter("- "))
        assertEquals(MarkdownFormatter.ListEnter.End(3), enter("1. "))
        assertEquals(MarkdownFormatter.ListEnter.End(6), enter("- [ ] "))
        // An empty ticked box ends the series too.
        assertEquals(MarkdownFormatter.ListEnter.End(6), enter("- [x] "))
        // The indent goes with it, or the new paragraph would start indented.
        assertEquals(MarkdownFormatter.ListEnter.End(4), enter("  - "))
    }

    @Test
    fun theEndDecisionIsMadeByTheLineNotByKeyTiming() {
        // Nothing here consults a clock: the same empty item ends the series however long the pause
        // between the two Enters was.
        repeat(3) { assertEquals(MarkdownFormatter.ListEnter.End(2), enter("- ", after = "")) }
        assertEquals(MarkdownFormatter.ListEnter.End(2), enter("- ", after = "   "))
    }

    @Test
    fun splittingAnItemMidWayCarriesOnRatherThanEnding() {
        // The caret sits after the marker of an item that still has text to its right: that text
        // moves down and must keep its place in the list.
        assertEquals(MarkdownFormatter.ListEnter.Continue("- "), enter("- ", after = "milk"))
        assertEquals(MarkdownFormatter.ListEnter.Continue("2. "), enter("1. ", after = "first"))
    }

    @Test
    fun enterOutsideAListIsLeftAlone() {
        assertNull(enter("just a paragraph"))
        assertNull(enter("# A heading"))
        assertNull(enter("> quoted"))
        assertNull(enter(""))
        assertNull(enter("---"))
        // A number with no space after the dot is not a list item.
        assertNull(enter("1."))
        assertNull(enter("1.first"))
    }

    // ── Renumbering ordered lists ─────────────────────────────────────────────

    /** Apply the rewrites back to front so the earlier offsets stay valid — as the editor does. */
    private fun renumbered(text: String): String {
        val sb = StringBuilder(text)
        for (change in MarkdownFormatter.renumberOrderedLists(text).asReversed()) {
            sb.replace(change.at, change.at + change.length, change.marker)
        }
        return sb.toString()
    }

    /** Renumbering must settle in one pass: re-running over its own output asks for nothing more. */
    private fun assertSettles(text: String) {
        val once = renumbered(text)
        assertTrue(
            "second pass wanted more edits on: $once",
            MarkdownFormatter.renumberOrderedLists(once).isEmpty(),
        )
        assertEquals(once, renumbered(once))
    }

    @Test
    fun anItemInsertedInTheMiddleRenumbersWhatFollows() {
        // Exactly what the buffer holds the instant Enter is pressed inside 1-2-3.
        assertEquals("1. a\n2. b\n3. \n4. c", renumbered("1. a\n2. b\n3. \n3. c"))
        assertSettles("1. a\n2. b\n3. \n3. c")
    }

    @Test
    fun aGapLeftByADeletedItemClosesUp() {
        assertEquals("1. a\n2. c", renumbered("1. a\n3. c"))
        assertSettles("1. a\n3. c")
    }

    @Test
    fun aListThatStartsAtThreeKeepsStartingAtThree() {
        // Markdown renders that list as 3, 4 — so the source is made to say 3, 4. The rendered
        // output never changes, which is what makes this safe to run unasked.
        assertEquals("3. a\n4. b", renumbered("3. a\n9. b"))
        assertSettles("3. a\n9. b")
    }

    @Test
    fun allOnesCountUp() {
        assertEquals("1. a\n2. b\n3. c", renumbered("1. a\n1. b\n1. c"))
        assertSettles("1. a\n1. b\n1. c")
    }

    @Test
    fun nestedRunsCountSeparately() {
        assertEquals(
            "1. a\n   1. sub\n   2. sub two\n2. b",
            renumbered("1. a\n   1. sub\n   1. sub two\n5. b"),
        )
        assertSettles("1. a\n   1. sub\n   1. sub two\n5. b")
    }

    @Test
    fun aDeeplyIndentedRunUnderAListIsStillAList() {
        // Four spaces is an indented code block only when there is no list above it to nest under.
        assertEquals(
            "1. a\n    1. sub\n    2. sub two",
            renumbered("1. a\n    1. sub\n    7. sub two"),
        )
        assertSettles("1. a\n    1. sub\n    7. sub two")
    }

    @Test
    fun aShallowerItemRestartsWhatWasNestedUnderIt() {
        // The second nested run is a *new* list, so it is free to keep its own start number — the
        // same courtesy the outer list gets. Only the outer run is counted on.
        assertEquals(
            "1. a\n   1. sub\n2. b\n   8. sub again",
            renumbered("1. a\n   1. sub\n4. b\n   8. sub again"),
        )
        assertSettles("1. a\n   1. sub\n4. b\n   8. sub again")
    }

    @Test
    fun aWrappedContinuationLineDoesNotBreakTheRun() {
        assertEquals(
            "1. a\n   still item a\n2. b",
            renumbered("1. a\n   still item a\n7. b"),
        )
    }

    @Test
    fun aParagraphBetweenTwoListsStartsTheSecondAfresh() {
        val text = "1. a\n\nA paragraph.\n\n1. b"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun oneBlankLineKeepsTheListGoingButTwoEndIt() {
        // A loose list is one list, and Markdown renders it 1, 2.
        assertEquals("1. a\n\n2. b", renumbered("1. a\n\n6. b"))
        // Two blanks are a break; the next list keeps whatever start it was given.
        assertEquals("1. a\n\n\n6. b", renumbered("1. a\n\n\n6. b"))
    }

    @Test
    fun bulletsAndTasksAreLeftAlone() {
        val text = "- a\n- b\n- [ ] c"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun aBulletInTheMiddleEndsTheOrderedRun() {
        assertEquals("1. a\n- b\n4. c", renumbered("1. a\n- b\n4. c"))
    }

    @Test
    fun fencedCodeIsNotRenumbered() {
        val backticks = "1. a\n\n```\n1. not a list\n1. still not\n```\n\n1. b"
        assertEquals(backticks, renumbered(backticks))

        val tildes = "1. a\n\n~~~\n1. not a list\n1. still not\n~~~\n\n1. b"
        assertEquals(tildes, renumbered(tildes))
    }

    @Test
    fun anIndentedBlockWithNoListAboveItIsLeftAlone() {
        val text = "Some prose.\n\n    1. looks like code\n    1. also code"
        assertEquals(text, renumbered(text))
    }

    @Test
    fun anAlreadyCorrectListCostsNoEdits() {
        assertEquals(
            emptyList<MarkdownFormatter.Renumber>(),
            MarkdownFormatter.renumberOrderedLists("1. a\n2. b"),
        )
    }

    @Test
    fun spacingAfterTheDotIsPreserved() {
        assertEquals("1. a\n2.   wide", renumbered("1. a\n9.   wide"))
        assertSettles("1. a\n9.   wide")
    }

    @Test
    fun multiDigitMarkersAreRewrittenWhole() {
        assertEquals("9. i\n10. j\n11. k", renumbered("9. i\n1. j\n1. k"))
        assertSettles("9. i\n1. j\n1. k")
    }

    @Test
    fun rewritesArriveInAscendingOffsetOrder() {
        val changes = MarkdownFormatter.renumberOrderedLists("1. a\n1. b\n1. c\n1. d")
        assertEquals(changes.map { it.at }.sorted(), changes.map { it.at })
        assertTrue(changes.isNotEmpty())
    }
}
