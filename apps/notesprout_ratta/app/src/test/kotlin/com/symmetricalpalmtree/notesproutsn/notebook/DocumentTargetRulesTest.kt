package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decision core of the document editor's host hooks (M6): where the editor is, what the source
 * strip says, where a flip lands, and what the read window is loaded with.
 *
 * [DocumentHostHooks] needs an open `.soil` and cannot be constructed here at all — these are the
 * rules it routes through, so the tables below are the tables it actually runs.
 */
class DocumentTargetRulesTest {

    private val pages = listOf("p1", "p2", "p3")

    // ── Which page the editor is on ──────

    @Test
    fun noTargetYetIsTheDisplayedPage() {
        assertEquals("p2", DocumentTargetRules.resolveTarget(null, pages, "p2"))
    }

    @Test
    fun aLiveTargetWins() {
        // The notebook stays where it was while the editor flips — the target is the answer.
        assertEquals("p3", DocumentTargetRules.resolveTarget("p3", pages, "p1"))
    }

    @Test
    fun aTargetThatIsNoLongerAPageFallsBack() {
        // The page was deleted under the editor, or saved state carried a stale id.
        assertEquals("p1", DocumentTargetRules.resolveTarget("gone", pages, "p1"))
    }

    // ── The source strip ──────

    @Test
    fun neverDraftedIsNone() {
        assertEquals(DocumentContract.SOURCE_NONE, DocumentTargetRules.source(null, 900L))
    }

    @Test
    fun aPageThatMovedOnIsStale() {
        assertEquals(DocumentContract.SOURCE_STALE, DocumentTargetRules.source(100L, 101L))
    }

    @Test
    fun anUnchangedPageIsDrafted() {
        assertEquals(DocumentContract.SOURCE_DRAFTED, DocumentTargetRules.source(100L, 100L))
        // Below the watermark too: arc 17's close-time purge can lower the maximum, and a draft
        // does not become stale because rows were compacted out from under it.
        assertEquals(DocumentContract.SOURCE_DRAFTED, DocumentTargetRules.source(100L, 40L))
    }

    // ── Flip bounds ──────

    @Test
    fun aFlipMovesOnePageInEachDirection() {
        assertEquals(2, DocumentTargetRules.flipIndex(1, DocumentContract.PAGE_NEXT, 3))
        assertEquals(0, DocumentTargetRules.flipIndex(1, DocumentContract.PAGE_PREV, 3))
    }

    @Test
    fun theEndsDoNotWrap() {
        assertNull(DocumentTargetRules.flipIndex(0, DocumentContract.PAGE_PREV, 3))
        assertNull(DocumentTargetRules.flipIndex(2, DocumentContract.PAGE_NEXT, 3))
    }

    @Test
    fun aSinglePageNotebookFlipsNowhere() {
        assertNull(DocumentTargetRules.flipIndex(0, DocumentContract.PAGE_NEXT, 1))
        assertNull(DocumentTargetRules.flipIndex(0, DocumentContract.PAGE_PREV, 1))
    }

    @Test
    fun anUnplaceableTargetFlipsNowhere() {
        assertNull(DocumentTargetRules.flipIndex(-1, DocumentContract.PAGE_NEXT, 3))
        assertNull(DocumentTargetRules.flipIndex(3, DocumentContract.PAGE_PREV, 3))
    }

    // ── Opening: the staged seed's decision table ──────

    @Test
    fun aStoredDocumentIsServedAndNeverSeededOver() {
        // Seed once: the document exists, so the stage — even one naming this very page — loses.
        assertEquals(
            DocumentTargetRules.Serve.Stored("written by hand"),
            DocumentTargetRules.openDecision("written by hand", "p1", "p1", "recognized"),
        )
    }

    @Test
    fun aStagedSeedForThisPageIsServedAsADraft() {
        assertEquals(
            DocumentTargetRules.Serve.Seed("recognized"),
            DocumentTargetRules.openDecision(null, "p1", "p1", "recognized"),
        )
    }

    @Test
    fun aStageForAnotherPageIsNeverServed() {
        assertEquals(
            DocumentTargetRules.Serve.Stored(""),
            DocumentTargetRules.openDecision(null, "p1", "p2", "recognized"),
        )
    }

    @Test
    fun aBlankStageIsNoSeed() {
        assertEquals(
            DocumentTargetRules.Serve.Stored(""),
            DocumentTargetRules.openDecision(null, "p1", "p1", "   \n "),
        )
        assertEquals(
            DocumentTargetRules.Serve.Stored(""),
            DocumentTargetRules.openDecision(null, "p1", null, null),
        )
    }

    @Test
    fun aBlankStoredDocumentIsAbsentAndStaysSeedable() {
        // The repository's blank-means-absent rule reaching the decision: a row a foreign writer
        // left empty must not stand in the way of the seed.
        assertEquals(
            DocumentTargetRules.Serve.Seed("recognized"),
            DocumentTargetRules.openDecision("  ", "p1", "p1", "recognized"),
        )
    }

    // ── Flipping: the seed-on-arrival table ──────

    @Test
    fun aDocumentedPageServesItsDocument() {
        assertEquals(
            DocumentTargetRules.Serve.Stored("page two's document"),
            DocumentTargetRules.flipDecision("page two's document", null),
        )
    }

