package com.symmetricalpalmtree.notesprout.ext.links

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PickerModel] — every decision the picker screen makes, without a screen: mode ↔ destination-kind
 * mapping, the Edit prefill, the hide-the-current-notebook filter, the "Page n" fallback, what OK
 * composes (and refuses to), and the selection-clearing mode switch.
 */
class PickerModelTest {

    private val nb = "11111111-2222-3333-4444-555555555555"
    private val otherNb = "99999999-8888-7777-6666-555555555555"
    private val pg = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun folder(id: String, label: String = "F") =
        PickerModel.Entry(id, ExtensionContract.CATALOG_FOLDER, label)

    private fun notebook(id: String, label: String = "N") =
        PickerModel.Entry(id, ExtensionContract.CATALOG_NOTEBOOK, label)

    private fun page(id: String, label: String = "") =
        PickerModel.Entry(id, ExtensionContract.CATALOG_PAGE, label)

    // ── Modes ────────────────────────────────────────────────────────────────

    @Test
    fun modesAreInTheRecordedOrder() {
        assertEquals(
            listOf(
                PickerModel.Mode.THIS_NOTEBOOK,
                PickerModel.Mode.NOTEBOOK,
                PickerModel.Mode.NOTEBOOK_PAGE,
            ),
            PickerModel.MODES,
        )
    }

    @Test
    fun kindOfMapsEveryMode() {
        assertEquals(ExtensionContract.DEST_PAGE, PickerModel.kindOf(PickerModel.Mode.THIS_NOTEBOOK))
        assertEquals(ExtensionContract.DEST_NOTEBOOK, PickerModel.kindOf(PickerModel.Mode.NOTEBOOK))
        assertEquals(ExtensionContract.DEST_NOTEBOOK_PAGE, PickerModel.kindOf(PickerModel.Mode.NOTEBOOK_PAGE))
    }

    @Test
    fun modeOfIsTheInverseOfKindOf() {
        for (mode in PickerModel.MODES) {
            assertEquals(mode, PickerModel.modeOf(PickerModel.kindOf(mode)))
        }
    }

    @Test
    fun modeOfIsNullForAnUnknownKind() {
        assertNull(PickerModel.modeOf(3))
        assertNull(PickerModel.modeOf(-1))
        assertNull(PickerModel.modeOf(99))
    }

    // ── Prefill ──────────────────────────────────────────────────────────────

    @Test
    fun createPrefillIsThisNotebookUnderlinedAndEmpty() {
        val p = PickerModel.prefill(null)
        assertSame(PickerModel.CREATE_PREFILL, p)
        assertEquals(PickerModel.Mode.THIS_NOTEBOOK, p.mode)
        assertEquals(ExtensionContract.LINK_CHROME_UNDERLINE, p.chrome)
        assertNull(p.selectedId)
        assertNull(p.drillNotebookId)
    }

    @Test
    fun prefillOfAPageLinkOpensThisNotebookOnThatPage() {
        val decoded = LinkPayload.decode(
            LinkPayload.encode(ExtensionContract.LINK_CHROME_NONE, ExtensionContract.DEST_PAGE, null, pg)
        )
        assertNotNull(decoded)
        val p = PickerModel.prefill(decoded)
        assertEquals(PickerModel.Mode.THIS_NOTEBOOK, p.mode)
        assertEquals(ExtensionContract.LINK_CHROME_NONE, p.chrome)
        assertEquals(pg, p.selectedId)
        assertNull(p.drillNotebookId)
    }

    @Test
    fun prefillOfANotebookLinkOpensTheBrowseAtRootWithItSelected() {
        val decoded = LinkPayload.decode(
            LinkPayload.encode(ExtensionContract.LINK_CHROME_UNDERLINE, ExtensionContract.DEST_NOTEBOOK, nb, null)
        )
        val p = PickerModel.prefill(decoded)
        assertEquals(PickerModel.Mode.NOTEBOOK, p.mode)
        assertEquals(ExtensionContract.LINK_CHROME_UNDERLINE, p.chrome)
        assertEquals(nb, p.selectedId)
        assertNull(p.drillNotebookId)
    }

