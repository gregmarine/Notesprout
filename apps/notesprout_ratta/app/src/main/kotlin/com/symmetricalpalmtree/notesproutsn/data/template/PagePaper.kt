package com.symmetricalpalmtree.notesproutsn.data.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps

/**
 * Paper the app can actually put on a page, and the one place it is turned into pixels
 * (arc 13 / G3).
 *
 * A pick on the Templates screen is a *card*; a page needs a bitmap at its own size and a token to
 * be known by. [PaperSource] is what the two have in common, and every path that paints a page —
 * creating a notebook, re-papering a page — goes through [render] and [token] so there is exactly
 * one answer to "what does this paper look like at this size" and exactly one to "is this the same
 * paper as that".
 *
 * **Two kinds and no third**, as the arc locked: arithmetic ([PaperSource.BuiltIn]) or stored
 * pixels ([PaperSource.Image]). [PaperSource.Blank] is neither — it is the absence of a template
 * row, and both functions say so.
 */
sealed class PaperSource {

    /** No template row at all; the page's `refId` stays `""`. */
    object Blank : PaperSource()

    /** Lined / Dotted / Grid, drawn from [TemplateGeometry] at the page's size. */
    data class BuiltIn(val kind: TemplateKind) : PaperSource()

    /**
     * An imported picture and the [TemplateFit] mode it is laid onto the page with. [bytes] are the
     * **original** stored bytes, never a previous render — one row has to land correctly on pages
     * of different sizes, and re-rendering a render would compound the resampling.
     */
    class Image(val bytes: ByteArray, val fit: Int) : PaperSource()
}

object PagePaper {

    /**
     * The `.soil` token this paper is filed under ([TemplateToken]) — `""` for blank, the kind's
     * own name for a built-in, `IMG#…` for a picture.
     */
    fun token(paper: PaperSource): String = when (paper) {
        PaperSource.Blank -> ""
        is PaperSource.BuiltIn -> TemplateToken.of(paper.kind)
        is PaperSource.Image -> TemplateToken.ofImage(paper.bytes, paper.fit)
    }

    /**
     * The page-sized bitmap, or null when there is nothing to draw — blank paper, a degenerate page
     * size, or bytes that will not decode. A null means the caller writes **no template row**
     * rather than one naming paper it cannot draw (the arc-12 rule, generalised).
     *
     * [widthPx] / [heightPx] are the **page's** own size, never the screen's.
     */
    fun render(paper: PaperSource, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? = when (paper) {
        PaperSource.Blank -> null
        is PaperSource.BuiltIn -> BuiltInTemplates.render(paper.kind, widthPx, heightPx, dpi)
        is PaperSource.Image -> renderImage(paper, widthPx, heightPx)
    }

    /** Decode bounded (untrusted bytes out of a database), then one blit onto a white page. */
    private fun renderImage(paper: PaperSource.Image, widthPx: Int, heightPx: Int): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        // Twice the page's long edge: enough that the downscale never softens a rule, bounded so a
        // corrupt or oversized blob cannot allocate its way through a memory-tight e-ink device.
        val bound = maxOf(widthPx, heightPx) * 2
        val src = Bitmaps.decodeBounded(paper.bytes, bound) ?: return null
        return try {
            val plan = TemplateFit.plan(paper.fit, src.width, src.height, widthPx, heightPx)
                ?: return null
            val page = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            page.eraseColor(Color.WHITE)
            Canvas(page).drawBitmap(
                src,
                Rect(
                    plan.src.left.toInt(), plan.src.top.toInt(),
                    plan.src.right.toInt(), plan.src.bottom.toInt(),
                ),
                RectF(plan.dst.left, plan.dst.top, plan.dst.right, plan.dst.bottom),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            page
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "page paper ${widthPx}x$heightPx allocation failed")
            null
        } finally {
            src.recycle()
        }
    }

    private const val TAG = "PagePaper"
}
