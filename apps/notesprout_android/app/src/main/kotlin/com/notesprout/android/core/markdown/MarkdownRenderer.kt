package com.notesprout.android.core.markdown

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import android.text.style.ReplacementSpan

/**
 * Converts a [Block] list from [MarkdownParser] into a [SpannableStringBuilder]
 * ready for [android.text.StaticLayout] or an EditText-based WYSIWYG editor.
 *
 * Supported subset: headings (h1-h6), bold, italic, strikethrough, unordered lists
 * with nesting, ordered lists with auto-renumbering, task checkboxes, blockquotes,
 * horizontal rules, and links (underlined display text, no click).
 *
 * Out of scope: inline code, code blocks, tables, images, HTML, underline-as-formatting.
 *
 * @param availableWidthPx content width in pixels — used to size [HorizontalRuleSpan].
 * @param density screen density from [android.util.DisplayMetrics.density] — used for
 *   dp-to-pixel conversions in list indents, blockquote stripes, and HR height.
 */
object MarkdownRenderer {

    fun render(
        blocks: List<Block>,
        availableWidthPx: Int,
        paint: TextPaint,
        density: Float,
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val indentStepPx = (16f * density).toInt()

        for (block in blocks) {
            val blockStart = sb.length
            when (block) {
                is Block.Heading -> renderHeading(sb, block, blockStart)
                is Block.Paragraph -> renderParagraph(sb, block)
                is Block.ListItem -> renderListItem(sb, block, blockStart, indentStepPx)
                is Block.Blockquote -> renderBlockquote(sb, block, blockStart, density)
                is Block.HorizontalRule -> renderHorizontalRule(sb, blockStart, availableWidthPx, density)
            }
        }

        return sb
    }

    // ── Block renderers ───────────────────────────────────────────────────────

    private fun renderHeading(sb: SpannableStringBuilder, block: Block.Heading, blockStart: Int) {
        val textStart = sb.length
        appendInlines(sb, block.inlines)
        val textEnd = sb.length
        sb.append('\n')

        val mult = headingSizeMultiplier(block.level)
        sb.setSpan(RelativeSizeSpan(mult), textStart, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), textStart, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun renderParagraph(sb: SpannableStringBuilder, block: Block.Paragraph) {
        appendInlines(sb, block.inlines)
        sb.append('\n')
    }

    private fun renderListItem(
        sb: SpannableStringBuilder,
        block: Block.ListItem,
        blockStart: Int,
        indentStepPx: Int,
    ) {
        val indentPx = (block.depth + 1) * indentStepPx
        val prefix = when {
            block.isTask && block.checked -> "☑ "   // ☑
            block.isTask -> "☐ "                    // ☐
            block.ordered -> "${block.displayNumber}. "
            else -> bulletGlyph(block.depth)
        }
        val textStart = sb.length
        sb.append(prefix)
        appendInlines(sb, block.inlines)
        sb.append('\n')
        val blockEnd = sb.length
        // LeadingMarginSpan is a ParagraphStyle; must span to the trailing \n.
        sb.setSpan(
            LeadingMarginSpan.Standard(indentPx, indentPx),
            textStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun renderBlockquote(
        sb: SpannableStringBuilder,
        block: Block.Blockquote,
        blockStart: Int,
        density: Float,
    ) {
        val textStart = sb.length
        appendInlines(sb, block.inlines)
        sb.append('\n')
        val blockEnd = sb.length
        val stripeWidth = (3f * density).toInt().coerceAtLeast(2)
        val gapWidth = (8f * density).toInt()
        sb.setSpan(
            QuoteSpan(Color.BLACK, stripeWidth, gapWidth),
            textStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun renderHorizontalRule(
        sb: SpannableStringBuilder,
        blockStart: Int,
        availableWidthPx: Int,
        density: Float,
    ) {
        val spanStart = sb.length
        sb.append('​') // zero-width space as the replacement span anchor
        val spanEnd = sb.length
        sb.append('\n')
        sb.setSpan(
            HorizontalRuleSpan(availableWidthPx, density),
            spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    // ── Inline renderer ───────────────────────────────────────────────────────

    internal fun appendInlines(sb: SpannableStringBuilder, inlines: List<Inline>) {
        for (inline in inlines) {
            val start = sb.length
            when (inline) {
                is Inline.Text -> sb.append(inline.text)
                is Inline.Bold -> {
                    appendInlines(sb, inline.children)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Italic -> {
                    appendInlines(sb, inline.children)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Strikethrough -> {
                    appendInlines(sb, inline.children)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Link -> {
                    sb.append(inline.displayText)
                    sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun headingSizeMultiplier(level: Int): Float = when (level) {
        1 -> 2.0f
        2 -> 1.75f
        3 -> 1.5f
        4 -> 1.25f
        5 -> 1.1f
        else -> 1.0f // h6 — same size as body, bold distinguishes it
    }

    private fun bulletGlyph(depth: Int): String = when (depth % 3) {
        0 -> "• " // •
        1 -> "◦ " // ◦
        else -> "▪ " // ▪
    }
}

// ── HorizontalRuleSpan ────────────────────────────────────────────────────────

/**
 * Replacement span that renders a 1dp inkBlack horizontal line spanning [widthPx].
 * Inserted as a single zero-width-space character so StaticLayout gives it a full
 * line of height.
 */
private class HorizontalRuleSpan(
    private val widthPx: Int,
    private val density: Float,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        fm?.let {
            val half = (paint.textSize / 2f).toInt().coerceAtLeast(4)
            it.ascent = -half
            it.descent = half
            it.top = it.ascent
            it.bottom = it.descent
        }
        return widthPx
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val mid = (top + bottom) / 2f
        val savedWidth = paint.strokeWidth
        paint.strokeWidth = density.coerceAtLeast(1f)
        canvas.drawLine(x, mid, x + widthPx, mid, paint)
        paint.strokeWidth = savedWidth
    }
}
