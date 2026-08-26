package com.symmetricalpalmtree.notesproutsn.data.template

/** The four built-in page backgrounds. [BLANK] writes no template row at all. */
enum class TemplateKind { BLANK, LINED, DOTTED, GRID }

/**
 * Everything a [TemplateSpec] resolves to on one page, in pixels — **the whole drawing decision,
 * made without a `Canvas`**. [BuiltInTemplates] takes one of these and only holds the brush, which
 * is what keeps the geometry that gets baked into every notebook JVM-testable.
 *
 * The content rect is the page minus the insets; the insets are blank space and the pattern stops
 * short of them, so every rule is drawn between [left]/[right] and every column between
 * [top]/[bottom] rather than edge to edge.
 */
data class TemplatePlan(
    val kind: TemplateKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Column positions — verticals on Grid, dot columns on Dotted, empty on Lined. */
    val xs: List<Float>,
    /** Row positions — rules on Lined, horizontals on Grid, dot rows on Dotted. */
    val ys: List<Float>,
    val lineWidthPx: Float,
    val dotRadiusPx: Float,
    /** Where the margin rule goes, or null when it is off. */
    val marginRuleX: Float?,
    /** 0…255 grey the whole pattern paints in; 0 is black. */
    val grey: Int,
)

/** The page rect the pattern is drawn inside: the page minus its insets. */
data class ContentRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where the rules, dots and grid lines go — **pure arithmetic, no `android.graphics`**, so the
 * geometry that ends up baked into every notebook is JVM-testable.
 *
 * Everything is derived from physical millimetres converted at the panel's real dpi. Paper is
 * measured in millimetres, not pixels, so a template must look the same size on any device — and
 * a *scaled* rendering (a card miniature, the options screen's preview) is just the same page at a
 * smaller **effective dpi**, which is why nothing here takes a scale factor.
 *
 * Two origins, deliberately different:
 *  - Lined rows start at **2 × spacing** below the content top — a writing sheet wants a margin
 *    above the first rule.
 *  - Grid and dot positions start at **1 × spacing** and are symmetric in both axes. A grid must
 *    not borrow the lined top margin or its top row of cells comes out double height.
 *
 * Arc 13 / G2 generalised the module from four fixed kinds to [TemplateSpec], and the one rule
 * that governed the change: **a stock spec must resolve to exactly what the four kinds resolved to
 * before it existed** — same origins, same 8 mm, same thickness, same dot radius, bit for bit.
 * `TemplateGeometryTest` pins it against hardcoded numbers, not against a second live definition.
 */
object TemplateGeometry {

    const val SPACING_MM = 8f

    /** Millimetres to pixels at [dpi] — the one conversion, used by everything physical. */
    fun mmToPx(mm: Float, dpi: Float): Float = mm * dpi / 25.4f

    /** 8 mm in pixels at [dpi]. */
    fun spacingPx(dpi: Float): Float = mmToPx(SPACING_MM, dpi)

    /** Stock rule thickness: one mdpi pixel, never thinner than 1 px (≈ 2 px on a 300 ppi panel). */
    fun lineWidthPx(dpi: Float): Float = maxOf(1f, mmToPx(TemplateSpec.STOCK_THICKNESS_MM, dpi))

    /** Stock dot radius: 2 px at mdpi, never smaller than 1 px (≈ 3.75 px, ~0.6 mm dot on a
     *  300 ppi panel) — Paper's on-device finding: a 1.5 px-authored dot read as faint grey. */
    fun dotRadiusPx(dpi: Float): Float = maxOf(1f, mmToPx(TemplateSpec.STOCK_DOT_MM / 2f, dpi))

    /** Lined: horizontal rules, first one a top margin (one spacing) below the first grid step. */
    fun linePositions(heightPx: Int, spacingPx: Float): List<Float> =
        steps(from = spacingPx * 2f, limit = heightPx.toFloat(), step = spacingPx)

    /** Grid verticals — first at one spacing, uniform cells across. */
    fun gridPositionsX(widthPx: Int, spacingPx: Float): List<Float> =
        steps(from = spacingPx, limit = widthPx.toFloat(), step = spacingPx)

    /** Grid horizontals — symmetric with [gridPositionsX]; never reuse [linePositions] here. */
    fun gridPositionsY(heightPx: Int, spacingPx: Float): List<Float> =
        steps(from = spacingPx, limit = heightPx.toFloat(), step = spacingPx)

    /** Dotted: the intersections of the same grid, row-major. */
    fun dotPositions(widthPx: Int, heightPx: Int, spacingPx: Float): List<Pair<Float, Float>> {
        val xs = gridPositionsX(widthPx, spacingPx)
        val out = ArrayList<Pair<Float, Float>>(xs.size * 8)
        for (y in gridPositionsY(heightPx, spacingPx)) for (x in xs) out.add(x to y)
        return out
    }

    // ── Specs (arc 13 / G2) ──────────────────────────────────────────────────

    /**
     * [spec] resolved onto a [widthPx] × [heightPx] page at [dpi]. Render a **miniature** by
     * passing the miniature's own size and `dpi × scale`: a count stays a count, a millimetre
     * shrinks, and no caller has to scale five things and forget the sixth.
     *
     * Insets that would leave no paper are dropped as a pair — see [contentRect].
     */
    fun plan(spec: TemplateSpec, widthPx: Int, heightPx: Int, dpi: Float): TemplatePlan {
        val s = spec.sanitized()
        val (left, top, right, bottom) = contentRect(s, widthPx, heightPx, dpi)

        // Lined rules sit one extra spacing down; grid and dot rows do not. The same number is the
        // divisor in count mode, so "27 lines" and "8 mm" agree about where the pattern begins.
        val leading = if (s.kind == TemplateKind.LINED) 2 else 1
        val ySpacing = resolve(s.rows, bottom - top, leading, dpi)
        val xSpacing = resolve(s.cols, right - left, 1, dpi)

        val ys = steps(from = top + ySpacing * leading, limit = bottom, step = ySpacing)
        val xs = if (s.kind == TemplateKind.LINED) emptyList()
                 else steps(from = left + xSpacing, limit = right, step = xSpacing)

        val lineWidth = maxOf(1f, mmToPx(s.thicknessMm, dpi))
        return TemplatePlan(
            kind = s.kind,
            left = left, top = top, right = right, bottom = bottom,
            xs = xs, ys = ys,
            lineWidthPx = lineWidth,
            dotRadiusPx = maxOf(1f, mmToPx(s.dotMm / 2f, dpi)),
            // Nudged in by half a stroke when the inset is zero, or half the rule falls off the page.
            marginRuleX = if (s.marginRule) maxOf(left, lineWidth / 2f) else null,
            grey = TemplateSpec.greyFor(s.shade),
        )
    }

    /**
     * How many features [spec] actually draws on this page, per axis — `rows to cols`. Read off
     * the resolved positions rather than from a formula, so the options screen's read-out ("8.0 mm
     * → 27 lines") can never disagree with the paper.
     */
    fun countsFor(spec: TemplateSpec, widthPx: Int, heightPx: Int, dpi: Float): Pair<Int, Int> {
        val p = plan(spec, widthPx, heightPx, dpi)
        return p.ys.size to p.xs.size
    }

    /**
     * The spacing in millimetres that [count] features per axis works out to on this page — what
     * the screen shows beside a count, and what the mode toggle carries across so switching from
     * count to spacing does not move the pattern.
     */
    fun spacingMmFor(count: Int, extentPx: Float, leading: Int, dpi: Float): Float {
        if (count < 1 || extentPx <= 0f || dpi <= 0f) return SPACING_MM
        return spacingPxFor(count, extentPx, leading) * 25.4f / dpi
    }

    /**
     * The spacing that draws exactly [count] features across [extentPx].
     *
     * The exact answer — `extent / (count + leading)` — puts the *next* feature precisely on the
     * far edge, which the `<` in `steps` is meant to exclude and float accumulation then includes
     * about half the time: 12 rows asked for, 13 drawn. So the spacing is nudged up by
     * [COUNT_EPS], a hundred-thousandth, which carries that phantom feature safely past the edge
     * and is far below anything a panel could render. The nudge lives **here and only here** —
     * stock spacing comes from millimetres and never touches this path, so its output is unchanged.
     */
    fun spacingPxFor(count: Int, extentPx: Float, leading: Int): Float =
        (extentPx / (count + leading)) * (1f + COUNT_EPS)

    /** The content extent of one axis in pixels — what [spacingMmFor] measures a count against. */
    fun contentExtentPx(spec: TemplateSpec, widthPx: Int, heightPx: Int, dpi: Float, vertical: Boolean): Float {
        val r = contentRect(spec.sanitized(), widthPx, heightPx, dpi)
        return if (vertical) r.bottom - r.top else r.right - r.left
    }

    /**
     * The page minus its insets. Insets that would leave no paper are dropped **as a pair** — a
     * spec asking for 60 mm of margin on a 50 mm-wide card is not a page with a negative content
     * rect, it is a page with no margins ([MIN_CONTENT_PX] is what "no paper" means).
     */
    fun contentRect(spec: TemplateSpec, widthPx: Int, heightPx: Int, dpi: Float): ContentRect {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        var top = mmToPx(spec.topMm, dpi)
        var bottom = h - mmToPx(spec.bottomMm, dpi)
        if (bottom - top < MIN_CONTENT_PX) { top = 0f; bottom = h }
        var left = mmToPx(spec.leftMm, dpi)
        var right = w - mmToPx(spec.rightMm, dpi)
        if (right - left < MIN_CONTENT_PX) { left = 0f; right = w }
        return ContentRect(left, top, right, bottom)
    }

    /** How many spacings sit above the first feature: 2 for Lined rows, 1 everywhere else. */
    fun leadingFor(kind: TemplateKind, vertical: Boolean): Int =
        if (vertical && kind == TemplateKind.LINED) 2 else 1

    /**
     * The library cover's *hint* at a template — the pattern squeezed to a fixed row count so a
     * 3 cm card shows something. Deliberately **not** a miniature: it is a placeholder for a
     * notebook with no cover snapshot yet, and it predates specs.
     */
    fun placeholderPlan(kind: TemplateKind, widthPx: Int, heightPx: Int, density: Float): TemplatePlan {
        val feature = maxOf(1f, density)
        val spacing = (heightPx / PLACEHOLDER_ROWS).toFloat().coerceAtLeast(2f)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        return TemplatePlan(
            kind = kind,
            left = 0f, top = 0f, right = w, bottom = h,
            xs = if (kind == TemplateKind.LINED) emptyList() else steps(spacing, w, spacing),
            ys = steps(if (kind == TemplateKind.LINED) spacing * 2f else spacing, h, spacing),
            lineWidthPx = feature,
            dotRadiusPx = feature,
            marginRuleX = null,
            grey = 0,
        )
    }

    /** How many pattern rows a card-sized placeholder shows, whatever the card's real size is. */
    private const val PLACEHOLDER_ROWS = 12

    /** Below this a page has no room left to be paper and the insets are dropped. */
    const val MIN_CONTENT_PX = 8f

    /** No pattern may be finer than this. Guards a foreign spec, and a very small miniature. */
    const val MIN_SPACING_PX = 2f

    /** A hard stop on how many features one axis may produce — a bound, not a design limit. */
    const val MAX_STEPS = 4096

    /** The nudge that keeps a count exact against float accumulation — see [spacingPxFor]. */
    const val COUNT_EPS = 1e-5f

    private fun resolve(axis: DensityAxis, extentPx: Float, leading: Int, dpi: Float): Float {
        val raw = if (axis.mode == DensityMode.COUNT) spacingPxFor(axis.count, extentPx, leading)
                  else mmToPx(axis.spacingMm, dpi)
        return maxOf(MIN_SPACING_PX, raw)
    }

    private fun steps(from: Float, limit: Float, step: Float): List<Float> {
        if (step <= 0f || !step.isFinite() || !from.isFinite()) return emptyList()
        val out = mutableListOf<Float>()
        var v = from
        while (v < limit && out.size < MAX_STEPS) {
            out.add(v)
            v += step
        }
        return out
    }
}
