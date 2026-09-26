package com.symmetricalpalmtree.sketchcompanion.crop

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The arithmetic of a 3:4 portrait frame over a photo — pure, every rule pinned on the JVM. */
object CropMath {
    const val ASPECT = 3f / 4f
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 4f
    const val MAX_EXPORT_WIDTH = 4096
    /** The decoded region behind a turned frame may be wider than the frame; cap its long side. */
    const val MAX_EXPORT_REGION = 6000
    /** Within this of a right angle the turn snaps to it at the end of a gesture. */
    const val SNAP_DEGREES = 4f

    /** The frame's own extent in source pixels at [zoom] (un-turned): `(Wf, Hf)`, `Hf = Wf / ASPECT`. */
    fun frameExtent(srcW: Int, srcH: Int, zoom: Float): Pair<Float, Float> {
        val (fx, fy) = visibleFraction(srcW, srcH, zoom)
        return Pair(fx * srcW, fy * srcH)
    }

    /** The fraction `(fx, fy)` of the source the un-turned frame spans at [zoom]: "cover" means the
     *  tighter axis is exactly `1 / zoom` and the other shows less. */
    fun visibleFraction(srcW: Int, srcH: Int, zoom: Float): Pair<Float, Float> {
        val a = srcW.toFloat() / srcH
        return if (a > ASPECT) Pair(ASPECT / a / zoom, 1f / zoom)   // wider than 3:4 → height-bound
        else Pair(1f / zoom, a / ASPECT / zoom)                      // taller → width-bound
    }

