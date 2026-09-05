package com.symmetricalpalmtree.notesproutsn.cloud

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud browser's rules (arc 25 / V3): the crumb, the paging, the two name lookups and the
 * New-folder answer. Everything the browser decides that a Binder call is not.
 */
class CloudBrowserRulesTest {

    private fun folder(name: String) = CloudEntry("id-$name", name, true, 0L, 0L)
    private fun file(name: String, size: Long = 10L) = CloudEntry("id-$name", name, false, size, 0L)

    // ── The crumb ────────────────────────────────────────────────────────────

    @Test
    fun `the crumb is always headed by the provider`() {
        assertEquals(
            "Google Drive › Exports › Trips",
            CloudBrowserRules.crumb("Google Drive", listOf("Exports", "Trips"), " › "),
        )
        assertEquals("Google Drive", CloudBrowserRules.crumb("Google Drive", emptyList(), " › "))
    }

    // ── Rows and paging ──────────────────────────────────────────────────────

    @Test
    fun `New folder is the first row, and only where a folder is being picked`() {
        val entries = listOf(folder("Trips"), file("a.soil"))
        val withNew = CloudBrowserRules.rows(entries, offersNewFolder = true)
        assertEquals(3, withNew.size)
        assertTrue(withNew[0] is CloudBrowserRules.Row.NewFolder)
        assertSame(entries[0], (withNew[1] as CloudBrowserRules.Row.Entry).entry)

        val without = CloudBrowserRules.rows(entries, offersNewFolder = false)
        assertEquals(2, without.size)
        assertTrue(without[0] is CloudBrowserRules.Row.Entry)
    }

    @Test
    fun `an empty folder still has one page to be empty on`() {
        assertEquals(1, CloudBrowserRules.pageCount(0, 5))
        assertEquals(1, CloudBrowserRules.pageCount(5, 5))
        assertEquals(2, CloudBrowserRules.pageCount(6, 5))
        assertEquals(1, CloudBrowserRules.pageCount(3, 0))
    }

    @Test
    fun `a page is its own slice, and a page past the end is empty rather than a crash`() {
        val rows = CloudBrowserRules.rows((1..7).map { folder("f$it") }, offersNewFolder = false)
        assertEquals(3, CloudBrowserRules.page(rows, 0, 3).size)
        assertEquals(1, CloudBrowserRules.page(rows, 2, 3).size)
        assertTrue(CloudBrowserRules.page(rows, 9, 3).isEmpty())
        assertTrue(CloudBrowserRules.page(rows, 0, 0).isEmpty())
        assertEquals(
            "f4",
            (CloudBrowserRules.page(rows, 1, 3)[0] as CloudBrowserRules.Row.Entry).entry.name,
        )
    }

    @Test
    fun `rows per page comes from the measured body and is never zero`() {
        assertEquals(5, CloudBrowserRules.itemsPerPage(bodyHeightPx = 345, density = 1f))
        assertEquals(1, CloudBrowserRules.itemsPerPage(bodyHeightPx = 0, density = 1f))
        assertEquals(1, CloudBrowserRules.itemsPerPage(bodyHeightPx = 1000, density = 0f))
    }

    // ── Up ───────────────────────────────────────────────────────────────────

    @Test
    fun `Up stops at the folder the browser was opened on`() {
        assertFalse(CloudBrowserRules.canGoUp(depth = 1, baseDepth = 1))
        assertTrue(CloudBrowserRules.canGoUp(depth = 2, baseDepth = 1))
    }

    // ── The two lookups ──────────────────────────────────────────────────────

    @Test
    fun `the lookups tell folders from files and match exactly`() {
        val listing = listOf(folder("Notes"), file("Notes"), file("Trip.soil"))
        assertTrue(CloudBrowserRules.folderNamed(listing, "Notes")!!.isFolder)
        assertFalse(CloudBrowserRules.fileNamed(listing, "Notes")!!.isFolder)
        assertEquals("Trip.soil", CloudBrowserRules.fileNamed(listing, "Trip.soil")?.name)
        // Exactly — an upload resolves the same way, so a looser match here would warn about
        // replacing a file the upload would in fact leave alone.
        assertNull(CloudBrowserRules.fileNamed(listing, "trip.soil"))
        assertNull(CloudBrowserRules.folderNamed(listing, "Trip.soil"))
    }

    // ── New folder ───────────────────────────────────────────────────────────

    @Test
    fun `a name already listed as a folder is entered, not created again`() {
        assertEquals(
            CloudBrowserRules.NewFolderOutcome.ENTER_EXISTING,
            CloudBrowserRules.newFolderOutcome("Trips", listOf(folder("Trips")), depth = 1),
        )
    }

    @Test
    fun `a same-named file is not in the way of a folder`() {
        assertEquals(
            CloudBrowserRules.NewFolderOutcome.CREATE,
            CloudBrowserRules.newFolderOutcome("Trips", listOf(file("Trips")), depth = 1),
        )
    }

    @Test
    fun `a name the seam cannot carry is refused before any bind`() {
        for (bad in listOf("", " Trips", "Trips ", "a/b", "a\\b", ".", "..", "x".repeat(256))) {
            assertEquals(
                "expected refusal for '$bad'",
                CloudBrowserRules.NewFolderOutcome.REFUSED,
                CloudBrowserRules.newFolderOutcome(bad, emptyList(), depth = 1),
            )
        }
    }

    @Test
    fun `the depth cap is the seam's, and it is refused here rather than at the call`() {
        assertEquals(
            CloudBrowserRules.NewFolderOutcome.CREATE,
            CloudBrowserRules.newFolderOutcome(
                "Trips", emptyList(), depth = CloudContract.MAX_PATH_DEPTH - 1,
            ),
        )
        assertEquals(
            CloudBrowserRules.NewFolderOutcome.REFUSED,
            CloudBrowserRules.newFolderOutcome("Trips", emptyList(), depth = CloudContract.MAX_PATH_DEPTH),
        )
    }

    // ── What a file row does (arc 25 / V5) ───────────────────────────────────

    @Test
    fun `a file row answers a tap only where a file is what is being picked`() {
        assertTrue(CloudBrowserRules.fileTappable(picksFiles = true))
        assertFalse(CloudBrowserRules.fileTappable(picksFiles = false))
    }
}
