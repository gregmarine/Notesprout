package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import kotlin.math.ceil

/**
 * Builds a link's composite bitmap (arc 7 / L1): the wrapped content rendered core-side at 1:1 page
 * px, sized to the link's bounds. Child objects first (below the ink, the paper's own layering),
 * each as its cached rendered bitmap or the standard dashed placeholder when its provider is absent
 * or failing; then the wrapped strokes through g-paper's [StrokeRasterizer] (0.1.4) — the same
 * internal renderer live ink bakes with, so the composite is pixel-identical to how the strokes
 * looked before the wrap (risk 3). The underline is **not** baked in — [ObjectRenderer] draws it
 * live from the session chrome map, so a chrome change never invalidates the composite.
 *
 * The composite is translation-invariant (children ride the bounds), so a move never rebuilds it;
 * it changes only when the wrapped content set does (create / unlink) or a child object's rendered
 * bitmap lands ([RenderFlow] invalidates then).
 */
object LinkComposite {

    /** Build the composite, or null when the link has no drawable size or the allocation failed
     *  (the caller keeps the dashed placeholder and does not retry until the next page load). */
    fun build(link: PageLink, childBitmap: (PageObject) -> Bitmap?): Bitmap? {
        val w = ceil(link.width).toInt().coerceAtMost(ExtensionContract.MAX_IMAGE_EDGE_PX)
        val h = ceil(link.height).toInt().coerceAtMost(ExtensionContract.MAX_IMAGE_EDGE_PX)
        if (w < 1 || h < 1) return null
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "composite ${w}x$h allocation failed for ${link.id}")
            return null
        }
        val canvas = Canvas(bmp)
        canvas.translate(-link.x, -link.y)
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (o in link.objects) {
            val ob = childBitmap(o)
            if (ob != null && !ob.isRecycled) {
                canvas.drawBitmap(ob, o.x, o.y, bitmapPaint)
            } else {
                canvas.drawRect(o.x + 0.5f, o.y + 0.5f, o.x + o.width - 0.5f, o.y + o.height - 0.5f, placeholder())
            }
        }
        StrokeRasterizer.draw(canvas, link.strokes)
        return bmp
    }

    private fun placeholder() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.BLACK
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private const val TAG = "LinkComposite"
}
