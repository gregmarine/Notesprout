package com.symmetricalpalmtree.notesproutsn.data

import com.symmetricalpalmtree.notesproutsn.data.template.DensityAxis
import com.symmetricalpalmtree.notesproutsn.data.template.DensityMode
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateGeometry
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TemplateGeometry.plan] — a spec resolved onto a page (arc 13 / G2).
 *
 * The first group is the one that must never go red: **a stock spec resolves to exactly what the
 * four fixed kinds resolved to before specs existed**, bit for bit. Existing notebooks and new
 * ones have to draw the same paper, and `.soil` template reuse across old and new files rests on
 * it. It is pinned against the legacy functions *and* against hardcoded numbers, so a change that
 * moved both together would still be caught.
 */
class TemplatePlanTest {

    private val nomadW = 1404
    private val nomadH = 1872
    private val dpi = 300f

    // ── Stock output is unchanged ────────────────────────────────────────────

    @Test
    fun stockLinedResolvesExactlyAsTheLegacyGeometryDid() {
        val plan = TemplateGeometry.plan(TemplateSpec.stock(TemplateKind.LINED), nomadW, nomadH, dpi)
        val legacy = TemplateGeometry.linePositions(nomadH, TemplateGeometry.spacingPx(dpi))
        assertEquals(legacy, plan.ys)
        assertTrue(plan.xs.isEmpty())
        assertEquals(0f, plan.left, 0f)
        assertEquals(nomadW.toFloat(), plan.right, 0f)
        assertEquals(TemplateGeometry.lineWidthPx(dpi), plan.lineWidthPx, 0f)
        assertEquals(0, plan.grey)
        assertNull(plan.marginRuleX)
    }

    @Test
    fun stockGridAndDottedResolveExactlyAsTheLegacyGeometryDid() {
        val spacing = TemplateGeometry.spacingPx(dpi)
        for (kind in listOf(TemplateKind.GRID, TemplateKind.DOTTED)) {
            val plan = TemplateGeometry.plan(TemplateSpec.stock(kind), nomadW, nomadH, dpi)
            assertEquals(TemplateGeometry.gridPositionsY(nomadH, spacing), plan.ys)
            assertEquals(TemplateGeometry.gridPositionsX(nomadW, spacing), plan.xs)
            assertEquals(TemplateGeometry.dotRadiusPx(dpi), plan.dotRadiusPx, 0f)
        }
    }

    /** The numbers themselves, so "both sides changed together" cannot pass. */
    @Test
    fun stockNumbersArePinnedNotJustCrossChecked() {
        val plan = TemplateGeometry.plan(TemplateSpec.stock(TemplateKind.LINED), nomadW, nomadH, dpi)
        assertEquals(94.488f, TemplateGeometry.spacingPx(dpi), 0.001f)
        assertEquals(1.875f, plan.lineWidthPx, 0f)
        assertEquals(3.75f, TemplateGeometry.plan(
            TemplateSpec.stock(TemplateKind.DOTTED), nomadW, nomadH, dpi
        ).dotRadiusPx, 0f)
        // First rule two spacings down, 18 of them on a Nomad page.
        assertEquals(188.976f, plan.ys.first(), 0.001f)
        assertEquals(18, plan.ys.size)
    }

    /** The mm constants must reproduce the old `px-at-mdpi × dpi / 160` at every real density. */
    @Test
    fun featureSizesInMillimetresMatchTheMdpiAuthoredPixels() {
        for (d in listOf(160f, 240f, 300f, 320f, 400f)) {
            assertEquals(d / 160f, TemplateGeometry.mmToPx(TemplateSpec.STOCK_THICKNESS_MM, d), 0f)
            assertEquals(2f * d / 160f, TemplateGeometry.mmToPx(TemplateSpec.STOCK_DOT_MM / 2f, d), 0f)
        }
    }

    // ── Insets ───────────────────────────────────────────────────────────────

