package com.symmetricalpalmtree.notesprout.data

import com.symmetricalpalmtree.notesprout.data.template.BuiltInTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateGeometryTest {

    @Test
    fun spacingPx300dpi() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val expected = 8f * 300f / 25.4f
        assertEquals(expected, spacing, 0.01f)
    }

    @Test
    fun linePositionsStartAfterTopMargin() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val lines = BuiltInTemplates.linePositions(2000, spacing)
        assertTrue(lines.isNotEmpty())
        val topMargin = spacing
        assertEquals(topMargin + spacing, lines.first(), 0.01f)
    }

    @Test
    fun linePositionsStayWithinBounds() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val height = 2000
        val lines = BuiltInTemplates.linePositions(height, spacing)
        assertTrue(lines.all { it < height })
    }

    @Test
    fun linePositionsEvenlySPaced() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val lines = BuiltInTemplates.linePositions(2000, spacing)
        for (i in 1 until lines.size) {
            assertEquals(spacing, lines[i] - lines[i - 1], 0.01f)
        }
    }

    @Test
    fun dotPositionsFormGrid() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val dots = BuiltInTemplates.dotPositions(1000, 1000, spacing)
        assertTrue(dots.isNotEmpty())
        assertTrue(dots.all { it.first > 0 && it.second > 0 })
        assertTrue(dots.all { it.first < 1000 && it.second < 1000 })
    }

    @Test
    fun gridPositionsXWithinBounds() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val xs = BuiltInTemplates.gridPositionsX(1000, spacing)
        assertTrue(xs.isNotEmpty())
        assertTrue(xs.all { it > 0 && it < 1000 })
    }

    /** The grid must be symmetric: horizontals start at one spacing (like the verticals), NOT at the
     *  2×spacing writing-line top margin that would leave a double-height top row of cells. */
    @Test
    fun gridPositionsYStartAtOneSpacing_symmetricWithX() {
        val spacing = BuiltInTemplates.spacingPx(300f)
        val ys = BuiltInTemplates.gridPositionsY(1000, spacing)
        val xs = BuiltInTemplates.gridPositionsX(1000, spacing)
        assertTrue(ys.isNotEmpty())
        assertEquals(spacing, ys.first(), 0.01f)
        // Same origin as the vertical lines — no double-height top band.
        assertEquals(xs.first(), ys.first(), 0.01f)
        assertTrue(ys.all { it > 0 && it < 1000 })
        for (i in 1 until ys.size) assertEquals(spacing, ys[i] - ys[i - 1], 0.01f)
    }

    @Test
    fun featureSizesScaleWithDensity_andNeverDropBelowOnePixel() {
        assertEquals(1f, BuiltInTemplates.lineWidthPx(160f), 0.001f)
        assertEquals(1.875f, BuiltInTemplates.lineWidthPx(300f), 0.001f)
        assertEquals(1f, BuiltInTemplates.lineWidthPx(120f), 0.001f)
        assertEquals(2f, BuiltInTemplates.dotRadiusPx(160f), 0.001f)
        assertEquals(3.75f, BuiltInTemplates.dotRadiusPx(300f), 0.001f)
    }
}
