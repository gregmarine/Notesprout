package com.notesprout.android.core.markdown

import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import com.notesprout.android.data.TextRender

/**
 * Canvas renderer for [TextRender] objects.
 *
 * Parses the markdown source in [TextRender.text] via [MarkdownParser], converts to a
 * [android.text.SpannableStringBuilder] via [MarkdownRenderer], then draws the result
 * with [StaticLayout] onto the canvas at the position given by [TextRender.boundingBox].
 *
 * The background is fully transparent — template, strokes, and any content behind the
 * bounding box remain visible. This matches the spec that text objects do NOT fill white.
 *
 * [measure] is the shared sizing function for both canvas rendering and future editor
 * dialogs; call it to compute the natural height of a given markdown string at a given
 * width without touching any canvas.
 */
object TextObjectRenderer {

    /**
     * Draw [textRender] onto [canvas] at the position defined by its [TextRender.boundingBox].
     *
     * @param widthPx  available content width in pixels (caps wrapping; should not exceed page width)
     * @param paint    text paint configured with desired size and inkBlack color
     * @param density  screen density for dp→px conversion inside [MarkdownRenderer]
     */
    fun draw(
        canvas: Canvas,
        textRender: TextRender,
        widthPx: Int,
        paint: TextPaint,
        density: Float,
    ) {
        if (textRender.text.isBlank()) return
        val layout = buildLayout(textRender.text, widthPx, paint, density)
        canvas.save()
        canvas.translate(textRender.boundingBox.left, textRender.boundingBox.top)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Compute the (width, height) in pixels that [text] occupies when rendered at
     * [availableWidthPx].
     *
     * Width is the natural content width — the maximum line width across all lines in the
     * StaticLayout, capped at [availableWidthPx]. This is narrower than [availableWidthPx]
     * for short content (e.g. "Hello world" returns ~text-measurement-width, not page width).
     * [StaticLayout.width] always equals the constraint passed to the builder, so we cannot
     * use it directly for bounding-box sizing.
     *
     * Thread-safe — does not access any View state.
     */
    fun measure(
        text: String,
        availableWidthPx: Int,
        paint: TextPaint,
        density: Float,
    ): Pair<Int, Int> {
        if (text.isBlank()) return Pair(0, 0)
        val layout = buildLayout(text, availableWidthPx, paint, density)
        val naturalWidth = (0 until layout.lineCount)
            .maxOfOrNull { i -> kotlin.math.ceil(layout.getLineWidth(i).toDouble()).toInt() }
            ?: 0
        return Pair(naturalWidth.coerceAtMost(availableWidthPx), layout.height)
    }

    private fun buildLayout(
        text: String,
        widthPx: Int,
        paint: TextPaint,
        density: Float,
    ): StaticLayout {
        val blocks = MarkdownParser.parse(text)
        val spannable = MarkdownRenderer.render(blocks, widthPx, paint, density)
        // MarkdownRenderer appends '\n' after every block. The final trailing newline
        // makes StaticLayout produce a spurious empty line, inflating the measured height.
        // Trim it before building the layout so height matches actual content.
        var end = spannable.length
        while (end > 0 && spannable[end - 1] == '\n') end--
        if (end < spannable.length) spannable.delete(end, spannable.length)
        return StaticLayout.Builder
            .obtain(spannable, 0, spannable.length, paint, widthPx)
            .build()
    }
}
