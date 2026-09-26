package com.symmetricalpalmtree.sketchcompanion.crop

import kotlin.math.roundToInt

/** The arithmetic of a 3:4 portrait frame over a photo — pure, every rule pinned on the JVM. */
object CropMath {
    const val ASPECT = 3f / 4f
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 4f
    const val MAX_EXPORT_WIDTH = 4096

    /** The fraction `(fx, fy)` of the source visible through the frame at [zoom]: "cover" means the
     *  tighter axis is exactly `1 / zoom` and the other shows less. */
    fun visibleFraction(srcW: Int, srcH: Int, zoom: Float): Pair<Float, Float> {
        val a = srcW.toFloat() / srcH
        return if (a > ASPECT) Pair(ASPECT / a / zoom, 1f / zoom)   // wider than 3:4 → height-bound
        else Pair(1f / zoom, a / ASPECT / zoom)                      // taller → width-bound
    }

    /** The invariant "the photo always covers the frame": zoom within its range and the centre no
     *  closer to an edge than half the visible fraction. */
    fun clamp(s: CropState, srcW: Int, srcH: Int): CropState {
        val zoom = s.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val (fx, fy) = visibleFraction(srcW, srcH, zoom)
        return CropState(zoom, s.cx.coerceIn(fx / 2f, 1f - fx / 2f), s.cy.coerceIn(fy / 2f, 1f - fy / 2f))
    }

    /** A drag of ([dxFrac], [dyFrac]) frame-widths/heights moves the photo with the finger, so the
     *  centre moves the other way by that fraction of what is visible. */
    fun pan(s: CropState, dxFrac: Float, dyFrac: Float, srcW: Int, srcH: Int): CropState {
        val (fx, fy) = visibleFraction(srcW, srcH, s.zoom)
        return clamp(s.copy(cx = s.cx - dxFrac * fx, cy = s.cy - dyFrac * fy), srcW, srcH)
    }

    /** Zoom by [factor] about the frame point ([u], [v]) in 0…1: the source point under the fingers
     *  stays under the fingers. */
    fun zoomAt(s: CropState, factor: Float, u: Float, v: Float, srcW: Int, srcH: Int): CropState {
        val (fx, fy) = visibleFraction(srcW, srcH, s.zoom)
        val px = s.cx + (u - 0.5f) * fx
        val py = s.cy + (v - 0.5f) * fy
        val zoom = (s.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val (fx2, fy2) = visibleFraction(srcW, srcH, zoom)
        return clamp(CropState(zoom, px - (u - 0.5f) * fx2, py - (v - 0.5f) * fy2), srcW, srcH)
    }

    /** The source pixels behind the frame as `[left, top, width, height]` — exactly 3:4 (the height
     *  follows the width), inside the source. */
    fun sourceRect(s: CropState, srcW: Int, srcH: Int): IntArray {
        val c = clamp(s, srcW, srcH)
        val (fx, _) = visibleFraction(srcW, srcH, c.zoom)
        val w = (fx * srcW).roundToInt().coerceIn(1, srcW)
        val h = (w / ASPECT).roundToInt().coerceIn(1, srcH)
        val left = (c.cx * srcW - w / 2f).roundToInt().coerceIn(0, srcW - w)
        val top = (c.cy * srcH - h / 2f).roundToInt().coerceIn(0, srcH - h)
        return intArrayOf(left, top, w, h)
    }

    /** The export's pixel size: the crop as-is, or scaled down proportionally past [maxW]. */
    fun exportSize(cropW: Int, cropH: Int, maxW: Int = MAX_EXPORT_WIDTH): Pair<Int, Int> {
        if (cropW <= maxW) return Pair(cropW, cropH)
        return Pair(maxW, (maxW.toLong() * cropH / cropW).toInt())
    }
}