    @Test
    fun prefillOfANotebookPageLinkDrillsStraightIntoThatNotebook() {
        val decoded = LinkPayload.decode(
            LinkPayload.encode(ExtensionContract.LINK_CHROME_NONE, ExtensionContract.DEST_NOTEBOOK_PAGE, nb, pg)
        )
        val p = PickerModel.prefill(decoded)
        assertEquals(PickerModel.Mode.NOTEBOOK_PAGE, p.mode)
        assertEquals(ExtensionContract.LINK_CHROME_NONE, p.chrome)
        assertEquals(pg, p.selectedId)
        assertEquals(nb, p.drillNotebookId)
    }

    @Test
    fun prefillOfAnUnknownKindFallsBackToCreate() {
        val bogus = LinkPayload.Decoded(chrome = ExtensionContract.LINK_CHROME_UNDERLINE, kind = 7, notebookId = nb, pageId = pg)
        assertSame(PickerModel.CREATE_PREFILL, PickerModel.prefill(bogus))
    }

    @Test
    fun prefillRoundTripsEveryCompositionBack() {
        for (mode in PickerModel.MODES) {
            for (chrome in intArrayOf(ExtensionContract.LINK_CHROME_NONE, ExtensionContract.LINK_CHROME_UNDERLINE)) {
                val drill = if (mode == PickerModel.Mode.NOTEBOOK_PAGE) nb else null
                val selected = if (mode == PickerModel.Mode.NOTEBOOK) nb else pg
                val composed = PickerModel.compose(mode, chrome, selected, drill)
                assertNotNull("compose $mode", composed)
                val payload = LinkPayload.encode(composed!!.chrome, composed.kind, composed.notebookId, composed.pageId)
                val back = PickerModel.prefill(LinkPayload.decode(payload))
                assertEquals(mode, back.mode)
                assertEquals(chrome, back.chrome)
                assertEquals(selected, back.selectedId)
                assertEquals(drill, back.drillNotebookId)
            }
        }
    }

    // ── The hide-the-current-notebook filter ─────────────────────────────────

    @Test
    fun browseHidesTheNotebookTheLinkLivesIn() {
        val rows = listOf(folder("f1"), notebook(nb), notebook(otherNb))
        val shown = PickerModel.browseEntries(rows, nb)
        assertEquals(listOf("f1", otherNb), shown.map { it.id })
    }

    @Test
    fun browseKeepsAFolderThatSharesTheCurrentNotebooksId() {
        // Ids never collide across kinds in practice; the filter is still kind-scoped.
        val rows = listOf(folder(nb), notebook(nb))
        val shown = PickerModel.browseEntries(rows, nb)
        assertEquals(1, shown.size)
        assertEquals(ExtensionContract.CATALOG_FOLDER, shown[0].kind)
    }

    @Test
    fun browseKeepsEverythingWhenThereIsNoCurrentNotebook() {
        val rows = listOf(folder("f1"), notebook(nb), notebook(otherNb))
        assertEquals(rows, PickerModel.browseEntries(rows, null))
        assertEquals(rows, PickerModel.browseEntries(rows, ""))
        assertEquals(rows, PickerModel.browseEntries(rows, "   "))
    }

    @Test
    fun browsePreservesTheCatalogsOrder() {
        val rows = listOf(folder("f2", "B"), folder("f1", "A"), notebook(otherNb))
        assertEquals(rows.map { it.id }, PickerModel.browseEntries(rows, nb).map { it.id })
    }

    // ── Page labels ──────────────────────────────────────────────────────────

    @Test
    fun aComposedLabelIsShownVerbatim() {
        val label = PickerModel.pageLabel(page(pg, "Page 3 — Heading"), 3) { n -> "Page $n" }
        assertEquals("Page 3 — Heading", label)
    }

