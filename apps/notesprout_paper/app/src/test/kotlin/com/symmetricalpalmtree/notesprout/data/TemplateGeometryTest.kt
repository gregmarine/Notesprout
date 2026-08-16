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
}
