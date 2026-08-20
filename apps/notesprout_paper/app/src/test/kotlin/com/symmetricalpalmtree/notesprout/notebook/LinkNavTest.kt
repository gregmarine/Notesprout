package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkNavTest {

    private val current = "nb-A"
    private val other = "nb-B"
    private val pages = listOf("p1", "p2", "p3")

    // ── planFollow — DEST_PAGE ──────────────────────────────────────────────

    @Test
    fun followPageLiveIsSamePage() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_PAGE, notebookId = null, pageId = "p2",
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.Plan.SamePage("p2"), plan)
    }

    @Test
    fun followPageUnknownIsDead() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_PAGE, notebookId = null, pageId = "p9",
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    @Test
    fun followPageNullIdIsDead() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_PAGE, notebookId = null, pageId = null,
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    // ── planFollow — DEST_NOTEBOOK ──────────────────────────────────────────

    @Test
    fun followNotebookOtherIsOtherNotebook() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK, notebookId = other, pageId = null,
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.Plan.OtherNotebook(other, null), plan)
    }

    @Test
    fun followNotebookSelfIsNoOp() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK, notebookId = current, pageId = null,
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.NoOp, plan)
    }

    @Test
    fun followNotebookNullIdIsDead() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK, notebookId = null, pageId = null,
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    // ── planFollow — DEST_NOTEBOOK_PAGE ─────────────────────────────────────

    @Test
    fun followNotebookPageOtherIsOtherNotebook() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK_PAGE, notebookId = other, pageId = "px",
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.Plan.OtherNotebook(other, "px"), plan)
    }

    @Test
    fun followNotebookPageCurrentLiveIsSamePage() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK_PAGE, notebookId = current, pageId = "p3",
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.Plan.SamePage("p3"), plan)
    }

    @Test
    fun followNotebookPageCurrentDeadIsDead() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK_PAGE, notebookId = current, pageId = "p9",
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    @Test
    fun followNotebookPageNullNotebookIdIsDead() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK_PAGE, notebookId = null, pageId = "p1",
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    @Test
    fun followNotebookPageNullPageIdIsDead() {
        val plan = LinkNav.planFollow(
            kind = ExtensionContract.DEST_NOTEBOOK_PAGE, notebookId = other, pageId = null,
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    // ── planFollow — unknown kind ────────────────────────────────────────────

    @Test
    fun followUnknownKindIsDead() {
        val plan = LinkNav.planFollow(
            kind = 99, notebookId = other, pageId = "p1",
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    @Test
    fun followNegativeUnknownKindIsDead() {
        val plan = LinkNav.planFollow(
            kind = -1, notebookId = current, pageId = "p1",
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.Plan.Dead, plan)
    }

    // ── planBack ─────────────────────────────────────────────────────────────

    @Test
    fun backCurrentNotebookLiveIsSamePage() {
        val step = LinkNav.planBack(
            entryNotebookId = current, entryPageId = "p1",
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.BackStep.SamePage("p1"), step)
    }

    @Test
    fun backCurrentNotebookDeadIsSkip() {
        val step = LinkNav.planBack(
            entryNotebookId = current, entryPageId = "p9",
            currentNotebookId = current, pageIds = pages,
        )
        assertSame(LinkNav.BackStep.Skip, step)
    }

    @Test
    fun backOtherNotebookIsOtherNotebook() {
        val step = LinkNav.planBack(
            entryNotebookId = other, entryPageId = "px",
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.BackStep.OtherNotebook(other, "px"), step)
        assertTrue(step is LinkNav.BackStep.OtherNotebook)
    }

    @Test
    fun backOtherNotebookWithCoincidingPageIdStillOtherNotebook() {
        // The page id happens to match a current-notebook page id, but the notebook id decides.
        val step = LinkNav.planBack(
            entryNotebookId = other, entryPageId = "p1",
            currentNotebookId = current, pageIds = pages,
        )
        assertEquals(LinkNav.BackStep.OtherNotebook(other, "p1"), step)
    }
}
