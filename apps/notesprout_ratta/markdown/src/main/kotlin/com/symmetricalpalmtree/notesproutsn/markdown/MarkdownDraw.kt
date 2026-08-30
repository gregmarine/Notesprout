package com.symmetricalpalmtree.notesproutsn.markdown

import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil

/**
 * Measure and draw a markdown string on a canvas.
 *
 * The one place that ties [MarkdownParser] and [MarkdownRenderer] to a [StaticLayout], so the
 * layout is built identically whether a caller is sizing a bounding box or painting a page.
 * Measurement and drawing that disagreed would show up as text clipped by its own box.
 *
 * Nothing here touches View state, so it is safe off the main thread — sizing a heading during a
 * store write must not wait for a frame.
 */
object MarkdownDraw {

    /**
     * Natural size of [text] laid out at [availableWidthPx], as `(width, height)` in px.
     *
     * Width is the widest line, capped at [availableWidthPx] — not [StaticLayout.width], which
     * always echoes the constraint back and would make every short heading a full page wide.
     *
     * @param singleLine caps the layout to one END-ellipsized line. Headings use this: a title is
     *   always exactly one line tall, however much text is behind it.
     */
    fun measure(
        text: String,
        availableWidthPx: Int,
        paint: TextPaint,
        density: Float,
        singleLine: Boolean = false,
    ): Pair<Int, Int> {
        if (text.isBlank()) return 0 to 0
        val layout = buildLayout(
            text, availableWidthPx, paint, density,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        )
        val widest = (0 until layout.lineCount)
            .maxOfOrNull { ceil(layout.getLineWidth(it).toDouble()).toInt() }
            ?: 0
        return widest.coerceAtMost(availableWidthPx) to layout.height
    }

    /**
     * Draws [text] with its top-left corner at ([x], [y]).
     *
     * Transparent background by contract — template lines and ink behind the text stay visible.
     */
    fun draw(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        widthPx: Int,
        paint: TextPaint,
        density: Float,
        maxLines: Int = Int.MAX_VALUE,
    ) {
        if (text.isBlank()) return
        val layout = buildLayout(text, widthPx, paint, density, maxLines)
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(
        text: String,
        widthPx: Int,
        paint: TextPaint,
        density: Float,
        maxLines: Int,
    ): StaticLayout {
        val spanned = MarkdownRenderer.render(MarkdownParser.parse(text), widthPx, paint, density)
        // Every block ends in '\n', so the last one leaves a trailing newline that StaticLayout
        // turns into a real empty line — a measured height one line taller than the content.
        var end = spanned.length
        while (end > 0 && spanned[end - 1] == '\n') end--
        if (end < spanned.length) spanned.delete(end, spanned.length)

        val builder = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, widthPx)
        if (maxLines < Int.MAX_VALUE) {
            builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }
}
