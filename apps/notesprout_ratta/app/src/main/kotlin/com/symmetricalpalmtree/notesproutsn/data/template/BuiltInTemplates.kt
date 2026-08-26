package com.symmetricalpalmtree.notesproutsn.data.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Paints a [TemplateKind] onto a bitmap. **Thin on purpose** — every position comes from
 * [TemplateGeometry]; this file only knows how to hold a brush.
 *
 * The result is baked into the notebook's `template` row at creation time (lossless WEBP), so
 * changing a constant here affects new notebooks only — existing files keep the background they
 * were born with. That is the point: a page must not silently re-rule itself under old ink.
 */
object BuiltInTemplates {

    /**
     * The page-sized render that gets baked into the file: real 8 mm spacing at the panel's [dpi].
     * Null for [TemplateKind.BLANK] — a blank notebook has no template row and its page's `refId`
     * is `""`.
     */
    fun render(kind: TemplateKind, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? = renderWith(
        kind, widthPx, heightPx,
        spacingPx = TemplateGeometry.spacingPx(dpi),
        lineWidthPx = TemplateGeometry.lineWidthPx(dpi),
        dotRadiusPx = TemplateGeometry.dotRadiusPx(dpi),
    )

    /**
     * A card-sized thumbnail of the same pattern for a notebook that has no cover snapshot yet.
     * Real 8 mm spacing inside a 3 cm card would draw two rules and read as nothing, so the
     * placeholder squeezes the pattern to a fixed number of rows — it is a *hint at* the template,
     * not a scale model of it.
     */
    fun placeholder(kind: TemplateKind, widthPx: Int, heightPx: Int, density: Float): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val feature = maxOf(1f, density)
        return renderWith(
            kind, widthPx, heightPx,
            spacingPx = (heightPx / PLACEHOLDER_ROWS).toFloat().coerceAtLeast(2f),
            lineWidthPx = feature,
            dotRadiusPx = feature,
        )
    }

    /**
     * A **true miniature** of the same paper (arc 13): the page pattern scaled honestly to a card,
     * not the squeezed hint [placeholder] draws. `scale` is the card's width over the page's, so
     * 8 mm ruling on a quarter-width card is 2 mm of card — which is exactly what makes a dense
     * grid look dense and tells two variants apart at a glance.
     *
     * Feature sizes floor at 1 px: below that a rule stops being drawn at all, and a card showing
     * nothing would read as Blank.
     */
    fun miniature(kind: TemplateKind, widthPx: Int, heightPx: Int, scale: Float, dpi: Float): Bitmap? {
        if (scale <= 0f) return null
        return renderWith(
            kind, widthPx, heightPx,
            spacingPx = TemplateGeometry.spacingPx(dpi) * scale,
            lineWidthPx = maxOf(1f, TemplateGeometry.lineWidthPx(dpi) * scale),
            dotRadiusPx = maxOf(1f, TemplateGeometry.dotRadiusPx(dpi) * scale),
        )
    }

    private fun renderWith(
        kind: TemplateKind,
        widthPx: Int,
        heightPx: Int,
        spacingPx: Float,
        lineWidthPx: Float,
        dotRadiusPx: Float,
    ): Bitmap? {
        if (kind == TemplateKind.BLANK || widthPx <= 0 || heightPx <= 0) return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        when (kind) {
            TemplateKind.LINED -> {
                paint.strokeWidth = lineWidthPx
                for (y in TemplateGeometry.linePositions(heightPx, spacingPx)) {
                    canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                }
            }
            TemplateKind.DOTTED -> {
                paint.style = Paint.Style.FILL
                for ((x, y) in TemplateGeometry.dotPositions(widthPx, heightPx, spacingPx)) {
                    canvas.drawCircle(x, y, dotRadiusPx, paint)
                }
            }
            TemplateKind.GRID -> {
                paint.strokeWidth = lineWidthPx
                for (y in TemplateGeometry.gridPositionsY(heightPx, spacingPx)) {
                    canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                }
                for (x in TemplateGeometry.gridPositionsX(widthPx, spacingPx)) {
                    canvas.drawLine(x, 0f, x, heightPx.toFloat(), paint)
                }
            }
            TemplateKind.BLANK -> {}
        }
        return bitmap
    }

    /** How many pattern rows a card-sized placeholder shows, whatever the card's real size is. */
    private const val PLACEHOLDER_ROWS = 12

    /**
     * Lossless WEBP at quality 100 — a template is line art; a lossy codec would fuzz every rule.
     * `WEBP_LOSSLESS` is API 30; on 29 the legacy `WEBP` at quality 100 is the closest available.
     * (minSdk is 29 for the family; every Supernote actually runs 30+.)
     */
    fun toWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, 100, out)
        return out.toByteArray()
    }
}
