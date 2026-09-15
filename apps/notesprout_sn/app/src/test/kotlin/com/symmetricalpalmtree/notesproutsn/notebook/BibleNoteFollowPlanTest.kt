package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 42 "Notes" (N3): where a note row goes — the pure table behind [BibleNoteFollow]. */
class BibleNoteFollowPlanTest {

    private val here = "11111111-1111-4111-8111-111111111111"
    private val there = "22222222-2222-4222-8222-222222222222"
    private val pageA = "33333333-3333-4333-8333-333333333333"
    private val pageB = "44444444-4444-4444-8444-444444444444"

    private fun link(notebookId: String, pageId: String) =
        BibleNoteTarget(notebookId, pageId, ExtensionContract.BIBLE_NOTE_KIND_LINK)

    private fun document(notebookId: String, pageId: String) =
        BibleNoteTarget(notebookId, pageId, ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT)

    private fun plan(target: BibleNoteTarget, current: String? = here, pages: List<String> = listOf(pageA, pageB)) =
        BibleNoteFollowRules.plan(target, current, pages)

    @Test
    fun aLinkRowOnThisNotebookIsAFlip() {
        val p = plan(link(here, pageB))
        assertTrue(p is BibleNoteFollowRules.Plan.SamePage)
        assertEquals(pageB, (p as BibleNoteFollowRules.Plan.SamePage).pageId)
    }

    @Test
    fun aLinkRowOnAPageThisNotebookNoLongerHasIsDead() {
        val gone = "55555555-5555-4555-8555-555555555555"
        val p = plan(link(here, gone))
        assertTrue(p is BibleNoteFollowRules.Plan.DeadPage)
        assertEquals(gone, (p as BibleNoteFollowRules.Plan.DeadPage).pageId)
    }

    @Test
    fun aDocumentRowOnThisNotebookOpensTheEditorWithOrWithoutAPage() {
        val bound = plan(document(here, pageA))
        assertTrue(bound is BibleNoteFollowRules.Plan.SameNotebookDocument)
        assertEquals(pageA, (bound as BibleNoteFollowRules.Plan.SameNotebookDocument).pageId)

        val pageless = plan(document(here, ""))
        assertTrue(pageless is BibleNoteFollowRules.Plan.SameNotebookDocument)
        assertEquals("", (pageless as BibleNoteFollowRules.Plan.SameNotebookDocument).pageId)
    }

    @Test
    fun aDocumentRowOnADeadPageOfThisNotebookIsDeadToo() {
        val gone = "55555555-5555-4555-8555-555555555555"
        assertTrue(plan(document(here, gone)) is BibleNoteFollowRules.Plan.DeadPage)
    }

    @Test
    fun aLinkRowOnAnotherNotebookLeavesWithItsPage() {
        val p = plan(link(there, pageA)) as BibleNoteFollowRules.Plan.Other
        assertEquals(there, p.notebookId)
        assertEquals(pageA, p.pageId)
        assertFalse(p.openEditor)
    }

    @Test
    fun aPageBoundDocumentRowOnAnotherNotebookAsksForTheEditor() {
        val p = plan(document(there, pageA)) as BibleNoteFollowRules.Plan.Other
        assertEquals(pageA, p.pageId)
        assertTrue(p.openEditor)
    }

    @Test
    fun aPagelessDocumentRowOnAnotherNotebookJustOpensIt() {
        // The editor's scope toggle lives inside the editor and the replay can only raise it over
        // a page, so the honest answer is the notebook — a text document opens into its own editor.
        val p = plan(document(there, "")) as BibleNoteFollowRules.Plan.Other
        assertNull(p.pageId)
        assertFalse(p.openEditor)
    }

    @Test
    fun withNoNotebookOpenEveryRowIsAHopOut() {
        // The library: `SamePage` is unreachable, which is what makes its two in-notebook
        // lambdas unreachable by construction.
        val p = plan(link(here, pageA), current = null, pages = emptyList())
        assertTrue(p is BibleNoteFollowRules.Plan.Other)
        assertEquals(here, (p as BibleNoteFollowRules.Plan.Other).notebookId)
        assertEquals(pageA, p.pageId)
    }
}
