package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.TemplateDigest
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The re-paper decision (arc 12, widened to the template library in arc 13 / G3): reuse the
 * notebook's existing paper or mint another megabyte of it, and which card the browser ticks. Both
 * are pure reads over blob-free digests, which is the whole reason they live outside the session.
 *
 * Since G3 the currency is the **token**, not the kind — the built-ins' tokens are their old names,
 * so every arc-12 case below still reads the same, and `IMG#…` joins them as one more string.
 */
class PageTemplateTest {

    private fun digest(
        id: String,
        token: String,
        width: Int = 1404,
        height: Int = 1872,
        blobLength: Int? = 40_000,
    ) = TemplateDigest(id, token, width.toFloat(), height.toFloat(), blobLength)

    private fun digest(id: String, kind: TemplateKind, width: Int = 1404, height: Int = 1872, blobLength: Int? = 40_000) =
        digest(id, TemplateToken.of(kind), width, height, blobLength)

    private val lined = digest("t-lined", TemplateKind.LINED)
    private val grid = digest("t-grid", TemplateKind.GRID)

    private fun reusable(digests: List<TemplateDigest>, kind: TemplateKind, w: Int = 1404, h: Int = 1872, prefer: String? = null) =
        PageTemplate.reusableId(digests, TemplateToken.of(kind), w, h, prefer)

    // ── reusableId ──────────────────────────────────────────────────────────

    @Test
    fun `reuses the row that already holds this kind at this size`() {
        assertEquals("t-grid", reusable(listOf(lined, grid), TemplateKind.GRID))
    }

    @Test
    fun `mints when the notebook has no row of that kind`() {
        assertNull(reusable(listOf(lined, grid), TemplateKind.DOTTED))
    }

    @Test
    fun `mints when the only row of that kind is a different page size`() {
        // A page pasted in from a bigger device keeps its authored size; ruling it with the
        // notebook's own template would stop short of its edge.
        assertNull(reusable(listOf(lined), TemplateKind.LINED, w = 1920, h = 2560))
    }

    @Test
    fun `blank never reuses and never mints`() {
        val blankRow = digest("t-blank", TemplateKind.BLANK)
        assertNull(reusable(listOf(blankRow, lined), TemplateKind.BLANK))
    }

    @Test
    fun `refuses a row with no pixels`() {
        // It names paper it cannot draw: re-pointing at it would blank the page while the browser
        // ticks Lined.
        assertNull(reusable(listOf(digest("t-empty", TemplateKind.LINED, blobLength = 0)), TemplateKind.LINED))
        assertNull(reusable(listOf(digest("t-null", TemplateKind.LINED, blobLength = null)), TemplateKind.LINED))
    }

    @Test
    fun `there and back is free`() {
        // Lined → Grid → Lined: nothing ever soft-deletes a template, so the second change finds
        // the original row instead of stacking a second copy of the same pixels.
        val digests = listOf(lined, grid)
        assertEquals("t-grid", reusable(digests, TemplateKind.GRID))
        assertEquals("t-lined", reusable(digests, TemplateKind.LINED))
    }

    @Test
    fun `the page's own row wins among identical twins`() {
        // Two Lined rows at one size is possible: a page pasted from a notebook whose panel had a
        // different density, so the paste's content dedupe found no match. Picking the card the
        // browser already ticked must stay a true no-op, not a re-point onto the twin plus a
        // pointless undo step.
        val twin = digest("t-lined-twin", TemplateKind.LINED)
        assertEquals("t-lined-twin", reusable(listOf(lined, twin), TemplateKind.LINED, prefer = "t-lined-twin"))
        // A prefer that is not among the matches is simply ignored.
        assertEquals("t-lined", reusable(listOf(lined, twin), TemplateKind.LINED, prefer = "t-grid"))
    }

    @Test
    fun `an empty notebook mints`() {
        assertNull(reusable(emptyList(), TemplateKind.LINED))
    }

    // ── reusableId, imported pictures (G3) ──────────────────────────────────

    @Test
    fun `an imported picture reuses by its own token`() {
        val token = TemplateToken.ofImage(byteArrayOf(1, 2, 3), fit = 0)
        val row = digest("t-img", token)
        assertEquals("t-img", PageTemplate.reusableId(listOf(lined, row), token, 1404, 1872))
    }

    @Test
    fun `two different pictures never share a row`() {
        val a = TemplateToken.ofImage(byteArrayOf(1, 2, 3), fit = 0)
        val b = TemplateToken.ofImage(byteArrayOf(4, 5, 6), fit = 0)
        assertNull(PageTemplate.reusableId(listOf(digest("t-a", a)), b, 1404, 1872))
    }

    @Test
    fun `the same picture at two fits is two papers`() {
        // Fit is what turns stored bytes into page pixels, so it is part of what the row IS. Without
        // this the second page would silently get the first page's fit.
        val fitted = TemplateToken.ofImage(byteArrayOf(9, 9, 9), fit = 0)
        val stretched = TemplateToken.ofImage(byteArrayOf(9, 9, 9), fit = 1)
        assertNull(PageTemplate.reusableId(listOf(digest("t-fitted", fitted)), stretched, 1404, 1872))
    }

    // ── tokenOf ─────────────────────────────────────────────────────────────

    @Test
    fun `an empty template id is blank paper, not an unknown`() {
        assertEquals("", PageTemplate.tokenOf(listOf(lined), ""))
    }

    @Test
    fun `names the token a page is pointing at`() {
        assertEquals("LINED", PageTemplate.tokenOf(listOf(lined, grid), "t-lined"))
    }

    @Test
    fun `a vanished row is unknown, not blank`() {
        assertNull(PageTemplate.tokenOf(listOf(lined), "t-gone"))
    }

    @Test
    fun `a template this build does not know comes back as it stands`() {
        // Family-compatible files can carry paper we cannot name. It is reported verbatim and simply
        // matches no card — ticking Blank would claim the page is empty while a ruled sheet is on
        // the glass.
        val foreign = TemplateDigest("t-foreign", "CORNELL", 1404f, 1872f, 40_000)
        assertEquals("CORNELL", PageTemplate.tokenOf(listOf(foreign), "t-foreign"))
        assertNull(TemplateToken.kindOf("CORNELL"))
    }

    @Test
    fun `a row with a null text is unknown`() {
        val untyped = TemplateDigest("t-untyped", null, 1404f, 1872f, 40_000)
        assertNull(PageTemplate.tokenOf(listOf(untyped), "t-untyped"))
    }
}
