package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The follow / walk-back planner (K4). Payloads are composed with [LinkPayload.encode] rather than
 * hand-written, so the two stay in step; what is asserted here is only the *classification* — every
 * existence check belongs to `LinkFollowFlow`, after planning.
 */
class LinkNavTest {

    private val here = "nb-current"
    private val there = "nb-other"

    private fun page(pageId: String) =
        LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, pageId)

    private fun notebook(notebookId: String) =
        LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, notebookId, null)

    private fun notebookPage(notebookId: String, pageId: String) =
        LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_NOTEBOOK_PAGE, notebookId, pageId)

    // ── planFollow ───────────────────────────────────────────────────────────

    @Test
    fun `a page target is an in-notebook hop`() {
        assertEquals(
            LinkNav.Follow.SamePage("pg-7"),
            LinkNav.planFollow(page("pg-7"), here),
        )
    }

    @Test
    fun `another notebook is a whole-notebook target with no page`() {
        assertEquals(
            LinkNav.Follow.OtherNotebook(there, null),
            LinkNav.planFollow(notebook(there), here),
        )
    }

    @Test
    fun `a notebook target naming the current notebook is a silent no-op`() {
        assertEquals(LinkNav.Follow.NoOp, LinkNav.planFollow(notebook(here), here))
    }

    @Test
    fun `a page of another notebook carries both ids`() {
        assertEquals(
            LinkNav.Follow.OtherNotebook(there, "pg-3"),
            LinkNav.planFollow(notebookPage(there, "pg-3"), here),
        )
    }

    @Test
    fun `a notebook-page target naming the current notebook is an in-notebook hop`() {
        assertEquals(
            LinkNav.Follow.SamePage("pg-3"),
            LinkNav.planFollow(notebookPage(here, "pg-3"), here),
        )
    }

    @Test
    fun `a corrupt payload is dead`() {
        assertEquals(LinkNav.Follow.Dead, LinkNav.planFollow("not a payload", here))
    }

    @Test
    fun `a future or foreign version tag is dead`() {
        assertEquals(LinkNav.Follow.Dead, LinkNav.planFollow("L9|1|0||pg-1", here))
    }

    @Test
    fun `an empty payload is dead`() {
        assertEquals(LinkNav.Follow.Dead, LinkNav.planFollow("", here))
    }

    // ── planBack ─────────────────────────────────────────────────────────────

    @Test
    fun `a trail entry in the open notebook walks back in place`() {
        assertEquals(
            LinkNav.Back.SamePage("pg-1"),
            LinkNav.planBack(here, "pg-1", here),
        )
    }

    @Test
    fun `a trail entry in another notebook walks back out`() {
        assertEquals(
            LinkNav.Back.OtherNotebook(there, "pg-1"),
            LinkNav.planBack(there, "pg-1", here),
        )
    }
}