    @Test
    fun insetsAreBlankSpaceAndThePatternStopsShortOfThem() {
        val spec = TemplateSpec.stock(TemplateKind.GRID)
            .copy(topMm = 10f, bottomMm = 5f, leftMm = 20f, rightMm = 20f)
        val plan = TemplateGeometry.plan(spec, nomadW, nomadH, dpi)
        val tenMm = TemplateGeometry.mmToPx(10f, dpi)
        assertEquals(tenMm, plan.top, 0.001f)
        assertEquals(nomadH - TemplateGeometry.mmToPx(5f, dpi), plan.bottom, 0.001f)
        assertEquals(TemplateGeometry.mmToPx(20f, dpi), plan.left, 0.001f)
        // The first feature is one spacing inside the content, not one spacing inside the page.
        assertEquals(plan.left + TemplateGeometry.spacingPx(dpi), plan.xs.first(), 0.001f)
        assertTrue(plan.xs.last() < plan.right)
        assertTrue(plan.ys.first() > plan.top)
        assertTrue(plan.ys.last() < plan.bottom)
    }

    @Test
    fun insetsThatWouldSwallowThePageAreDroppedAsAPair() {
        // 60 mm each side of a 200 px card at 300 dpi leaves nothing to rule.
        val spec = TemplateSpec.stock(TemplateKind.LINED).copy(leftMm = 60f, rightMm = 60f)
        val plan = TemplateGeometry.plan(spec, 200, 400, dpi)
        assertEquals(0f, plan.left, 0f)
        assertEquals(200f, plan.right, 0f)
    }

    @Test
    fun linedRulesSpanTheContentNotThePage() {
        val spec = TemplateSpec.stock(TemplateKind.LINED).copy(leftMm = 15f, rightMm = 5f)
        val plan = TemplateGeometry.plan(spec, nomadW, nomadH, dpi)
        assertEquals(TemplateGeometry.mmToPx(15f, dpi), plan.left, 0.001f)
        assertEquals(nomadW - TemplateGeometry.mmToPx(5f, dpi), plan.right, 0.001f)
    }

    // ── Margin rule ──────────────────────────────────────────────────────────

    @Test
    fun theMarginRuleSitsAtTheLeftInset() {
        val spec = TemplateSpec.stock(TemplateKind.LINED).copy(marginRule = true, leftMm = 20f)
        val plan = TemplateGeometry.plan(spec, nomadW, nomadH, dpi)
        assertEquals(plan.left, plan.marginRuleX!!, 0f)
    }

    @Test
    fun theMarginRuleIsNudgedInWhenThereIsNoInsetToSitOn() {
        val spec = TemplateSpec.stock(TemplateKind.LINED).copy(marginRule = true)
        val plan = TemplateGeometry.plan(spec, nomadW, nomadH, dpi)
        // At x = 0 half the stroke would fall off the page and the rule would look thin.
        assertEquals(plan.lineWidthPx / 2f, plan.marginRuleX!!, 0f)
    }

    @Test
    fun noMarginRuleMeansNoPosition() {
        assertNull(TemplateGeometry.plan(TemplateSpec.stock(TemplateKind.GRID), nomadW, nomadH, dpi).marginRuleX)
        assertNotNull(
            TemplateGeometry.plan(
                TemplateSpec.stock(TemplateKind.GRID).copy(marginRule = true), nomadW, nomadH, dpi
            ).marginRuleX
        )
    }

    // ── Spacing ↔ count ──────────────────────────────────────────────────────

    @Test
    fun aCountRoundTripsThroughTheSpacingItWorksOutTo() {
        for (kind in TemplateKind.entries.filter { it != TemplateKind.BLANK }) {
            for (n in listOf(1, 2, 5, 12, 27, 60, 120)) {
                val spec = TemplateSpec.stock(kind).copy(
                    rows = DensityAxis(DensityMode.COUNT, count = n),
                    cols = DensityAxis(DensityMode.COUNT, count = n),
                )
                val (rowCount, colCount) = TemplateGeometry.countsFor(spec, nomadW, nomadH, dpi)
                assertEquals("$kind rows at n=$n", n, rowCount)
                if (kind != TemplateKind.LINED) assertEquals("$kind cols at n=$n", n, colCount)
            }
        }
    }

