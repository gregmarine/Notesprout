package com.symmetricalpalmtree.sketchcompanion.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CropMathTest {

    @Test fun visibleFractionAtCover() {
        val (fx, fy) = CropMath.visibleFraction(4000, 3000, 1f)     // 4:3 landscape → height-bound
        assertEquals(0.5625f, fx, 1e-4f); assertEquals(1f, fy, 1e-4f)
        val (gx, gy) = CropMath.visibleFraction(3000, 4000, 1f)     // exactly 3:4
        assertEquals(1f, gx, 1e-4f); assertEquals(1f, gy, 1e-4f)
        val (hx, hy) = CropMath.visibleFraction(1080, 1920, 1f)     // 9:16 → width-bound
        assertEquals(1f, hx, 1e-4f); assertEquals(0.75f, hy, 1e-4f)
        val (zx, _) = CropMath.visibleFraction(4000, 3000, 2f)
        assertEquals(0.28125f, zx, 1e-4f)
    }

    @Test fun clampNeverShowsAnEmptyEdge() {
        val rnd = Random(7)
        for (dims in listOf(4000 to 3000, 3000 to 4000, 1080 to 1920, 16320 to 12240)) {
            repeat(500) {
                val s = CropState(rnd.nextFloat() * 6f - 1f, rnd.nextFloat() * 3f - 1f, rnd.nextFloat() * 3f - 1f)
                val c = CropMath.clamp(s, dims.first, dims.second)
                val (fx, fy) = CropMath.visibleFraction(dims.first, dims.second, c.zoom)
                assertTrue(c.zoom in CropMath.MIN_ZOOM..CropMath.MAX_ZOOM)
                assertTrue(c.cx - fx / 2f >= -1e-5f && c.cx + fx / 2f <= 1f + 1e-5f)
                assertTrue(c.cy - fy / 2f >= -1e-5f && c.cy + fy / 2f <= 1f + 1e-5f)
            }
        }
    }

    @Test fun coverLeavesNothingToPanOnTheTightAxis() {
        val s = CropMath.pan(CropState(), 0f, 0.4f, 4000, 3000)     // height-bound: no vertical pan
        assertEquals(0.5f, s.cy, 1e-6f)
        val t = CropMath.pan(CropState(), 0.1f, 0f, 4000, 3000)     // sideways moves by 0.1 × fx
        assertEquals(0.5f - 0.1f * 0.5625f, t.cx, 1e-5f)
    }

    @Test fun panByAFullFrameMovesByTheVisibleFractionThenClamps() {
        val z = CropState(2f)
        val (fx, _) = CropMath.visibleFraction(4000, 3000, 2f)
        val s = CropMath.pan(z, -0.5f, 0f, 4000, 3000)
        assertEquals(0.5f + 0.5f * fx, s.cx, 1e-5f)
        val far = CropMath.pan(z, -10f, 0f, 4000, 3000)
        assertEquals(1f - fx / 2f, far.cx, 1e-5f)
    }

    @Test fun zoomAtKeepsThePointUnderTheFingers() {
        val s0 = CropState(1.5f, 0.55f, 0.5f)
        val (u, v) = 0.2f to 0.7f
        val (fx0, fy0) = CropMath.visibleFraction(4000, 3000, s0.zoom)
        val px = s0.cx + (u - 0.5f) * fx0
        val py = s0.cy + (v - 0.5f) * fy0
        val s1 = CropMath.zoomAt(s0, 1.3f, u, v, 4000, 3000)
        val (fx1, fy1) = CropMath.visibleFraction(4000, 3000, s1.zoom)
        assertEquals(px, s1.cx + (u - 0.5f) * fx1, 1e-4f)
        assertEquals(py, s1.cy + (v - 0.5f) * fy1, 1e-4f)
    }

    @Test fun zoomIsBounded() {
        assertEquals(CropMath.MAX_ZOOM, CropMath.zoomAt(CropState(3.5f), 5f, 0.5f, 0.5f, 4000, 3000).zoom, 0f)
        assertEquals(CropMath.MIN_ZOOM, CropMath.zoomAt(CropState(1.2f), 0.1f, 0.5f, 0.5f, 4000, 3000).zoom, 0f)
    }

    @Test fun sourceRectIsThreeByFourInsideTheSource() {
        val r = CropMath.sourceRect(CropState(), 4000, 3000)
        assertEquals(2250, r[2]); assertEquals(3000, r[3]); assertEquals(875, r[0]); assertEquals(0, r[1])
        val rnd = Random(3)
        for (dims in listOf(4000 to 3000, 3000 to 4000, 1080 to 1920, 16320 to 12240, 640 to 480)) {
            repeat(300) {
                val s = CropState(1f + rnd.nextFloat() * 3f, rnd.nextFloat(), rnd.nextFloat())
                val q = CropMath.sourceRect(s, dims.first, dims.second)
                assertTrue(q[0] >= 0 && q[1] >= 0)
                assertTrue(q[0] + q[2] <= dims.first && q[1] + q[3] <= dims.second)
                assertTrue(abs(q[3] - q[2] * 4f / 3f) <= 1f)
            }
        }
    }

    @Test fun exportSizeCapsAtWidthKeepingThreeByFour() {
        assertEquals(3000 to 4000, CropMath.exportSize(3000, 4000))
        val (w, h) = CropMath.exportSize(9180, 12240)
        assertEquals(4096, w); assertEquals(5461, h)
    }
}