    @Test
    fun anUndocumentedPageIsSeededOnArrival() {
        assertEquals(
            DocumentTargetRules.Serve.Seed("what the ink says"),
            DocumentTargetRules.flipDecision(null, "what the ink says"),
        )
    }

    @Test
    fun recognitionUnavailableStillLandsTheFlip() {
        // null = recognition could not run at all; "" = it ran and the page had nothing to give.
        // Both land on an empty window, and the page stays seedable either way.
        assertEquals(DocumentTargetRules.Serve.Stored(""), DocumentTargetRules.flipDecision(null, null))
        assertEquals(DocumentTargetRules.Serve.Stored(""), DocumentTargetRules.flipDecision(null, ""))
        assertEquals(DocumentTargetRules.Serve.Stored(""), DocumentTargetRules.flipDecision(null, " \n "))
    }

    // ── M7: the notebook document's key ──────

    @Test
    fun theNotebookKeyIsPrefixedAndResolvesToTheRoot() {
        val nbId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val key = DocumentTargetRules.notebookKey(nbId)
        assertEquals("nb:$nbId", key)
        // Well inside the wire cap, and carries no path character the state's require would refuse.
        assert(key.length <= DocumentContract.MAX_PAGE_KEY_CHARS)
        assertEquals(nbId, DocumentTargetRules.parentFor(key, nbId))
    }

    @Test
    fun aPageKeyResolvesToItself() {
        // The mode-routing floor: a page's key IS its parent, and only the one minted token maps
        // to the root — equality, never a parse, so a page whose id happened to start "nb" is safe.
        assertEquals("p1", DocumentTargetRules.parentFor("p1", "notebook-id"))
        assertEquals("nb", DocumentTargetRules.parentFor("nb", "notebook-id"))
        assertEquals("nb:other", DocumentTargetRules.parentFor("nb:other", "notebook-id"))
    }

    // ── M7: what one page contributes to a merge ──────

    @Test
    fun aPageDocumentWinsOverItsInk() {
        assertEquals("the doc", DocumentTargetRules.mergePagePart("the doc", "the ink"))
    }

    @Test
    fun anUndocumentedPageContributesItsRecognition() {
        assertEquals("the ink", DocumentTargetRules.mergePagePart(null, "the ink"))
        assertEquals("the ink", DocumentTargetRules.mergePagePart("  ", "the ink"))
    }

    @Test
    fun aPageWithNothingToGiveIsDroppedWhole() {
        // null recognition = could not run (the user's "fix it" call: it never blocks the merge —
        // the page just contributes nothing); blank = ran and found nothing. Same drop.
        assertNull(DocumentTargetRules.mergePagePart(null, null))
        assertNull(DocumentTargetRules.mergePagePart(null, "  \n"))
        assertNull(DocumentTargetRules.mergePagePart("", null))
    }

    // ── M7: the merge join ──────

    @Test
    fun pagesJoinWithExactlyOneBlankLine() {
        // og's assembleMarkdown: joinToString("\n\n") then trim — NOT the `---` append rule.
        assertEquals("one\n\ntwo\n\nthree", DocumentTargetRules.mergeText(listOf("one", "two", "three")))
    }

    @Test
    fun droppedPagesLeaveNoSeparatorBehind() {
        assertEquals("one\n\nthree", DocumentTargetRules.mergeText(listOf("one", null, "three")))
        assertEquals("only", DocumentTargetRules.mergeText(listOf(null, "only", null)))
    }

    @Test
    fun anEmptyMergeIsEmpty() {
        assertEquals("", DocumentTargetRules.mergeText(emptyList()))
        assertEquals("", DocumentTargetRules.mergeText(listOf(null, null)))
    }

    @Test
    fun theWholeMergeIsTrimmedButPartsAreNot() {
        // og verbatim: only the ENDS of the whole are trimmed — a part's own trailing newline
        // survives into the join (og never normalises per page, and neither do we).
        assertEquals("one\n\n\ntwo", DocumentTargetRules.mergeText(listOf("one\n", "two\n")))
    }

    // ── M7: entering the notebook scope ──────

    @Test
    fun aStoredNotebookDocumentIsServedAndNeverMergedOver() {
        // Seed once, notebook form: a document exists, the merge (which was not even run — the
        // caller passes null) loses to it.
        assertEquals(
            DocumentTargetRules.Serve.Stored("the final draft"),
            DocumentTargetRules.scopeDecision("the final draft", null),
        )
    }

    @Test
    fun anUndocumentedNotebookServesTheMergeAsADraft() {
        assertEquals(
            DocumentTargetRules.Serve.Seed("merged pages"),
            DocumentTargetRules.scopeDecision(null, "merged pages"),
        )
        // Blank-means-absent reaches this table too.
        assertEquals(
            DocumentTargetRules.Serve.Seed("merged pages"),
            DocumentTargetRules.scopeDecision("  ", "merged pages"),
        )
    }

    @Test
    fun aMergeWithNothingToGiveStillLandsTheToggle() {
        assertEquals(DocumentTargetRules.Serve.Stored(""), DocumentTargetRules.scopeDecision(null, null))
        assertEquals(DocumentTargetRules.Serve.Stored(""), DocumentTargetRules.scopeDecision(null, " \n "))
    }
}
