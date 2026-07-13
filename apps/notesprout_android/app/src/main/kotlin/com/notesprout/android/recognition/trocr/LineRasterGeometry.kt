package com.notesprout.android.recognition.trocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Placement math for [LineRasterizer]'s intermediate (stage-1) bitmap: uniform scale of
 * the line's ink into a fixed-height bitmap whose width follows the ink's aspect ratio,
 * with padding proportional to the ink height and stroke thickness normalized to a
 * legible-band at render resolution.
 *
 * Pure Kotlin (no android.graphics) so it is unit-testable, mirroring
 * [com.notesprout.android.core.StrokeCodec]'s Android-free style.
 */
class LineRasterGeometry private constructor(
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    private val scale: Float,
    private val dx: Float,
    private val dy: Float,
) {
    class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun mapX(x: Float): Float = x * scale + dx
    fun mapY(y: Float): Float = y * scale + dy

    /** Source stroke width → render width, clamped so thin pen ink never vanishes. */
    fun renderStrokeWidth(sourceWidth: Float): Float =
        (sourceWidth * scale).coerceIn(MIN_RENDER_STROKE_PX, MAX_RENDER_STROKE_PX)

    companion object {
        /** Stage-1 bitmap height; chosen near typical HTR line-crop heights. */
        const val RENDER_HEIGHT = 128

        /** Padding around the ink as a fraction of ink height. */
        const val PAD_FRAC = 0.08f

        /** Cap on stage-1 width — bounds memory on very long lines. */
        const val MAX_RENDER_WIDTH = 3072

        const val MIN_RENDER_STROKE_PX = 1.5f
        const val MAX_RENDER_STROKE_PX = 4.5f

        fun compute(bounds: Bounds): LineRasterGeometry {
            val contentH = max(bounds.height, 1f)
            val contentW = max(bounds.width, 1f)
            val pad = contentH * PAD_FRAC

            // Uniform scale fitting (ink + padding) height into RENDER_HEIGHT.
            var scale = RENDER_HEIGHT / (contentH + 2 * pad)
            var width = ((contentW + 2 * pad) * scale).roundToInt().coerceAtLeast(1)
            if (width > MAX_RENDER_WIDTH) {
                // Very long line: shrink uniformly to the width cap (letterboxed vertically).
                scale *= MAX_RENDER_WIDTH.toFloat() / width
                width = MAX_RENDER_WIDTH
            }
            val scaledH = (contentH + 2 * pad) * scale
            val dy = (RENDER_HEIGHT - scaledH) / 2f - (bounds.top - pad) * scale
            val dx = -(bounds.left - pad) * scale

            return LineRasterGeometry(
                bitmapWidth = width,
                bitmapHeight = RENDER_HEIGHT,
                scale = scale,
                dx = dx,
                dy = dy,
            )
        }
    }
}
