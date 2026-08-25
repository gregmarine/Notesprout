package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.TemplateDigest
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The re-paper decision (arc 12): reuse the notebook's existing paper or mint another megabyte of
 * it, and which of the four kinds to tick in the picker. Both are pure reads over blob-free
 * digests, which is the whole reason they live outside the session.
 */
class PageTemplateTest {

    private fun digest(
        id: String,
        kind: TemplateKind,
        width: Int = 1404,
        height: Int = 1872,
        blobLength: Int? = 40_000,
    ) = TemplateDigest(id, kind.name, width.toFloat(), height.toFloat(), blobLength)

    private val lined = digest("t-lined", TemplateKind.LINED)
    private val grid = digest("t-grid", TemplateKind.GRID)

    // ── reusableId ──────────────────────────────────────────────────────────

    @Test
    fun `reuses the row that already holds this kind at this size`() {
        assertEquals("t-grid", PageTemplate.reusableId(listOf(lined, grid), TemplateKind.GRID, 1404, 1872))
    }

    @Test
    fun `mints when the notebook has no row of that kind`() {
        assertNull(PageTemplate.reusableId(listOf(lined, grid), TemplateKind.DOTTED, 1404, 1872))
    }

    @Test
    fun `mints when the only row of that kind is a different page size`() {
        // A page pasted in from a bigger device keeps its authored size; ruling it with the
        // notebook's own template would stop short of its edge.
        assertNull(PageTemplate.reusableId(listOf(lined), TemplateKind.LINED, 1920, 2560))
    }

    @Test
    fun `blank never reuses and never mints`() {
        val blankRow = digest("t-blank", TemplateKind.BLANK)
        assertNull(PageTemplate.reusableId(listOf(blankRow, lined), TemplateKind.BLANK, 1404, 1872))
    }

    @Test
    fun `refuses a row with no pixels`() {
        // It names paper it cannot draw: re-pointing at it would blank the page while the sheet
        // says Lined.
        assertNull(PageTemplate.reusableId(listOf(digest("t-empty", TemplateKind.LINED, blobLength = 0)),
            TemplateKind.LINED, 1404, 1872))
        assertNull(PageTemplate.reusableId(listOf(digest("t-null", TemplateKind.LINED, blobLength = null)),
            TemplateKind.LINED, 1404, 1872))
    }

    @Test
    fun `there and back is free`() {
        // Lined → Grid → Lined: nothing ever soft-deletes a template, so the second change finds
        // the original row instead of stacking a second copy of the same pixels.
        val digests = listOf(lined, grid)
        assertEquals("t-grid", PageTemplate.reusableId(digests, TemplateKind.GRID, 1404, 1872))
        assertEquals("t-lined", PageTemplate.reusableId(digests, TemplateKind.LINED, 1404, 1872))
    }

    @Test
    fun `the page's own row wins among identical twins`() {
        // Two Lined rows at one size is possible: a page pasted from a notebook whose panel had a
        // different density, so the paste's content dedupe found no match. Picking the kind the
        // sheet already ticked must stay a true no-op, not a re-point onto the twin plus a
        // pointless undo step.
        val twin = digest("t-lined-twin", TemplateKind.LINED)
        assertEquals(
            "t-lined-twin",
            PageTemplate.reusableId(listOf(lined, twin), TemplateKind.LINED, 1404, 1872, prefer = "t-lined-twin"),
        )
        // A prefer that is not among the matches is simply ignored.
        assertEquals(
            "t-lined",
            PageTemplate.reusableId(listOf(lined, twin), TemplateKind.LINED, 1404, 1872, prefer = "t-grid"),
        )
    }

    @Test
    fun `an empty notebook mints`() {
        assertNull(PageTemplate.reusableId(emptyList(), TemplateKind.LINED, 1404, 1872))
    }

    // ── kindOf ──────────────────────────────────────────────────────────────

    @Test
    fun `an empty template id is blank paper, not an unknown`() {
        assertEquals(TemplateKind.BLANK, PageTemplate.kindOf(listOf(lined), ""))
    }

    @Test
    fun `names the kind a page is pointing at`() {
        assertEquals(TemplateKind.LINED, PageTemplate.kindOf(listOf(lined, grid), "t-lined"))
    }

    @Test
    fun `a vanished row is unknown, not blank`() {
        assertNull(PageTemplate.kindOf(listOf(lined), "t-gone"))
    }

    @Test
    fun `a template this build does not know is unknown, not blank`() {
        // Family-compatible files can carry paper we cannot name; ticking Blank would claim the
        // page is empty while a ruled sheet is on the glass.
        val foreign = TemplateDigest("t-foreign", "CORNELL", 1404f, 1872f, 40_000)
        assertNull(PageTemplate.kindOf(listOf(foreign), "t-foreign"))
    }

    @Test
    fun `a row with a null text is unknown`() {
        val untyped = TemplateDigest("t-untyped", null, 1404f, 1872f, 40_000)
        assertNull(PageTemplate.kindOf(listOf(untyped), "t-untyped"))
    }
}
