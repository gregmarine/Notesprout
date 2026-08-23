package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextPaint
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import kotlin.math.ceil

/**
 * Builds a link's composite bitmap (arc 6 / K1): the wrapped content rendered core-side at 1:1
 * page px, sized to the link's bounds. Heading children first (below the ink — the paper's own
 * layering, via [HeadingRenderer.drawHeading] so a wrapped heading looks exactly as it did on the
 * page); then the wrapped strokes through g-paper's [StrokeRasterizer] — the same internal
 * renderer live ink bakes with, so the composite is pixel-identical to how the strokes looked
 * before the wrap. The underline chrome is **not** baked in — [LinkRenderer] draws it live from
 * the link's decoded chrome, so a chrome edit never invalidates the composite.
 *
 * The composite is translation-invariant (children ride the bounds), so a move never rebuilds it;
 * it changes only when the wrapped content set does (create / unlink / undo-redo — all of which
 * hand the renderer a fresh [PageLink] via a page reload or an explicit update).
 */
object LinkComposite {

    /** Build the composite, or null when the link has no drawable size or the allocation failed
     *  (the caller draws the dashed placeholder instead and does not retry until the next update). */
    fun build(link: PageLink, density: Float, paint: TextPaint): Bitmap? {
        val w = ceil(link.width).toInt().coerceAtMost(MAX_EDGE_PX)
        val h = ceil(link.height).toInt().coerceAtMost(MAX_EDGE_PX)
        if (w < 1 || h < 1) return null
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "composite ${w}x$h allocation failed for ${link.id}")
            return null
        }
        val canvas = Canvas(bmp)
        canvas.translate(-link.x, -link.y)
        for (heading in link.headings) HeadingRenderer.drawHeading(canvas, heading, density, paint)
        StrokeRasterizer.draw(canvas, link.strokes)
        return bmp
    }

    /** Bitmap edge cap — Paper's `MAX_IMAGE_EDGE_PX` (a wrap can't outgrow a page by much, but a
     *  foreign file's bounds are untrusted input). */
    const val MAX_EDGE_PX = 4_096

    private const val TAG = "LinkComposite"
}
