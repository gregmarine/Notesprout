package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.notebook.LinkPickerModel.PickMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The picker's decisions (K2): prefill → mode/style, page numbering beside exclusion, the grid page
 * a prefilled selection lives on, and what OK is allowed to compose.
 */
class LinkPickerModelTest {

    private val current = "nb-current"
    private val other = "nb-other"

    private fun page(id: String) = PickerPage(id, 1404, 1872)

    private fun pages(vararg ids: String) = ids.map { page(it) }

    // ── modeFor / chromeFor ──────────────────────────────────────────────────

    @Test
    fun `no prefill opens on this notebook, underlined`() {
        assertEquals(PickMode.THIS_NOTEBOOK, LinkPickerModel.modeFor(null))
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPickerModel.chromeFor(null))
    }

    @Test
    fun `a page prefill opens on this notebook`() {
        val decoded = LinkPayload.decode(LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "p2"))
        assertEquals(PickMode.THIS_NOTEBOOK, LinkPickerModel.modeFor(decoded))
    }

    @Test
    fun `a notebook prefill opens on notebook`() {
        val decoded = LinkPayload.decode(LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_NOTEBOOK, other, null))
        assertEquals(PickMode.NOTEBOOK, LinkPickerModel.modeFor(decoded))
    }

    @Test
    fun `a notebook-page prefill opens on notebook page`() {
        val decoded = LinkPayload.decode(
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_NOTEBOOK_PAGE, other, "p7")
        )
        assertEquals(PickMode.NOTEBOOK_PAGE, LinkPickerModel.modeFor(decoded))
    }

    @Test
    fun `an unusable prefill is a fresh picker, not an error`() {
        // decode() answers null for anything it cannot read; the picker must simply open normally.
        assertNull(LinkPayload.decode("not a payload"))
        assertEquals(PickMode.THIS_NOTEBOOK, LinkPickerModel.modeFor(LinkPayload.decode("not a payload")))
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPickerModel.chromeFor(LinkPayload.decode("not a payload")))
    }

    @Test
    fun `the style latch comes from the prefill`() {
        val none = LinkPayload.decode(LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_PAGE, null, "p1"))
        val underline = LinkPayload.decode(LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "p1"))
        assertEquals(LinkPayload.CHROME_NONE, LinkPickerModel.chromeFor(none))
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPickerModel.chromeFor(underline))
    }

    // ── pageCards ────────────────────────────────────────────────────────────

    @Test
    fun `every page is numbered from one when nothing is excluded`() {
        val cards = LinkPickerModel.pageCards(pages("a", "b", "c"), null)
        assertEquals(listOf("a" to 1, "b" to 2, "c" to 3), cards.map { it.first.id to it.second })
    }

    @Test
    fun `numbering does not drift when the current page is excluded`() {
        val cards = LinkPickerModel.pageCards(pages("a", "b", "c", "d"), excludePageId = "b")
        // "c" is still page 3 — the number a user counts to on paper, not an index into the grid.
        assertEquals(listOf("a" to 1, "c" to 3, "d" to 4), cards.map { it.first.id to it.second })
    }

    @Test
    fun `excluding the first page leaves the rest at their own numbers`() {
        val cards = LinkPickerModel.pageCards(pages("a", "b", "c"), excludePageId = "a")
        assertEquals(listOf("b" to 2, "c" to 3), cards.map { it.first.id to it.second })
    }

    @Test
    fun `an exclusion that matches nothing drops nothing`() {
        val cards = LinkPickerModel.pageCards(pages("a", "b"), excludePageId = "zzz")
        assertEquals(2, cards.size)
    }

    @Test
    fun `a single-page notebook excluding its only page is empty`() {
        assertEquals(emptyList<Pair<PickerPage, Int>>(), LinkPickerModel.pageCards(pages("a"), "a"))
    }

    @Test
    fun `no pages is no cards`() {
        assertEquals(emptyList<Pair<PickerPage, Int>>(), LinkPickerModel.pageCards(emptyList(), "a"))
    }

    // ── gridPageOf ───────────────────────────────────────────────────────────

    @Test
    fun `the grid page holding an item is its index over the page size`() {
        assertEquals(0, LinkPickerModel.gridPageOf(0, 12))
        assertEquals(0, LinkPickerModel.gridPageOf(11, 12))
        assertEquals(1, LinkPickerModel.gridPageOf(12, 12))
        assertEquals(2, LinkPickerModel.gridPageOf(30, 12))
    }

    @Test
    fun `nothing selected or an unusable page size stays on the first page`() {
        assertEquals(0, LinkPickerModel.gridPageOf(-1, 12))
        assertEquals(0, LinkPickerModel.gridPageOf(5, 0))
    }

    // ── composeOk ────────────────────────────────────────────────────────────

    @Test
    fun `this notebook composes a page link carrying no notebook id`() {
        val payload = LinkPickerModel.composeOk(
            PickMode.THIS_NOTEBOOK, LinkPayload.CHROME_UNDERLINE, current,
            selectedNotebookId = null, selectedPageId = "p3",
        )
        assertEquals("L1|1|0||p3", payload)
    }

    @Test
    fun `notebook composes a notebook link carrying no page id`() {
        val payload = LinkPickerModel.composeOk(
            PickMode.NOTEBOOK, LinkPayload.CHROME_NONE, current,
            selectedNotebookId = other, selectedPageId = null,
        )
        assertEquals("L1|0|1|$other|", payload)
    }

    @Test
    fun `notebook page composes both ids`() {
        val payload = LinkPickerModel.composeOk(
            PickMode.NOTEBOOK_PAGE, LinkPayload.CHROME_UNDERLINE, current,
            selectedNotebookId = other, selectedPageId = "p9",
        )
        assertEquals("L1|1|2|$other|p9", payload)
    }

    @Test
    fun `a stale page selection is ignored in notebook mode`() {
        // Switching modes clears the selection, but the contract must not depend on that: a page
        // id can never leak into a whole-notebook payload.
        val payload = LinkPickerModel.composeOk(
            PickMode.NOTEBOOK, LinkPayload.CHROME_UNDERLINE, current,
            selectedNotebookId = other, selectedPageId = "p9",
        )
        assertEquals("L1|1|1|$other|", payload)
    }

    @Test
    fun `nothing selected composes nothing`() {
        assertNull(
            LinkPickerModel.composeOk(PickMode.THIS_NOTEBOOK, LinkPayload.CHROME_UNDERLINE, current, null, null)
        )
        assertNull(
            LinkPickerModel.composeOk(PickMode.NOTEBOOK, LinkPayload.CHROME_UNDERLINE, current, null, null)
        )
        assertNull(
            LinkPickerModel.composeOk(PickMode.NOTEBOOK_PAGE, LinkPayload.CHROME_UNDERLINE, current, null, null)
        )
    }

    @Test
    fun `a drilled notebook with no page chosen composes nothing`() {
        assertNull(
            LinkPickerModel.composeOk(
                PickMode.NOTEBOOK_PAGE, LinkPayload.CHROME_UNDERLINE, current,
                selectedNotebookId = other, selectedPageId = null,
            )
        )
    }

    @Test
    fun `a page chosen with no notebook composes nothing`() {
        assertNull(
            LinkPickerModel.composeOk(
                PickMode.NOTEBOOK_PAGE, LinkPayload.CHROME_UNDERLINE, current,
                selectedNotebookId = null, selectedPageId = "p1",
            )
        )
    }

    @Test
    fun `the current notebook can never be the target`() {
        assertNull(
            LinkPickerModel.composeOk(
                PickMode.NOTEBOOK, LinkPayload.CHROME_UNDERLINE, current,
                selectedNotebookId = current, selectedPageId = null,
            )
        )
        assertNull(
            LinkPickerModel.composeOk(
                PickMode.NOTEBOOK_PAGE, LinkPayload.CHROME_UNDERLINE, current,
                selectedNotebookId = current, selectedPageId = "p1",
            )
        )
    }

    @Test
    fun `an id the grammar cannot carry composes nothing rather than throwing`() {
        val payload = LinkPickerModel.composeOk(
            PickMode.NOTEBOOK, LinkPayload.CHROME_UNDERLINE, current,
            selectedNotebookId = "has|separator", selectedPageId = null,
        )
        assertNull(payload)
    }

    @Test
    fun `every composed payload decodes back to what was picked`() {
        val payload = LinkPickerModel.composeOk(
            PickMode.NOTEBOOK_PAGE, LinkPayload.CHROME_NONE, current,
            selectedNotebookId = other, selectedPageId = "p4",
        )!!
        val decoded = LinkPayload.decode(payload)!!
        assertEquals(LinkPayload.CHROME_NONE, decoded.chrome)
        assertEquals(LinkPayload.KIND_NOTEBOOK_PAGE, decoded.kind)
        assertEquals(other, decoded.notebookId)
        assertEquals("p4", decoded.pageId)
        // …and re-opening the picker on it lands on the mode and style it was made with.
        assertEquals(PickMode.NOTEBOOK_PAGE, LinkPickerModel.modeFor(decoded))
        assertEquals(LinkPayload.CHROME_NONE, LinkPickerModel.chromeFor(decoded))
    }
}
