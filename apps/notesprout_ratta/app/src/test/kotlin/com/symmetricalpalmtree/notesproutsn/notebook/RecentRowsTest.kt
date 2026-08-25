package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook Recents panel's arithmetic (arc 10). Same rule as the library shelf — **stored order
 * wins** — plus the one clause that is this screen's own: the notebook you are in is never listed.
 */
class RecentRowsTest {

    private fun entry(id: String, t: Long = 0L) = RecentEntry(id, t)

    // ── select ──────────────────────────────────────────────────────────────

    @Test
    fun `stored order is kept, not sorted`() {
        // Deliberately anti-alphabetical and anti-chronological: neither may reorder this.
        val entries = listOf(entry("zeta", 300), entry("alpha", 100), entry("mid", 200))
        val ids = RecentRows.select(entries, setOf("zeta", "alpha", "mid"), currentId = "none")
        assertEquals(listOf("zeta", "alpha", "mid"), ids)
    }

    @Test
    fun `the open notebook is never offered as somewhere to go`() {
        val entries = listOf(entry("here"), entry("a"), entry("b"))
        val ids = RecentRows.select(entries, setOf("here", "a", "b"), currentId = "here")
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `dead ids are dropped and the rest keep their order`() {
        val entries = listOf(entry("a"), entry("gone"), entry("b"), entry("also-gone"), entry("c"))
        val ids = RecentRows.select(entries, setOf("a", "b", "c"), currentId = "none")
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `a duplicated id shows once, at its newest position`() {
        val entries = listOf(entry("a"), entry("b"), entry("a"))
        val ids = RecentRows.select(entries, setOf("a", "b"), currentId = "none")
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `a duplicate of the current notebook is dropped every time`() {
        val entries = listOf(entry("here"), entry("a"), entry("here"))
        val ids = RecentRows.select(entries, setOf("here", "a"), currentId = "here")
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun `nothing but the current notebook is an empty list, not a crash`() {
        val ids = RecentRows.select(listOf(entry("here")), setOf("here"), currentId = "here")
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `alive ids that were never visited are not invented`() {
        val ids = RecentRows.select(listOf(entry("a")), setOf("a", "b", "c"), currentId = "none")
        assertEquals(listOf("a"), ids)
    }

    // ── breadcrumb ──────────────────────────────────────────────────────────

    @Test
    fun `a notebook at the root is just the root label`() {
        assertEquals("Notebooks", RecentRows.breadcrumb("Notebooks", emptyList()))
    }

    @Test
    fun `folders read root-first, separated like the library's crumbs`() {
        assertEquals(
            "Notebooks › Work › 2026",
            RecentRows.breadcrumb("Notebooks", listOf("Work", "2026")),
        )
    }

    // ── sidebar width ───────────────────────────────────────────────────────

    @Test
    fun `the panel is half the window, narrower than the Contents' 60 percent`() {
        assertEquals(702, RecentRows.sidebarWidthPx(1404))   // Nomad
        assertEquals(960, RecentRows.sidebarWidthPx(1920))   // Manta
        assertTrue(RecentRows.SIDEBAR_WIDTH_FRACTION < ContentsLayout.SIDEBAR_WIDTH_FRACTION)
    }

    @Test
    fun `an odd width rounds rather than truncates`() {
        assertEquals(51, RecentRows.sidebarWidthPx(101))
    }

    // ── itemsPerPage ────────────────────────────────────────────────────────

    @Test
    fun `rows per page is the whole number that fits`() {
        assertEquals(9, RecentRows.itemsPerPage(bodyHeightPx = 900, rowHeightPx = 100))
        assertEquals(9, RecentRows.itemsPerPage(bodyHeightPx = 999, rowHeightPx = 100))
    }

    @Test
    fun `a body too short for one row still shows one`() {
        assertEquals(1, RecentRows.itemsPerPage(bodyHeightPx = 40, rowHeightPx = 100))
        assertEquals(1, RecentRows.itemsPerPage(bodyHeightPx = 0, rowHeightPx = 100))
    }

    @Test
    fun `an unmeasured row never divides by zero`() {
        assertEquals(1, RecentRows.itemsPerPage(bodyHeightPx = 900, rowHeightPx = 0))
        assertEquals(1, RecentRows.itemsPerPage(bodyHeightPx = 900, rowHeightPx = -5))
    }
}
