package com.notesprout.android.recognition.trocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.notesprout.android.data.LiveStroke
import java.nio.FloatBuffer

/**
 * Renders one segmented text line (its [LiveStroke]s only — no template, no color) into
 * the normalized float tensor the TrOCR encoder expects.
 *
 * Two stages, deliberately matching the model's training distribution (IAM line crops are
 * resized straight to a 384×384 square, distorting aspect):
 *  1. Render the strokes at **uniform** scale into an intermediate bitmap whose aspect
 *     matches the ink (fixed height, proportional width) — ink thickness stays isotropic.
 *  2. Bilinear-scale that bitmap to imageSize×imageSize (non-uniform, like training).
 *
 * The geometry math lives in [LineRasterGeometry] (pure Kotlin, unit-testable); this class
 * owns only the android.graphics half.
 *
 * The exact same rendering is used for training-pair export (Phase 3) — train/infer match.
 */
class LineRasterizer(
    private val imageSize: Int,
    private val mean: FloatArray,
    private val std: FloatArray,
) {

    /** Rasterize [strokes] and return a CHW float tensor buffer (3 × imageSize × imageSize). */
    fun rasterize(strokes: List<LiveStroke>): FloatBuffer {
        val bitmap = renderLineBitmap(strokes)
        val scaled = if (bitmap.width == imageSize && bitmap.height == imageSize) bitmap
        else Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)
        val tensor = toTensor(scaled)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return tensor
    }

    /** Stage 1: black-on-white uniform-scale render. Exposed for HwrLab preview + training export. */
    fun renderLineBitmap(strokes: List<LiveStroke>): Bitmap {
        val g = LineRasterGeometry.compute(strokeUnionBounds(strokes))
        val bitmap = Bitmap.createBitmap(g.bitmapWidth, g.bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK // all ink renders black regardless of LiveStroke.color
        }
        val path = Path()
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            paint.strokeWidth = g.renderStrokeWidth(stroke.strokeWidth)
            path.rewind()
            path.moveTo(g.mapX(pts[0].x), g.mapY(pts[0].y))
            if (pts.size == 1) {
                // dot — ROUND cap turns a zero-length segment into a disc
                path.lineTo(g.mapX(pts[0].x) + 0.01f, g.mapY(pts[0].y))
            } else {
                for (i in 1 until pts.size) path.lineTo(g.mapX(pts[i].x), g.mapY(pts[i].y))
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    private fun strokeUnionBounds(strokes: List<LiveStroke>): LineRasterGeometry.Bounds {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in strokes) {
            val bb = s.boundingBox
            if (bb.left < l) l = bb.left
            if (bb.top < t) t = bb.top
            if (bb.right > r) r = bb.right
            if (bb.bottom > b) b = bb.bottom
        }
        if (l > r) { l = 0f; t = 0f; r = 1f; b = 1f } // no strokes — degenerate unit box
        return LineRasterGeometry.Bounds(l, t, r, b)
    }

    /** ARGB bitmap → normalized CHW tensor: gray = luminance, replicated to 3 channels. */
    private fun toTensor(bitmap: Bitmap): FloatBuffer {
        val n = imageSize * imageSize
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)
        val buf = FloatBuffer.allocate(3 * n)
        for (c in 0 until 3) {
            val m = mean[c]; val s = std[c]
            for (i in 0 until n) {
                val p = pixels[i]
                // luminance in [0,1] — ink is grayscale, channels identical
                val lum = (0.299f * ((p shr 16) and 0xFF) +
                           0.587f * ((p shr 8) and 0xFF) +
                           0.114f * (p and 0xFF)) / 255f
                buf.put(c * n + i, (lum - m) / s)
            }
        }
        return buf
    }
}