    @Test
    fun aSpacingRoundTripsThroughTheCountItProduces() {
        val spec = TemplateSpec.stock(TemplateKind.GRID)
        val (rowCount, colCount) = TemplateGeometry.countsFor(spec, nomadW, nomadH, dpi)
        val asCount = spec.copy(
            rows = DensityAxis(DensityMode.COUNT, count = rowCount),
            cols = DensityAxis(DensityMode.COUNT, count = colCount),
        )
        assertEquals(rowCount to colCount, TemplateGeometry.countsFor(asCount, nomadW, nomadH, dpi))
        // And the spacing it reports back is the 8 mm it came from, near enough to draw the same.
        val mm = TemplateGeometry.spacingMmFor(
            rowCount,
            TemplateGeometry.contentExtentPx(spec, nomadW, nomadH, dpi, vertical = true),
            TemplateGeometry.leadingFor(TemplateKind.GRID, vertical = true),
            dpi,
        )
        assertEquals(TemplateGeometry.SPACING_MM, mm, 0.5f)
    }

    @Test
    fun linedCountsCarryTheExtraTopMargin() {
        // Lined starts one spacing lower than a grid, so the same page holds one fewer rule.
        val lined = TemplateGeometry.countsFor(TemplateSpec.stock(TemplateKind.LINED), nomadW, nomadH, dpi).first
        val grid = TemplateGeometry.countsFor(TemplateSpec.stock(TemplateKind.GRID), nomadW, nomadH, dpi).first
        assertEquals(grid - 1, lined)
        assertEquals(2, TemplateGeometry.leadingFor(TemplateKind.LINED, vertical = true))
        assertEquals(1, TemplateGeometry.leadingFor(TemplateKind.LINED, vertical = false))
        assertEquals(1, TemplateGeometry.leadingFor(TemplateKind.GRID, vertical = true))
    }

    // ── Bounds ───────────────────────────────────────────────────────────────

    @Test
    fun aPatternFinerThanTheFloorIsClampedRatherThanUnbounded() {
        val spec = TemplateSpec.stock(TemplateKind.GRID)
            .copy(rows = DensityAxis(DensityMode.COUNT, count = TemplateSpec.MAX_COUNT))
        val plan = TemplateGeometry.plan(spec, 40, 40, dpi)
        assertTrue(plan.ys.size <= TemplateGeometry.MAX_STEPS)
        // 400 rows across 40 px would be a tenth of a pixel apart; the floor stops it.
        assertTrue(plan.ys.size < TemplateSpec.MAX_COUNT)
    }

    @Test
    fun aScaledRenderIsTheSamePaperAtASmallerEffectiveDpi() {
        val spec = TemplateSpec.stock(TemplateKind.GRID)
        val full = TemplateGeometry.plan(spec, nomadW, nomadH, dpi)
        val quarter = TemplateGeometry.plan(spec, nomadW / 4, nomadH / 4, dpi / 4f)
        assertEquals(full.xs.size, quarter.xs.size)
        assertEquals(full.ys.size, quarter.ys.size)
    }

    @Test
    fun greyIsBlackAtTheTopOfTheLadderAndLighterDownIt() {
        assertEquals(0, TemplateSpec.greyFor(TemplateSpec.SHADE_BLACK))
        assertEquals(17, TemplateSpec.greyFor(TemplateSpec.SHADE_BLACK - 1))
        assertEquals(238, TemplateSpec.greyFor(TemplateSpec.SHADE_MIN))
        // Out of band is black, never paper — an invisible pattern is not a lighter one.
        assertEquals(0, TemplateSpec.greyFor(99))
        assertEquals(238, TemplateSpec.greyFor(-4))
    }
}