    /** The turned frame's axis-aligned bounding box in source pixels. */
    fun boundingBox(srcW: Int, srcH: Int, zoom: Float, angle: Float): Pair<Float, Float> {
        val (wf, hf) = frameExtent(srcW, srcH, zoom)
        val r = Math.toRadians(angle.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        return Pair(wf * c + hf * s, wf * s + hf * c)
    }

    /** The least zoom at which a frame turned by [angle] still lies wholly inside the photo. */
    fun minZoom(srcW: Int, srcH: Int, angle: Float): Float {
        val (bw, bh) = boundingBox(srcW, srcH, 1f, angle)
        return maxOf(MIN_ZOOM, bw / srcW, bh / srcH)
    }

    /** The invariant "the photo always covers the frame": zoom within its range for this turn, and
     *  the centre no closer to an edge than half the turned frame's bounding box. */
    fun clamp(s: CropState, srcW: Int, srcH: Int): CropState {
        val angle = normalize(s.angle)
        val zoom = s.zoom.coerceIn(minZoom(srcW, srcH, angle), MAX_ZOOM)
        val (bw, bh) = boundingBox(srcW, srcH, zoom, angle)
        val hx = (bw / srcW / 2f).coerceAtMost(0.5f)
        val hy = (bh / srcH / 2f).coerceAtMost(0.5f)
        return CropState(zoom, s.cx.coerceIn(hx, 1f - hx), s.cy.coerceIn(hy, 1f - hy), angle)
    }

    /** A drag of ([dxFrac], [dyFrac]) frame-widths/heights on screen moves the photo with the
     *  finger: the centre moves the other way, along the frame's own (turned) axes. */
    fun pan(s: CropState, dxFrac: Float, dyFrac: Float, srcW: Int, srcH: Int): CropState {
        val (wf, hf) = frameExtent(srcW, srcH, s.zoom)
        val (dx, dy) = unturn(dxFrac * wf, dyFrac * hf, s.angle)
        return clamp(s.copy(cx = s.cx - dx / srcW, cy = s.cy - dy / srcH), srcW, srcH)
    }

    /** Zoom by [factor] about the frame point ([u], [v]) in 0…1: the source point under the fingers
     *  stays under the fingers. */
    fun zoomAt(s: CropState, factor: Float, u: Float, v: Float, srcW: Int, srcH: Int): CropState =
        transformAt(s, (s.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM), s.angle, u, v, srcW, srcH)

    /** Turn by [degrees] about the frame point ([u], [v]): that source point stays put. */
    fun rotateAt(s: CropState, degrees: Float, u: Float, v: Float, srcW: Int, srcH: Int): CropState =
        transformAt(s, s.zoom, s.angle + degrees, u, v, srcW, srcH)

    /** Snap a turn within [SNAP_DEGREES] of a right angle onto it (the end of a gesture). */
    fun snap(s: CropState, srcW: Int, srcH: Int): CropState {
        val a = normalize(s.angle)
        val nearest = (a / 90f).roundToInt() * 90f
        return if (abs(a - nearest) <= SNAP_DEGREES) clamp(s.copy(angle = nearest), srcW, srcH) else clamp(s, srcW, srcH)
    }

    private fun transformAt(s: CropState, zoom: Float, angle: Float, u: Float, v: Float, srcW: Int, srcH: Int): CropState {
        val (wf, hf) = frameExtent(srcW, srcH, s.zoom)
        val (ox, oy) = unturn((u - 0.5f) * wf, (v - 0.5f) * hf, s.angle)
        val px = s.cx * srcW + ox
        val py = s.cy * srcH + oy
        val z = zoom.coerceIn(minZoom(srcW, srcH, angle), MAX_ZOOM)
        val (wf2, hf2) = frameExtent(srcW, srcH, z)
        val (ox2, oy2) = unturn((u - 0.5f) * wf2, (v - 0.5f) * hf2, angle)
        return clamp(CropState(z, (px - ox2) / srcW, (py - oy2) / srcH, angle), srcW, srcH)
    }

    /** A vector in the frame's axes → the same vector in the source's axes (the frame is turned by
     *  [angle] on screen, so the source is turned by −angle relative to the frame). */
    private fun unturn(x: Float, y: Float, angle: Float): Pair<Float, Float> {
        val r = Math.toRadians(-angle.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Pair(x * c - y * s, x * s + y * c)
    }

    fun normalize(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }

    /** What an export needs, all in source pixels: the frame's size ([cropW] × [cropH], exactly 3:4),
     *  its centre, its turn, and the axis-aligned [region] behind it to decode. */
    class ExportGeometry(
        val cropW: Int, val cropH: Int, val centreX: Float, val centreY: Float, val angle: Float,
        val regionLeft: Int, val regionTop: Int, val regionW: Int, val regionH: Int,
    )

    fun exportGeometry(s: CropState, srcW: Int, srcH: Int): ExportGeometry {
        val c = clamp(s, srcW, srcH)
        val (fx, _) = visibleFraction(srcW, srcH, c.zoom)
        val w = (fx * srcW).roundToInt().coerceIn(1, srcW)
        val h = (w / ASPECT).roundToInt().coerceIn(1, srcH)
        val (bw, bh) = boundingBox(srcW, srcH, c.zoom, c.angle)
        val cxPx = c.cx * srcW
        val cyPx = c.cy * srcH
        val rw = bw.roundToInt().coerceIn(1, srcW)
        val rh = bh.roundToInt().coerceIn(1, srcH)
        val left = (cxPx - rw / 2f).roundToInt().coerceIn(0, srcW - rw)
        val top = (cyPx - rh / 2f).roundToInt().coerceIn(0, srcH - rh)
        return ExportGeometry(w, h, cxPx, cyPx, c.angle, left, top, rw, rh)
    }

    /** The un-turned source rect behind the frame as `[left, top, width, height]` — the export at
     *  angle 0, kept for the tests and the log. */
    fun sourceRect(s: CropState, srcW: Int, srcH: Int): IntArray {
        val g = exportGeometry(s, srcW, srcH)
        val left = (g.centreX - g.cropW / 2f).roundToInt().coerceIn(0, srcW - g.cropW)
        val top = (g.centreY - g.cropH / 2f).roundToInt().coerceIn(0, srcH - g.cropH)
        return intArrayOf(left, top, g.cropW, g.cropH)
    }

    /** The factor the source is scaled by for the export: 1, or less so the crop stays within
     *  [MAX_EXPORT_WIDTH] wide and the decoded region within [MAX_EXPORT_REGION] on its long side. */
    fun exportScale(g: ExportGeometry): Float =
        minOf(1f, MAX_EXPORT_WIDTH.toFloat() / g.cropW, MAX_EXPORT_REGION.toFloat() / maxOf(g.regionW, g.regionH))

    /** The export's pixel size: the crop as-is, or scaled down proportionally past [maxW]. */
    fun exportSize(cropW: Int, cropH: Int, maxW: Int = MAX_EXPORT_WIDTH): Pair<Int, Int> {
        if (cropW <= maxW) return Pair(cropW, cropH)
        return Pair(maxW, (maxW.toLong() * cropH / cropW).toInt())
    }
}
