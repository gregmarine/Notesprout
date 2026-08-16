package com.symmetricalpalmtree.notesprout.ext.templates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * The built-in template renderer, moved verbatim from the v0 core `BuiltInTemplates` (same geometry,
 * same WEBP encode) so notebooks created through the extension are indistinguishable from v0's.
 *
 * Blank is NOT a template here — it is the host's "no template" option.
 */
object TemplateRenderer {

    private const val SPACING_MM = 8f
    /** Feature sizes are authored at mdpi (160 dpi) and scaled by density — a 1 px rule / 1.5 px dot
     *  at 300 ppi was ~0.13 mm and read as faint grey on e-ink (Phase 3 finding). */
    private const val LINE_WIDTH_MDPI = 1f
    private const val DOT_RADIUS_MDPI = 2f

    enum class Kind { LINED, DOTTED, GRID }

    /** The ids this provider offers, in display order. */
    val TEMPLATE_IDS: List<String> = listOf("lined", "dotted", "grid")

    fun kindForId(id: String): Kind? = when (id) {
        "lined" -> Kind.LINED
        "dotted" -> Kind.DOTTED
        "grid" -> Kind.GRID
        else -> null
    }

    /** Render [templateId] at exactly [widthPx]×[heightPx] for [dpi] as a lossless WEBP, or null if unknown. */
    fun renderWebp(templateId: String, widthPx: Int, heightPx: Int, dpi: Float): ByteArray? {
        val kind = kindForId(templateId) ?: return null
        val bitmap = render(kind, widthPx, heightPx, dpi)
        return try {
            bitmapToWebp(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(kind: Kind, widthPx: Int, heightPx: Int, dpi: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val spacingPx = spacingPx(dpi)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
        }
        val lineWidth = lineWidthPx(dpi)
        val dotRadius = dotRadiusPx(dpi)
        when (kind) {
            Kind.LINED -> drawLined(canvas, paint, widthPx, heightPx, spacingPx, lineWidth)
            Kind.DOTTED -> drawDotted(canvas, paint, widthPx, heightPx, spacingPx, dotRadius)
            Kind.GRID -> drawGrid(canvas, paint, widthPx, heightPx, spacingPx, lineWidth)
        }
        return bitmap
    }

    fun spacingPx(dpi: Float): Float = SPACING_MM * dpi / 25.4f

    /** Rule thickness: 1 px at mdpi, never below 1 px (≈ 2 px on a 300 ppi panel). */
    fun lineWidthPx(dpi: Float): Float = maxOf(1f, LINE_WIDTH_MDPI * dpi / 160f)

    /** Dot radius: 2 px at mdpi (≈ 3.75 px radius, ~0.6 mm dot at 300 ppi). */
    fun dotRadiusPx(dpi: Float): Float = maxOf(1f, DOT_RADIUS_MDPI * dpi / 160f)

    fun linePositions(heightPx: Int, spacingPx: Float): List<Float> {
        val topMargin = spacingPx
        val positions = mutableListOf<Float>()
        var y = topMargin + spacingPx
        while (y < heightPx) {
            positions.add(y)
            y += spacingPx
        }
        return positions
    }

    fun gridPositionsX(widthPx: Int, spacingPx: Float): List<Float> {
        val positions = mutableListOf<Float>()
        var x = spacingPx
        while (x < widthPx) {
            positions.add(x)
            x += spacingPx
        }
        return positions
    }

    /** Horizontal grid lines. Symmetric with [gridPositionsX] (first at one spacing, uniform cells) —
     *  the grid must NOT reuse [linePositions], whose 2×spacing writing-line top margin would leave a
     *  double-height top row of cells. */
    fun gridPositionsY(heightPx: Int, spacingPx: Float): List<Float> {
        val positions = mutableListOf<Float>()
        var y = spacingPx
        while (y < heightPx) {
            positions.add(y)
            y += spacingPx
        }
        return positions
    }

    fun dotPositions(widthPx: Int, heightPx: Int, spacingPx: Float): List<Pair<Float, Float>> {
        val positions = mutableListOf<Pair<Float, Float>>()
        var y = spacingPx
        while (y < heightPx) {
            var x = spacingPx
            while (x < widthPx) {
                positions.add(x to y)
                x += spacingPx
            }
            y += spacingPx
        }
        return positions
    }

    private fun drawLined(canvas: Canvas, paint: Paint, w: Int, h: Int, spacingPx: Float, lineWidth: Float) {
        paint.strokeWidth = lineWidth
        for (y in linePositions(h, spacingPx)) {
            canvas.drawLine(0f, y, w.toFloat(), y, paint)
        }
    }

    private fun drawDotted(canvas: Canvas, paint: Paint, w: Int, h: Int, spacingPx: Float, dotRadius: Float) {
        paint.style = Paint.Style.FILL
        for ((x, y) in dotPositions(w, h, spacingPx)) {
            canvas.drawCircle(x, y, dotRadius, paint)
        }
    }

    private fun drawGrid(canvas: Canvas, paint: Paint, w: Int, h: Int, spacingPx: Float, lineWidth: Float) {
        paint.strokeWidth = lineWidth
        for (y in gridPositionsY(h, spacingPx)) {
            canvas.drawLine(0f, y, w.toFloat(), y, paint)
        }
        for (x in gridPositionsX(w, spacingPx)) {
            canvas.drawLine(x, 0f, x, h.toFloat(), paint)
        }
    }

    private fun bitmapToWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        return out.toByteArray()
    }
}
