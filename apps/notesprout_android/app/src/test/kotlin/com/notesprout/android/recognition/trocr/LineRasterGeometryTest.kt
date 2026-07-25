package com.notesprout.android.recognition.trocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineRasterGeometryTest {

    @Test
    fun typicalLineFillsHeightAndKeepsAspect() {
        // ink 1000×100 at (50, 200)
        val g = LineRasterGeometry.compute(LineRasterGeometry.Bounds(50f, 200f, 1050f, 300f))
        assertEquals(LineRasterGeometry.RENDER_HEIGHT, g.bitmapHeight)
        // width ≈ (1000 + 2*8) / (100 + 2*8) * 128 ≈ 1122
        assertTrue("width ${g.bitmapWidth}", g.bitmapWidth in 1050..1200)
        // left edge of ink maps just inside the left padding, vertically centered-ish
        assertTrue(g.mapX(50f) > 0f && g.mapX(50f) < 20f)
        assertTrue(g.mapY(200f) > 0f && g.mapY(300f) < LineRasterGeometry.RENDER_HEIGHT.toFloat())
    }

    @Test
    fun veryLongLineIsCappedAtMaxWidth() {
        val g = LineRasterGeometry.compute(LineRasterGeometry.Bounds(0f, 0f, 60000f, 100f))
        assertEquals(LineRasterGeometry.MAX_RENDER_WIDTH, g.bitmapWidth)
        // content still lands inside the bitmap
        assertTrue(g.mapX(60000f) <= LineRasterGeometry.MAX_RENDER_WIDTH.toFloat())
        assertTrue(g.mapY(50f) in 0f..LineRasterGeometry.RENDER_HEIGHT.toFloat())
    }

    @Test
    fun degenerateDotDoesNotCrashOrCollapse() {
        val g = LineRasterGeometry.compute(LineRasterGeometry.Bounds(10f, 10f, 10f, 10f))
        assertTrue(g.bitmapWidth >= 1)
        assertEquals(LineRasterGeometry.RENDER_HEIGHT, g.bitmapHeight)
    }

    @Test
    fun strokeWidthClampedToLegibleBand() {
        val g = LineRasterGeometry.compute(LineRasterGeometry.Bounds(0f, 0f, 1000f, 100f))
        assertEquals(LineRasterGeometry.MIN_RENDER_STROKE_PX, g.renderStrokeWidth(0.1f))
        assertEquals(LineRasterGeometry.MAX_RENDER_STROKE_PX, g.renderStrokeWidth(500f))
        val mid = g.renderStrokeWidth(3f)
        assertTrue(mid in LineRasterGeometry.MIN_RENDER_STROKE_PX..LineRasterGeometry.MAX_RENDER_STROKE_PX)
    }
}
