package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Canvas
import android.graphics.Color
import android.text.TextPaint
import com.symmetricalpalmtree.gpaper.core.render.ContentLayer
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer
import com.symmetricalpalmtree.gpaper.core.render.HitTarget
import com.symmetricalpalmtree.notesproutsn.core.markdown.HeadingTypography
import com.symmetricalpalmtree.notesproutsn.core.markdown.MarkdownDraw
import kotlin.math.roundToInt

/**
 * Draws the visible page's headings into g-paper's committed layer — the N2 `ContentRenderer`.
 *
 * **Below the ink** ([ContentLayer.BELOW_STROKES], the wizard's og-parity call): ink annotations
 * over a heading stay visible on top. [headings] is the screen's working copy, set on Main at the
 * two page-load sites and after every mutation; the engine re-records on `notifyContentChanged()`
 * (or on its own data-in calls), never per frame.
 *
 * Text goes through the N1 engine ([MarkdownDraw]) with the stored hash prefix intact — the parser
 * turns `"## Title"` into a heading block, the renderer applies the level's scale and bold over
 * one base [paint] ([HeadingTypography.BASE_SP]), and the line is END-ellipsized single-line. The
 * box was measured by [measure] at write time with the same pipeline, so pixels and stored bounds
 * cannot disagree.
 *
 * Implements the **live-drag pair** — the exclusion-aware [draw] plus [drawObject] — so a dragged
 * heading rides under the pen as its real self instead of a dashed ghost (and is not also painted
 * at its origin; see `ContentRenderer`'s contract).
 */
class HeadingRenderer(
    private val density: Float,
    scaledDensity: Float,
) : ContentRenderer {

    override val layer = ContentLayer.BELOW_STROKES

    /** The visible page's headings — read on Main and on the engine's re-record path only. */
    var headings: List<Heading> = emptyList()

    private val paint = basePaint(scaledDensity)

    override fun draw(canvas: Canvas) = draw(canvas, emptySet())

    override fun draw(canvas: Canvas, excludedContentIds: Set<String>) {
        for (h in headings) {
            if (h.id in excludedContentIds) continue
            drawHeading(canvas, h)
        }
    }

    override fun drawObject(canvas: Canvas, contentId: String): Boolean {
        val h = headings.firstOrNull { it.id == contentId } ?: return false
        drawHeading(canvas, h)
        return true
    }

    override fun hitTargets(): List<HitTarget> = headings.map { HitTarget(it.id, it.bounds) }

    private fun drawHeading(canvas: Canvas, h: Heading) = drawHeading(canvas, h, density, paint)

    companion object {

        /**
         * Draw one heading at its stored bounds — the single draw recipe, shared with
         * [LinkComposite] (a wrapped heading must look exactly like it did before the wrap).
         * [paint] comes from [basePaint]; thread-safe off a live view (StaticLayout only), so a
         * composite may build off Main.
         */
        fun drawHeading(canvas: Canvas, h: Heading, density: Float, paint: TextPaint) {
            val pad = HeadingTypography.paddingPx(density)
            val contentWidth = (h.width - 2 * pad).roundToInt()
            if (contentWidth <= 0) return
            MarkdownDraw.draw(
                canvas, h.text,
                x = h.x + pad, y = h.y + pad,
                widthPx = contentWidth,
                paint = paint, density = density,
                maxLines = 1,
            )
        }

        /**
         * The box for [text] (hash-prefixed) — natural single-line size plus the padding on every
         * side, as `(width, height)` in page px. **Free growth** (wizard): the measure width is
         * effectively unbounded, so the box takes the text's real width even past the page edge —
         * the overhang is simply not visible. Same [MarkdownDraw] pipeline as the draw path;
         * thread-safe (no view state), so a store write may size a heading off Main.
         */
        fun measure(text: String, density: Float, scaledDensity: Float): Pair<Float, Float> {
            val pad = HeadingTypography.paddingPx(density)
            val (w, h) = MarkdownDraw.measure(
                text, FREE_GROWTH_WIDTH_PX, basePaint(scaledDensity), density, singleLine = true,
            )
            return (w + 2 * pad) to (h + 2 * pad)
        }

        fun basePaint(scaledDensity: Float) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = HeadingTypography.BASE_SP * scaledDensity
        }

        /** Wide enough that no title ever wraps or ellipsizes; small enough for StaticLayout math. */
        private const val FREE_GROWTH_WIDTH_PX = 1_000_000
    }
}
