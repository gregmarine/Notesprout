package com.symmetricalpalmtree.notesproutsn.markdown

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * [Block] list → a spanned [CharSequence] ready for [android.text.StaticLayout].
 *
 * Chrome-free by design: no colour beyond ink black, no backgrounds, nothing decorative. Links get
 * an underline and keep their url in the model, but nothing here makes them tappable.
 *
 * Every block ends with a `\n` — paragraph-style spans (indent, quote stripe) only take effect if
 * they reach the line terminator, so callers that measure must trim the trailing newlines
 * themselves (see [MarkdownDraw]).
 *
 * @param availableWidthPx content width in px; the horizontal rule is drawn across it.
 * @param density [android.util.DisplayMetrics.density], for the dp-based indents and stripes.
 * @param blockGapPx height of the blank line inserted **between** blocks. 0 (the default) packs
 *   blocks tight, which is what an on-page object wants; a document view passes a real gap.
 */
object MarkdownRenderer {

    private const val INDENT_STEP_DP = 16f
    private const val QUOTE_STRIPE_DP = 3f
    private const val QUOTE_GAP_DP = 8f

    /** Zero-width space: the anchor character the horizontal rule's replacement span replaces. */
    private const val RULE_ANCHOR = '​'

    fun render(
        blocks: List<Block>,
        availableWidthPx: Int,
        paint: TextPaint,
        density: Float,
        blockGapPx: Int = 0,
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val indentStepPx = (INDENT_STEP_DP * density).toInt()

        for ((index, block) in blocks.withIndex()) {
            if (blockGapPx > 0 && index > 0) appendGap(sb, blockGapPx)
            when (block) {
                is Block.Heading -> appendHeading(sb, block)
                is Block.Paragraph -> {
                    appendInlines(sb, block.inlines)
                    sb.append('\n')
                }
                is Block.ListItem -> appendListItem(sb, block, indentStepPx)
                is Block.Blockquote -> appendBlockquote(sb, block, density)
                is Block.HorizontalRule -> appendRule(sb, availableWidthPx, density)
            }
        }
        return sb
    }

    /**
     * A blank line sized to [gapPx]. Sizing the lone `\n` sizes the line it sits on, which is the
     * only way to get vertical space out of a StaticLayout without a custom line-spacing pass.
     */
    private fun appendGap(sb: SpannableStringBuilder, gapPx: Int) {
        val start = sb.length
        sb.append('\n')
        sb.setSpan(AbsoluteSizeSpan(gapPx, false), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    private fun appendHeading(sb: SpannableStringBuilder, block: Block.Heading) {
        val start = sb.length
        appendInlines(sb, block.inlines)
        val end = sb.length
        sb.append('\n')
        // Relative, not absolute: the caller's paint size stays the single source of body size.
        sb.setSpan(RelativeSizeSpan(headingScale(block.level)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendListItem(sb: SpannableStringBuilder, block: Block.ListItem, indentStepPx: Int) {
        val prefix = when {
            block.isTask && block.checked -> "☑ "
            block.isTask -> "☐ "
            block.ordered -> "${block.displayNumber}. "
            else -> bullet(block.depth)
        }
        val start = sb.length
        sb.append(prefix)
        appendInlines(sb, block.inlines)
        sb.append('\n')
        // LeadingMarginSpan is a ParagraphStyle: it must cover the trailing newline or Android
        // silently drops it. Same margin for first and rest, so wrapped text lines up under itself.
        val indentPx = (block.depth + 1) * indentStepPx
        sb.setSpan(
            LeadingMarginSpan.Standard(indentPx, indentPx),
            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun appendBlockquote(sb: SpannableStringBuilder, block: Block.Blockquote, density: Float) {
        val start = sb.length
        appendInlines(sb, block.inlines)
        sb.append('\n')
        // Also a ParagraphStyle — spans through the newline for the same reason as the indent.
        // The stripe floors at 2 px: sub-pixel rules disappear entirely on e-ink.
        sb.setSpan(
            QuoteSpan(
                Color.BLACK,
                (QUOTE_STRIPE_DP * density).toInt().coerceAtLeast(2),
                (QUOTE_GAP_DP * density).toInt(),
            ),
            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun appendRule(sb: SpannableStringBuilder, availableWidthPx: Int, density: Float) {
        val start = sb.length
        sb.append(RULE_ANCHOR)
        val end = sb.length
        sb.append('\n')
        sb.setSpan(HorizontalRuleSpan(availableWidthPx, density), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ── Inlines ───────────────────────────────────────────────────────────────

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
                is Inline.Code -> {
                    sb.append(inline.text)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Link -> {
                    // Underline only — colour is not available to say "link", and nothing follows it.
                    sb.append(inline.displayText)
                    sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * One scale table for the whole app: a heading object drawn straight from [HeadingTypography]
     * and the same heading arriving through this renderer must come out the same size.
     */
    private fun headingScale(level: Int): Float = HeadingTypography.scaleFor(level)

    private fun bullet(depth: Int): String = when (depth % 3) {
        0 -> "• "  // bullet
        1 -> "◦ "  // white bullet
        else -> "▪ " // black small square
    }
}

/**
 * The `---` rule: a full-width hairline, drawn on the line the zero-width anchor occupies.
 *
 * A [ReplacementSpan] rather than a border: it is the only way to claim a whole line of height and
 * paint into it from inside a StaticLayout.
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
        // Half a text-line of air above and below, never less than 4 px, so the rule reads as a
        // separator instead of crowding the blocks either side of it.
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
        // The paint belongs to the layout — borrow the stroke width and hand it back untouched.
        val saved = paint.strokeWidth
        paint.strokeWidth = density.coerceAtLeast(1f)
        canvas.drawLine(x, mid, x + widthPx, mid, paint)
        paint.strokeWidth = saved
    }
}