    @Test
    fun aBlankLabelFallsBackToThePositionsPageN() {
        assertEquals("Page 1", PickerModel.pageLabel(page(pg, ""), 1) { n -> "Page $n" })
        assertEquals("Page 12", PickerModel.pageLabel(page(pg, "   "), 12) { n -> "Page $n" })
    }

    @Test
    fun theFallbackIsOneBasedAndUsesTheGivenPosition() {
        val seen = ArrayList<Int>()
        PickerModel.pageLabel(page(pg, ""), 7) { n -> seen.add(n); "Page $n" }
        assertEquals(listOf(7), seen)
    }

    @Test
    fun theFallbackIsNeverCalledForALabelledPage() {
        var called = false
        val label = PickerModel.pageLabel(page(pg, "Page 2"), 2) { called = true; "wrong" }
        assertTrue(!called)
        assertEquals("Page 2", label)
    }

    // ── OK ───────────────────────────────────────────────────────────────────

    @Test
    fun thisNotebookComposesAPageDestination() {
        val c = PickerModel.compose(
            PickerModel.Mode.THIS_NOTEBOOK, ExtensionContract.LINK_CHROME_UNDERLINE, pg, null,
        )
        assertNotNull(c)
        assertEquals(ExtensionContract.DEST_PAGE, c!!.kind)
        assertEquals(ExtensionContract.LINK_CHROME_UNDERLINE, c.chrome)
        assertNull(c.notebookId)
        assertEquals(pg, c.pageId)
        // And it is exactly what the codec accepts.
        assertEquals("L1|1|0||$pg", LinkPayload.encode(c.chrome, c.kind, c.notebookId, c.pageId))
    }

    @Test
    fun notebookComposesANotebookDestination() {
        val c = PickerModel.compose(
            PickerModel.Mode.NOTEBOOK, ExtensionContract.LINK_CHROME_NONE, nb, null,
        )
        assertNotNull(c)
        assertEquals(ExtensionContract.DEST_NOTEBOOK, c!!.kind)
        assertEquals(nb, c.notebookId)
        assertNull(c.pageId)
        assertEquals("L1|0|1|$nb|", LinkPayload.encode(c.chrome, c.kind, c.notebookId, c.pageId))
    }

    @Test
    fun notebookPageComposesBothIds() {
        val c = PickerModel.compose(
            PickerModel.Mode.NOTEBOOK_PAGE, ExtensionContract.LINK_CHROME_UNDERLINE, pg, nb,
        )
        assertNotNull(c)
        assertEquals(ExtensionContract.DEST_NOTEBOOK_PAGE, c!!.kind)
        assertEquals(nb, c.notebookId)
        assertEquals(pg, c.pageId)
        assertEquals("L1|1|2|$nb|$pg", LinkPayload.encode(c.chrome, c.kind, c.notebookId, c.pageId))
    }

    @Test
    fun composeIsNullWithNoSelectionInAnyMode() {
        for (mode in PickerModel.MODES) {
            assertNull("$mode null", PickerModel.compose(mode, ExtensionContract.LINK_CHROME_UNDERLINE, null, nb))
            assertNull("$mode blank", PickerModel.compose(mode, ExtensionContract.LINK_CHROME_UNDERLINE, "  ", nb))
        }
    }

    @Test
    fun notebookPageIsNullUntilANotebookIsDrilledInto() {
        assertNull(PickerModel.compose(PickerModel.Mode.NOTEBOOK_PAGE, ExtensionContract.LINK_CHROME_NONE, pg, null))
        assertNull(PickerModel.compose(PickerModel.Mode.NOTEBOOK_PAGE, ExtensionContract.LINK_CHROME_NONE, pg, ""))
    }