class CropRotationTest {
    private val w = 3000; private val h = 4000

    @Test fun minZoomIsOneUpRightAndFourThirdsAtNinetyOnAThreeByFour() {
        assertEquals(1f, CropMath.minZoom(w, h, 0f), 1e-5f)
        assertEquals(4f / 3f, CropMath.minZoom(w, h, 90f), 1e-4f)
        assertEquals(1f, CropMath.minZoom(w, h, 180f), 1e-4f)
        assertTrue(CropMath.minZoom(w, h, 45f) > 1f)
    }

    @Test fun clampRaisesZoomForTheTurnAndKeepsTheBoxInside() {
        val rnd = Random(11)
        for (dims in listOf(w to h, 4000 to 3000, 1080 to 1920)) {
            repeat(500) {
                val s = CropState(rnd.nextFloat() * 5f, rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat() * 720f - 360f)
                val c = CropMath.clamp(s, dims.first, dims.second)
                assertTrue(c.angle > -180f && c.angle <= 180f)
                assertTrue(c.zoom >= CropMath.minZoom(dims.first, dims.second, c.angle) - 1e-5f)
                val (bw, bh) = CropMath.boundingBox(dims.first, dims.second, c.zoom, c.angle)
                assertTrue(c.cx * dims.first - bw / 2f >= -1f && c.cx * dims.first + bw / 2f <= dims.first + 1f)
                assertTrue(c.cy * dims.second - bh / 2f >= -1f && c.cy * dims.second + bh / 2f <= dims.second + 1f)
            }
        }
    }

    @Test fun rotateAtKeepsTheSourcePointUnderTheFingers() {
        val s0 = CropState(2f, 0.5f, 0.5f, 10f)
        val (u, v) = 0.3f to 0.6f
        fun under(s: CropState): Pair<Float, Float> {
            val (wf, hf) = CropMath.frameExtent(w, h, s.zoom)
            val r = Math.toRadians(-s.angle.toDouble())
            val ox = (u - 0.5f) * wf; val oy = (v - 0.5f) * hf
            return Pair(s.cx * w + (ox * Math.cos(r) - oy * Math.sin(r)).toFloat(), s.cy * h + (ox * Math.sin(r) + oy * Math.cos(r)).toFloat())
        }
        val before = under(s0)
        val s1 = CropMath.rotateAt(s0, 25f, u, v, w, h)
        val after = under(s1)
        assertEquals(35f, s1.angle, 1e-4f)
        assertEquals(before.first, after.first, 0.5f)
        assertEquals(before.second, after.second, 0.5f)
    }

    @Test fun panFollowsTheTurnedAxes() {
        val s = CropState(2f, 0.5f, 0.5f, 90f)
        val moved = CropMath.pan(s, 0.1f, 0f, w, h)        // a sideways drag at 90° moves the centre vertically
        assertEquals(0.5f, moved.cx, 1e-4f)
        assertTrue(abs(moved.cy - 0.5f) > 0.01f)
    }

    @Test fun snapPullsNearRightAnglesOnly() {
        assertEquals(90f, CropMath.snap(CropState(2f, angle = 87f), w, h).angle, 0f)
        assertEquals(0f, CropMath.snap(CropState(2f, angle = -3.5f), w, h).angle, 0f)
        assertEquals(180f, CropMath.snap(CropState(2f, angle = -177f), w, h).angle, 0f)
        assertEquals(30f, CropMath.snap(CropState(2f, angle = 30f), w, h).angle, 0f)
    }

    @Test fun exportGeometryRegionCoversTheTurnedFrame() {
        val g = CropMath.exportGeometry(CropState(2f, 0.5f, 0.5f, 45f), w, h)
        assertEquals(1500, g.cropW); assertEquals(2000, g.cropH)
        assertTrue(g.regionW >= g.cropW && g.regionH >= g.cropH)
        assertTrue(g.regionLeft >= 0 && g.regionLeft + g.regionW <= w)
        assertTrue(g.regionTop >= 0 && g.regionTop + g.regionH <= h)
        assertEquals(45f, g.angle, 0f)
    }

    @Test fun exportScaleCapsBothTheCropAndTheRegion() {
        val g = CropMath.exportGeometry(CropState(1f, 0.5f, 0.5f, 0f), 12000, 16000)
        assertEquals(4096f / 12000f, CropMath.exportScale(g), 1e-5f)
        val turned = CropMath.exportGeometry(CropState(1.5f, 0.5f, 0.5f, 45f), 12000, 16000)
        assertTrue(CropMath.exportScale(turned) < 4096f / turned.cropW + 1e-6f)
        assertTrue(maxOf(turned.regionW, turned.regionH) * CropMath.exportScale(turned) <= CropMath.MAX_EXPORT_REGION + 1f)
    }
}
