package com.symmetricalpalmtree.notesproutsn.templates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.notebook.PreviewMath

/**
 * The Templates screen's card art (arc 13 / G1) — a **true miniature**: the card *is* the page,
 * scaled honestly, at the page's own aspect.
 *
 * That is the locked decision and it earns its cost. Density is what tells two variants apart, so
 * a card that squeezed the pattern to a fixed row count (what the library's cover placeholder does
 * — a *hint* at the paper) would draw every Grid the same. A dense grid reading as a grey wash is
 * what a dense grid looks like.
 *
 * Rendered off Main, cached by `id:stamp:width` so a generator's miniature is drawn once per
 * session and a saved variant's survives every page turn — the sentinels never change, and a row's
 * `updatedAt` is exactly what invalidates it. Cached bitmaps are **never recycled**: the cache is
 * the only owner, and a card that outlived its bitmap would draw a hole.
 */
object TemplateThumbnails {

    /**
     * The miniature for [card] on a card [cellWidthPx] wide, or null when there is nothing to draw.
     * [image] is a static template's stored bytes — the caller reads it (the one read that costs
     * pixels) and passes it in, so this function never touches the index.
     *
     * Safe off the main thread. Returns a bitmap owned by the cache.
     */
    fun bitmap(
        card: TemplateCard,
        cellWidthPx: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        dpi: Float,
        image: ByteArray? = null,
    ): Bitmap? {
        if (cellWidthPx < 1 || pageWidthPx < 1 || pageHeightPx < 1) return null
        // A place is not a paper: folders draw the folder card, not a page.
        if (card is TemplateCard.Folder || card is TemplateCard.Generated) return null
        val key = "${card.id}:${card.stamp}:$cellWidthPx"
        cache.get(key)?.let { return it }
        val bmp = render(card, cellWidthPx, pageWidthPx, pageHeightPx, dpi, image) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** Drop everything — the screen calls this when the page size it renders against changes. */
    fun clear() = cache.evictAll()

    private fun render(
        card: TemplateCard,
        cellWidthPx: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        dpi: Float,
        image: ByteArray?,
    ): Bitmap? {
        // The page's aspect, clamped against a degenerate size — the same arithmetic the link
        // picker's page cards use, so a template card and a page card are the same object on screen.
        val (w, h) = PreviewMath.renderSize(cellWidthPx, pageWidthPx, pageHeightPx)
        val scale = w / pageWidthPx.toFloat()

        // A generator (and a saved variant of one) is drawn from the same arithmetic the page bakes
        // with, scaled — never from stored pixels, which is why a miniature costs no blob read.
        val kind = when (card) {
            is TemplateCard.Generator -> card.kind
            is TemplateCard.Static -> card.baseKind
            else -> null
        }
        val bmp = kind?.let { BuiltInTemplates.miniature(it, w, h, scale, dpi) } ?: blankPage(w, h) ?: return null

        // Imported pixels: fit-centred on the white page. The other two fit modes arrive with the
        // import flow that can produce them (G4); until then a static row is either a saved
        // generator variant (handled above) or nothing at all.
        if (kind == null && image != null) drawFitted(bmp, image)

        // The page's own edge, drawn ON the bitmap — a border on the ImageView is overpainted by
        // the fit-centred paper wherever the two disagree (the page-card lesson). Inset half a
        // stroke so all four 1 px edges land inside the bitmap.
        Canvas(bmp).drawRect(0.5f, 0.5f, bmp.width - 0.5f, bmp.height - 0.5f, border)
        return bmp
    }

    private fun blankPage(w: Int, h: Int): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "thumbnail ${w}x$h allocation failed")
        null
    }

    /** Decode [image] bounded (untrusted bytes out of a database) and fit it centred into [page]. */
    private fun drawFitted(page: Bitmap, image: ByteArray) {
        val src = Bitmaps.decodeBounded(image, DECODE_EDGE) ?: return
        val scale = minOf(page.width.toFloat() / src.width, page.height.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (page.width - w) / 2f
        val top = (page.height - h) / 2f
        Canvas(page).drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        src.recycle()
    }

    private val border = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    /** Two grid pages' worth of cards, which is what a page turn back and forth costs. */
    private val cache = LruCache<String, Bitmap>(32)

    /** Card art is small; the bound only protects against an oversized stored image. */
    private const val DECODE_EDGE = 1024

    private const val TAG = "TemplateThumbnails"
}
