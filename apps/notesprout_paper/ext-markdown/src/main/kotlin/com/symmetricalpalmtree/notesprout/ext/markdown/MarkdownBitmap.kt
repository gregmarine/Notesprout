package com.symmetricalpalmtree.notesprout.ext.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.min

/**
 * Port of the original Notesprout `core/markdown/TextObjectRenderer.kt` measure + draw (arc 4 / H0),
 * turned into "markdown in, bitmap out": [MarkdownParser] → [MarkdownSpans] → [StaticLayout] drawn
 * onto a transparent `ARGB_8888` bitmap sized to the text's *natural* width (the widest line, capped
 * at the width the caller allows) plus [RENDER_PADDING] on all four sides. Black text, base size
 * [BASE_TEXT_SP] sp converted with the caller's dpi. `maxLines > 0` ellipsizes END past that many
 * lines (the heading's single line). Blank source → null (nothing to draw).
 *
 * The arithmetic ([Sizing]) is pure so it is JVM-tested; only [render] / [encodeWebp] touch Android.
 */
object MarkdownBitmap {

    /** Base text size — the original's text-object size. sp → px through the panel dpi, not a View. */
    const val BASE_TEXT_SP: Float = 24f

    /** sp → px for the base size at [dpi]. */
    fun textSizePx(dpi: Float): Float = BASE_TEXT_SP * dpi / 160f

    /** dp → px factor for the dp-based spans (indents, quote stripe, rule). */
    fun density(dpi: Float): Float = dpi / 160f

    /** Pure sizing arithmetic — the padding + cap rules of the contract. */
    object Sizing {
        /**
         * Rejects arguments outside the contract (`IllegalArgumentException`): source over
         * [ExtensionContract.MAX_MARKDOWN_CHARS], `maxWidthPx ≤ 0` or over the edge cap, `dpi ≤ 0`
         * or NaN, `maxLines < 0`, padding outside `0..RENDER_PADDING_MAX_PX`.
         */
        fun checkArgs(markdownLength: Int, maxWidthPx: Int, dpi: Float, maxLines: Int, paddingPx: Int) {
            require(markdownLength <= ExtensionContract.MAX_MARKDOWN_CHARS) { "markdown over MAX_MARKDOWN_CHARS ($markdownLength)" }
            require(maxWidthPx > 0 && maxWidthPx <= ExtensionContract.MAX_IMAGE_EDGE_PX) { "maxWidthPx out of range ($maxWidthPx)" }
            require(dpi > 0f && !dpi.isNaN()) { "dpi must be > 0 ($dpi)" }
            require(maxLines >= 0) { "maxLines must be >= 0 ($maxLines)" }
            require(paddingPx in 0..ExtensionContract.RENDER_PADDING_MAX_PX) { "paddingPx out of range ($paddingPx)" }
        }

        /** The width the text itself may use: the caller's width minus both paddings, never below 1 px. */
        fun contentWidth(maxWidthPx: Int, paddingPx: Int): Int = (maxWidthPx - 2 * paddingPx).coerceAtLeast(1)

        /**
         * The image size for a layout whose widest line is [naturalWidth] px and whose height is
         * [layoutHeight] px: content width = `min(naturalWidth, contentWidth)` (never below 1),
         * plus padding on both sides. Throws `IllegalArgumentException` if either edge would exceed
         * [ExtensionContract.MAX_IMAGE_EDGE_PX] (a tall document at a big dpi).
         */
        fun imageSize(naturalWidth: Int, layoutHeight: Int, maxWidthPx: Int, paddingPx: Int): Pair<Int, Int> {
            val w = min(naturalWidth, contentWidth(maxWidthPx, paddingPx)).coerceAtLeast(1) + 2 * paddingPx
            val h = layoutHeight.coerceAtLeast(1) + 2 * paddingPx
            require(w <= ExtensionContract.MAX_IMAGE_EDGE_PX && h <= ExtensionContract.MAX_IMAGE_EDGE_PX) {
                "rendered image exceeds MAX_IMAGE_EDGE_PX (${w}x$h)"
            }
            return w to h
        }
    }

    /**
     * Render [markdown] to a bitmap, or null when the source is blank / renders to nothing. Callers
     * are expected to have run [Sizing.checkArgs] (the service does; this method assumes valid args).
     */
    fun render(markdown: String, maxWidthPx: Int, dpi: Float, maxLines: Int, paddingPx: Int): Bitmap? {
        if (markdown.isBlank()) return null
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = textSizePx(dpi)
            density = density(dpi)
        }
        val contentWidth = Sizing.contentWidth(maxWidthPx, paddingPx)
        val layout = buildLayout(markdown, contentWidth, paint, density(dpi), maxLines) ?: return null
        val naturalWidth = (0 until layout.lineCount)
            .maxOfOrNull { i -> ceil(layout.getLineWidth(i).toDouble()).toInt() } ?: 0
        val (w, h) = Sizing.imageSize(naturalWidth, layout.height, maxWidthPx, paddingPx)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)   // transparent by default
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.translate(paddingPx.toFloat(), paddingPx.toFloat())
        canvas.clipRect(0, 0, w - 2 * paddingPx, h - 2 * paddingPx)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /** Lossless WEBP with alpha: `WEBP_LOSSLESS` exists from API 30; on API 29 (minSdk) `WEBP` at quality 100 is lossless. */
    fun encodeWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
                     else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 100, out)
        return out.toByteArray()
    }

    /** The original's `buildLayout` — parse, span, drop the trailing block newline, lay out. Null if nothing is left to draw. */
    private fun buildLayout(text: String, widthPx: Int, paint: TextPaint, density: Float, maxLines: Int): StaticLayout? {
        val blocks = MarkdownParser.parse(text)
        val spannable: SpannableStringBuilder = MarkdownSpans.render(blocks, widthPx, paint, density)
        // MarkdownSpans appends '\n' after every block. The final trailing newline makes StaticLayout
        // produce a spurious empty line, inflating the measured height. Trim it before building.
        var end = spannable.length
        while (end > 0 && spannable[end - 1] == '\n') end--
        if (end < spannable.length) spannable.delete(end, spannable.length)
        if (spannable.isEmpty()) return null
        val builder = StaticLayout.Builder.obtain(spannable, 0, spannable.length, paint, widthPx)
        if (maxLines > 0) {
            builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }
}
