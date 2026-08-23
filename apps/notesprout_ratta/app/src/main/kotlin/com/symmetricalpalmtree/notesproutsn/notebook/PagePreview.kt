package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer

/**
 * Renders one page-preview bitmap for the link picker (arc 6 / K2): white paper, the page's
 * content scaled uniformly from page px to the preview's width — headings first, then each link's
 * wrapped children, then the loose ink, the paper's own layering (content renderers sit
 * [below the strokes][com.symmetricalpalmtree.gpaper.core.render.ContentLayer.BELOW_STROKES]).
 *
 * Strokes + headings only, the locked scope: no template render, and no link chrome — a preview
 * shows what was *written*. Strokes go through g-paper's [StrokeRasterizer] (the same renderer
 * the page bakes with) and headings through the shared [HeadingRenderer.drawHeading] recipe, so a
 * thumbnail is the page in miniature, not an approximation.
 *
 * Thread-safe off Main (StaticLayout + plain canvas — the [HeadingRenderer] contract), which is
 * where the picker calls it: previews render async per grid page, behind placeholder cards.
 */
object PagePreview {

    /** Build the preview, or null when the sizes are unusable or the allocation failed (the card
     *  keeps its placeholder — a missing preview is never an error the user must see). */
    fun render(
        page: PickerPage,
        content: PageContent,
        outWidth: Int,
        outHeight: Int,
        density: Float,
        paint: TextPaint,
    ): Bitmap? {
        if (outWidth < 1 || outHeight < 1 || page.width <= 0) return null
        val bmp = try {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "preview ${outWidth}x$outHeight allocation failed for ${page.id}")
            return null
        }
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val scale = outWidth / page.width.toFloat()
        canvas.save()
        canvas.scale(scale, scale)
        for (h in content.headings) HeadingRenderer.drawHeading(canvas, h, density, paint)
        for (l in content.links) {
            for (h in l.headings) HeadingRenderer.drawHeading(canvas, h, density, paint)
            StrokeRasterizer.draw(canvas, l.strokes)
        }
        StrokeRasterizer.draw(canvas, content.strokes)
        canvas.restore()
        // The page's own edge, drawn ON the bitmap (eye-check #7): the miniature is fit-centred
        // into a band it rarely fills exactly, so a border on the ImageView gets overpainted by
        // the white paper wherever the two disagree — the outline must travel with the pixels.
        // Unscaled, inset half a stroke so all four 1 px edges land inside the bitmap.
        canvas.drawRect(0.5f, 0.5f, outWidth - 0.5f, outHeight - 0.5f, border)
        return bmp
    }

    private val border = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private const val TAG = "PagePreview"
}
