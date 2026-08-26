package com.symmetricalpalmtree.notesproutsn.data.template

/** The four built-in page backgrounds. [BLANK] writes no template row at all. */
enum class TemplateKind { BLANK, LINED, DOTTED, GRID }

/**
 * Where the rules, dots and grid lines go — **pure arithmetic, no `android.graphics`**, so the
 * geometry that ends up baked into every notebook is JVM-testable.
 *
 * Everything is derived from one physical constant: **8 mm** between features, converted to pixels
 * at the panel's real dpi. Paper is measured in millimetres, not pixels, so a template must look
 * the same size on any device.
 *
 * Feature *sizes* are authored at mdpi and scaled by dpi: a literal 1 px rule on a 300 ppi e-ink
 * panel is 0.08 mm and renders as faint grey, not a line. [lineWidthPx] / [dotRadiusPx] are what
 * keep a rule black on a Supernote.
 *
 * Two origins, deliberately different:
 *  - [linePositions] starts at **2 × spacing** — a writing sheet wants a top margin above the
 *    first rule.
 *  - [gridPositionsX] / [gridPositionsY] / [dotPositions] start at **1 × spacing** and are
 *    symmetric in both axes. A grid must not borrow the lined top margin or its top row of cells
 *    comes out double height.
 */
object TemplateGeometry {

    const val SPACING_MM = 8f
    private const val LINE_WIDTH_MDPI = 1f
    private const val DOT_RADIUS_MDPI = 2f

    /** 8 mm in pixels at [dpi]. */
    fun spacingPx(dpi: Float): Float = SPACING_MM * dpi / 25.4f

    /** Rule thickness: 1 px at mdpi, never thinner than 1 px (≈ 2 px on a 300 ppi panel). */
    fun lineWidthPx(dpi: Float): Float = maxOf(1f, LINE_WIDTH_MDPI * dpi / 160f)

    /** Dot radius: 2 px at mdpi, never smaller than 1 px (≈ 3.75 px, ~0.6 mm dot on a 300 ppi
     *  panel) — Paper's on-device finding: a 1.5 px-authored dot read as faint grey on e-ink. */
    fun dotRadiusPx(dpi: Float): Float = maxOf(1f, DOT_RADIUS_MDPI * dpi / 160f)

    /** Lined: horizontal rules, first one a top margin (one spacing) below the first grid step. */
    fun linePositions(heightPx: Int, spacingPx: Float): List<Float> =
        steps(from = spacingPx * 2f, limit = heightPx, step = spacingPx)

    /** Grid verticals — first at one spacing, uniform cells across. */
    fun gridPositionsX(widthPx: Int, spacingPx: Float): List<Float> =
        steps(from = spacingPx, limit = widthPx, step = spacingPx)

    /** Grid horizontals — symmetric with [gridPositionsX]; never reuse [linePositions] here. */
    fun gridPositionsY(heightPx: Int, spacingPx: Float): List<Float> =
        steps(from = spacingPx, limit = heightPx, step = spacingPx)

    /** Dotted: the intersections of the same grid, row-major. */
    fun dotPositions(widthPx: Int, heightPx: Int, spacingPx: Float): List<Pair<Float, Float>> {
        val xs = gridPositionsX(widthPx, spacingPx)
        val out = ArrayList<Pair<Float, Float>>(xs.size * 8)
        for (y in gridPositionsY(heightPx, spacingPx)) for (x in xs) out.add(x to y)
        return out
    }

    private fun steps(from: Float, limit: Int, step: Float): List<Float> {
        if (step <= 0f) return emptyList()
        val out = mutableListOf<Float>()
        var v = from
        while (v < limit) {
            out.add(v)
            v += step
        }
        return out
    }
}
