package com.symmetricalpalmtree.notesprout.data.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

enum class TemplateKind { BLANK, LINED, DOTTED, GRID }

object BuiltInTemplates {

    private const val SPACING_MM = 8f
    /** Feature sizes are authored at mdpi (160 dpi) and scaled by density — a 1 px rule / 1.5 px dot
     *  at 300 ppi was ~0.13 mm and read as faint grey on e-ink (Phase 3 finding). */
    private const val LINE_WIDTH_MDPI = 1f
    private const val DOT_RADIUS_MDPI = 2f

    fun render(kind: TemplateKind, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? {
        if (kind == TemplateKind.BLANK) return null
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
            TemplateKind.LINED -> drawLined(canvas, paint, widthPx, heightPx, spacingPx, lineWidth)
            TemplateKind.DOTTED -> drawDotted(canvas, paint, widthPx, heightPx, spacingPx, dotRadius)
            TemplateKind.GRID -> drawGrid(canvas, paint, widthPx, heightPx, spacingPx, lineWidth)
            TemplateKind.BLANK -> {}
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
        for (y in linePositions(h, spacingPx)) {
            canvas.drawLine(0f, y, w.toFloat(), y, paint)
        }
        for (x in gridPositionsX(w, spacingPx)) {
            canvas.drawLine(x, 0f, x, h.toFloat(), paint)
        }
    }
}
