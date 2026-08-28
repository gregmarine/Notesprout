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

    /**
     * The margin the composite renders beyond the link's bounds, on every side: a `Stroke.bounds`
     * is the tight bounds of its **points** (`Bounds.of(points)` — no width), but the rasterized
     * ink overhangs them by half the stroke width plus its round cap, so a bitmap cut exactly at
     * the union bounds shears the outermost strokes (eye-check #7: the "L"'s foot). Zero for a
     * heading-only link — a heading's box already includes its padding.
     */
    fun padOf(link: PageLink): Int {
        val maxWidth = link.strokes.maxOfOrNull { it.width } ?: return 0
        return ceil(maxWidth / 2f).toInt() + 1   // +1: anti-aliasing slop
    }

    /** The bitmap size [build] will produce for [link] — bounds plus [padOf] on each side, capped.
     *  The renderer's cache-reuse check compares against THIS, never the raw bounds. */
    fun sizeOf(link: PageLink): Pair<Int, Int> {
        val pad = padOf(link)
        return ceil(link.width).toInt().plus(2 * pad).coerceAtMost(MAX_EDGE_PX) to
            ceil(link.height).toInt().plus(2 * pad).coerceAtMost(MAX_EDGE_PX)
    }

    /** Build the composite, or null when the link has no drawable size or the allocation failed
     *  (the caller draws the dashed placeholder instead and does not retry until the next update).
     *  The bitmap is [padOf] larger than the bounds on every side — draw it at
     *  `(x - pad, y - pad)`, which [LinkRenderer.drawLink] does. */
    fun build(link: PageLink, density: Float, paint: TextPaint): Bitmap? {
        val (w, h) = sizeOf(link)
        if (w < 1 || h < 1) return null
        // ARGB_8888 and it must stay that way: unlike the card thumbnails, this bitmap is NOT
        // erased to white — it is drawn over the live page, so the paper has to show through
        // everywhere the wrapped ink isn't. RGB_565 has no alpha channel and would paint the
        // link's whole bounding box black.
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "composite ${w}x$h allocation failed for ${link.id}")
            return null
        }
        val pad = padOf(link)
        val canvas = Canvas(bmp)
        canvas.translate(pad - link.x, pad - link.y)
        for (heading in link.headings) HeadingRenderer.drawHeading(canvas, heading, density, paint)
        StrokeRasterizer.draw(canvas, link.strokes)
        return bmp
    }

    /** Bitmap edge cap — Paper's `MAX_IMAGE_EDGE_PX` (a wrap can't outgrow a page by much, but a
     *  foreign file's bounds are untrusted input). */
    const val MAX_EDGE_PX = 4_096

    private const val TAG = "LinkComposite"
}
