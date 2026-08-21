package com.symmetricalpalmtree.notesproutsn.data

import com.symmetricalpalmtree.notesproutsn.data.template.TemplateGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateGeometryTest {

    private val eps = 0.001f

    @Test
    fun spacingIsEightMillimetresAtTheRealDpi() {
        assertEquals(8f * 160f / 25.4f, TemplateGeometry.spacingPx(160f), eps)
        assertEquals(8f * 300f / 25.4f, TemplateGeometry.spacingPx(300f), eps)
        // ~94 px on a 300 ppi Supernote panel.
        assertEquals(94.488f, TemplateGeometry.spacingPx(300f), 0.01f)
    }

    @Test
    fun featureSizesScaleWithDensityAndNeverGoBelowOnePixel() {
        assertEquals(1f, TemplateGeometry.lineWidthPx(160f), eps)
        assertEquals(1.875f, TemplateGeometry.lineWidthPx(300f), eps)
        assertEquals(1f, TemplateGeometry.lineWidthPx(80f), eps) // clamped
        assertEquals(2f, TemplateGeometry.dotRadiusPx(160f), eps)
        assertEquals(3.75f, TemplateGeometry.dotRadiusPx(300f), eps)
        assertEquals(1f, TemplateGeometry.dotRadiusPx(60f), eps) // clamped
    }

    @Test
    fun linedStartsAfterATopMarginOfOneSpacing() {
        val positions = TemplateGeometry.linePositions(heightPx = 100, spacingPx = 10f)
        assertEquals(20f, positions.first(), eps)
        assertEquals(listOf(20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f), positions)
    }

    @Test
    fun linedStopsBeforeTheBottomEdge() {
        val positions = TemplateGeometry.linePositions(heightPx = 100, spacingPx = 10f)
        assertTrue(positions.all { it < 100f })
        assertEquals(90f, positions.last(), eps)
    }

    @Test
    fun linedIsEmptyWhenThePageIsShorterThanTheMargin() {
        assertEquals(emptyList<Float>(), TemplateGeometry.linePositions(heightPx = 15, spacingPx = 10f))
    }

    @Test
    fun gridStartsAtOneSpacingOnBothAxes() {
        assertEquals(listOf(10f, 20f, 30f, 40f), TemplateGeometry.gridPositionsX(widthPx = 50, spacingPx = 10f))
        assertEquals(listOf(10f, 20f, 30f, 40f), TemplateGeometry.gridPositionsY(heightPx = 50, spacingPx = 10f))
    }

    @Test
    fun gridDoesNotInheritTheLinedTopMargin() {
        // If the horizontals ever borrowed linePositions the top row of cells would be double height.
        val h = TemplateGeometry.gridPositionsY(heightPx = 100, spacingPx = 10f)
        val lined = TemplateGeometry.linePositions(heightPx = 100, spacingPx = 10f)
        assertEquals(10f, h.first(), eps)
        assertEquals(20f, lined.first(), eps)
        assertEquals(h.size, lined.size + 1)
    }

    @Test
    fun gridIsSymmetricForASquarePage() {
        assertEquals(
            TemplateGeometry.gridPositionsX(widthPx = 200, spacingPx = 17f),
            TemplateGeometry.gridPositionsY(heightPx = 200, spacingPx = 17f),
        )
    }

    @Test
    fun dotsSitOnEveryGridIntersectionRowMajor() {
        val dots = TemplateGeometry.dotPositions(widthPx = 30, heightPx = 30, spacingPx = 10f)
        assertEquals(listOf(10f to 10f, 20f to 10f, 10f to 20f, 20f to 20f), dots)
    }

    @Test
    fun dotCountIsColumnsTimesRows() {
        val xs = TemplateGeometry.gridPositionsX(widthPx = 1404, spacingPx = 94.488f)
        val ys = TemplateGeometry.gridPositionsY(heightPx = 1872, spacingPx = 94.488f)
        assertEquals(xs.size * ys.size, TemplateGeometry.dotPositions(1404, 1872, 94.488f).size)
    }

    @Test
    fun aNomadPageGetsSaneCounts() {
        val spacing = TemplateGeometry.spacingPx(300f)
        // 1872 px tall at 94.5 px spacing: 8 mm rules with a top margin.
        assertEquals(18, TemplateGeometry.linePositions(1872, spacing).size)
        assertEquals(19, TemplateGeometry.gridPositionsY(1872, spacing).size)
        assertEquals(14, TemplateGeometry.gridPositionsX(1404, spacing).size)
    }

    @Test
    fun nonPositiveSpacingProducesNothingRatherThanHanging() {
        assertEquals(emptyList<Float>(), TemplateGeometry.linePositions(1000, 0f))
        assertEquals(emptyList<Float>(), TemplateGeometry.gridPositionsX(1000, -5f))
    }
}