    @Test
    fun theOtherTwoModesIgnoreADrilledNotebook() {
        val a = PickerModel.compose(PickerModel.Mode.THIS_NOTEBOOK, ExtensionContract.LINK_CHROME_NONE, pg, nb)
        assertNull(a!!.notebookId)
        val b = PickerModel.compose(PickerModel.Mode.NOTEBOOK, ExtensionContract.LINK_CHROME_NONE, nb, otherNb)
        assertEquals(nb, b!!.notebookId)
        assertNull(b.pageId)
    }

    @Test
    fun composeCarriesTheChromeThrough() {
        for (chrome in intArrayOf(ExtensionContract.LINK_CHROME_NONE, ExtensionContract.LINK_CHROME_UNDERLINE)) {
            assertEquals(
                chrome,
                PickerModel.compose(PickerModel.Mode.THIS_NOTEBOOK, chrome, pg, null)!!.chrome,
            )
        }
    }

    // ── Mode switching ───────────────────────────────────────────────────────

    @Test
    fun switchingModesClearsTheTargetAndTheBrowsePositionButKeepsTheChrome() {
        for (mode in PickerModel.MODES) {
            for (chrome in intArrayOf(ExtensionContract.LINK_CHROME_NONE, ExtensionContract.LINK_CHROME_UNDERLINE)) {
                val after = PickerModel.afterModeSwitch(mode, chrome)
                assertEquals(mode, after.mode)
                assertEquals(chrome, after.chrome)
                assertNull(after.selectedId)
                assertNull(after.drillNotebookId)
            }
        }
    }

    @Test
    fun aClearedSelectionMakesOkRefuse() {
        val after = PickerModel.afterModeSwitch(PickerModel.Mode.NOTEBOOK, ExtensionContract.LINK_CHROME_UNDERLINE)
        assertNull(PickerModel.compose(after.mode, after.chrome, after.selectedId, after.drillNotebookId))
    }

    // ── pathTo reply splitting (the Edit-prefill browse seed) ────────────────

    @Test
    fun pathWithNoFoldersIsJustTheNotebook() {
        val path = PickerModel.pathParts(listOf(notebook(nb, "Recipes")))!!
        assertEquals(emptyList<Pair<String, String>>(), path.folders)
        assertEquals(nb, path.notebookId)
        assertEquals("Recipes", path.notebookName)
    }

    @Test
    fun pathWithOneFolderSplitsRootFirst() {
        val path = PickerModel.pathParts(listOf(folder("f1", "Work"), notebook(nb, "Notes")))!!
        assertEquals(listOf("f1" to "Work"), path.folders)
        assertEquals(nb, path.notebookId)
        assertEquals("Notes", path.notebookName)
    }

    @Test
    fun pathWithTwoFoldersKeepsTheirOrder() {
        val path = PickerModel.pathParts(
            listOf(folder("f1", "Work"), folder("f2", "2026"), notebook(nb, "Plans")),
        )!!
        assertEquals(listOf("f1" to "Work", "f2" to "2026"), path.folders)
        assertEquals(nb, path.notebookId)
    }

    @Test
    fun emptyPathIsNull() {
        assertNull(PickerModel.pathParts(emptyList()))
    }

    @Test
    fun pathWithoutANotebookTailIsNull() {
        assertNull(PickerModel.pathParts(listOf(folder("f1"), folder("f2"))))
    }

    @Test
    fun pathWithAFolderAfterTheNotebookIsNull() {
        assertNull(PickerModel.pathParts(listOf(notebook(nb), folder("f1"))))
    }

    @Test
    fun pathWithAStrayKindBeforeTheNotebookIsNull() {
        assertNull(PickerModel.pathParts(listOf(page(pg), notebook(nb))))
        assertNull(PickerModel.pathParts(listOf(notebook(otherNb), notebook(nb))))
    }

    @Test
    fun pathOfASingleNonNotebookEntryIsNull() {
        assertNull(PickerModel.pathParts(listOf(folder("f1"))))
        assertNull(PickerModel.pathParts(listOf(page(pg))))
    }
}
