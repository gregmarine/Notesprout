package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The g-paper [ContentRenderer] bridge for the page's content objects (arc 4 / H1). Draws below the
 * ink; for each live object paints the cached bitmap at (x, y) when [ObjectRenderCache] holds one,
 * else a **dashed 1 px inkBlack placeholder rect** at its bounds — the look of an object whose
 * provider is absent, disabled or failing (still selectable, movable, deletable). Implements the
 * live-drag pair (`draw(canvas, excluded)` + `drawObject`) so a dragged object rides under the pen
 * instead of ghosting; `hitTargets()` = every live object's bounds, which is what makes objects
 * lasso-selectable.
 *
 * Reads [objects] (the screen's `liveObjects` mirror) on the main thread only — the paper calls in
 * on Main while re-recording the committed layer, never per frame. Paper coordinates throughout.
 */
class ObjectRenderer(
    private val objects: () -> Collection<PageObject>,
    private val cache: ObjectRenderCache,
    private val pageWidth: () -> Float,
    private val dpi: () -> Float,
) : ContentRenderer {

    override val layer: ContentLayer = ContentLayer.BELOW_STROKES

    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.BLACK
        pathEffect = DashPathEffect(floatArrayOf(DASH_PX, DASH_PX), 0f)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (o in objects()) if (o.id !in excludedContentIds) drawOne(canvas, o)
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val o = objects().firstOrNull { it.id == contentId } ?: return false
        drawOne(canvas, o)
        return true
    }

    override fun hitTargets(): List<HitTarget> = objects().map { HitTarget(it.id, it.bounds) }

    private fun drawOne(canvas: Canvas, o: PageObject) {
        val bmp = cache.get(o.id, o.payload, renderWidth(pageWidth(), o), dpi())
        if (bmp != null) {
            canvas.drawBitmap(bmp, o.x, o.y, bitmapPaint)
        } else {
            // Inset by half the stroke so the 1 px dash sits inside the bounds (and inside the hit rect).
            canvas.drawRect(o.x + 0.5f, o.y + 0.5f, o.x + o.width - 0.5f, o.y + o.height - 0.5f, placeholder)
        }
    }

    companion object {
        private const val DASH_PX = 6f

        /** The width an object may render into — the page's right edge minus its x (≥ 1, ≤ the image
         *  edge cap). The one function both the renderer's cache lookup and the render pass key on. */
        fun renderWidth(pageWidth: Float, o: PageObject): Int =
            (pageWidth - o.x).toInt().coerceIn(1, ExtensionContract.MAX_IMAGE_EDGE_PX)
    }
}
