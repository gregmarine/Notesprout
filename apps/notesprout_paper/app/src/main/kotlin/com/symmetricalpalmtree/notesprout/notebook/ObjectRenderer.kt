package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import kotlin.math.ceil

/**
 * The g-paper [ContentRenderer] bridge for the page's content objects (arc 4 / H1) **and links**
 * (arc 7 / L1). Draws below the ink; for each live object paints the cached bitmap at (x, y) when
 * [ObjectRenderCache] holds one, else a **dashed 1 px inkBlack placeholder rect** at its bounds —
 * the look of an object whose provider is absent, disabled or failing (still selectable, movable,
 * deletable). A link draws its cached composite (built by [LinkComposite], same placeholder while
 * absent) plus a live **1 dp underline** across its bounds' bottom when the session chrome map says
 * so — the underline is never baked into the composite, so a chrome change never invalidates it,
 * and with the extension missing the map is empty and the content renders bare (L0 Q4). Implements
 * the live-drag pair (`draw(canvas, excluded)` + `drawObject`) so a dragged object rides under the
 * pen instead of ghosting; `hitTargets()` = every live object's and link's bounds, which is what
 * makes both lasso-selectable.
 *
 * Reads [objects] / [links] (the screen's mirrors) on the main thread only — the paper calls in
 * on Main while re-recording the committed layer, never per frame. Paper coordinates throughout.
 */
class ObjectRenderer(
    private val objects: () -> Collection<PageObject>,
    private val links: () -> Collection<PageLink>,
    private val cache: ObjectRenderCache,
    private val pageWidth: () -> Float,
    private val dpi: () -> Float,
    /** The session chrome flag for a link id — `LINK_CHROME_NONE` when unknown (extension missing). */
    private val linkChrome: (String) -> Int,
) : ContentRenderer {

    override val layer: ContentLayer = ContentLayer.BELOW_STROKES

    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.BLACK
        pathEffect = DashPathEffect(floatArrayOf(DASH_PX, DASH_PX), 0f)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val underline = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (o in objects()) if (o.id !in excludedContentIds) drawOne(canvas, o)
        for (l in links()) if (l.id !in excludedContentIds) drawOne(canvas, l)
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        objects().firstOrNull { it.id == contentId }?.let { drawOne(canvas, it); return true }
        links().firstOrNull { it.id == contentId }?.let { drawOne(canvas, it); return true }
        return false
    }

    override fun hitTargets(): List<HitTarget> =
        objects().map { HitTarget(it.id, it.bounds) } + links().map { HitTarget(it.id, it.bounds) }

    private fun drawOne(canvas: Canvas, o: PageObject) {
        val bmp = cache.get(o.id, o.payload, renderWidth(pageWidth(), o), dpi())
        if (bmp != null) {
            canvas.drawBitmap(bmp, o.x, o.y, bitmapPaint)
        } else {
            // Inset by half the stroke so the 1 px dash sits inside the bounds (and inside the hit rect).
            canvas.drawRect(o.x + 0.5f, o.y + 0.5f, o.x + o.width - 0.5f, o.y + o.height - 0.5f, placeholder)
        }
    }

    private fun drawOne(canvas: Canvas, l: PageLink) {
        val bmp = cache.get(l.id, LINK_COMPOSITE_KEY, linkWidth(l), dpi())
        if (bmp != null) {
            canvas.drawBitmap(bmp, l.x, l.y, bitmapPaint)
        } else {
            canvas.drawRect(l.x + 0.5f, l.y + 0.5f, l.x + l.width - 0.5f, l.y + l.height - 0.5f, placeholder)
        }
        if (linkChrome(l.id) == ExtensionContract.LINK_CHROME_UNDERLINE) {
            val px = (dpi() / 160f).coerceAtLeast(1f)   // 1 dp, never sub-px
            underline.strokeWidth = px
            val y = l.y + l.height - px / 2f            // inside the bounds' clearance band
            canvas.drawLine(l.x, y, l.x + l.width, y, underline)
        }
    }

    companion object {
        private const val DASH_PX = 6f

        /** The composite cache entry's payload key: the composite depends on the wrapped content, not
         *  the (opaque) payload — invalidation is explicit ([RenderFlow]), so the key is a constant. */
        const val LINK_COMPOSITE_KEY = ""

        /** The width an object may render into — the page's right edge minus its x (≥ 1, ≤ the image
         *  edge cap). The one function both the renderer's cache lookup and the render pass key on. */
        fun renderWidth(pageWidth: Float, o: PageObject): Int =
            (pageWidth - o.x).toInt().coerceIn(1, ExtensionContract.MAX_IMAGE_EDGE_PX)

        /** A link composite's width key — the bounds' width, capped like every image. The one
         *  function the renderer's lookup and [RenderFlow]'s build both key on. */
        fun linkWidth(l: PageLink): Int =
            ceil(l.width).toInt().coerceIn(1, ExtensionContract.MAX_IMAGE_EDGE_PX)
    }
}
