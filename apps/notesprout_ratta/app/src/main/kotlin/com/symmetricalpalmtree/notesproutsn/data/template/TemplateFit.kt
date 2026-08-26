package com.symmetricalpalmtree.notesproutsn.data.template

/**
 * How an imported picture becomes a page (arc 13). **Pure arithmetic, no `android.graphics`** — the
 * library stores the *original* image and the page-sized render happens on use, so this is the
 * function that decides what a Nomad page and a Manta page each get out of one row.
 *
 * Three modes and no fourth, stored in the index row's `flags`:
 *
 *  - [FIT] — the whole picture, centred on white, aspect kept. Nothing is lost and nothing is
 *    distorted; the page shows margins where the aspects disagree. The default, and the only one a
 *    scan of a real sheet of paper ever wants.
 *  - [STRETCH] — the picture pulled to the page's corners. Aspect is not kept.
 *  - [FILL] — aspect kept, scaled until the page is covered, the overhang cropped evenly off both
 *    sides of the long axis.
 *
 * The result is one source rect and one destination rect, both in whole-pixel floats: Fit moves the
 * destination, Fill moves the source, Stretch moves neither. A blit of `src → dst` onto a
 * white page is the whole render.
 */
object TemplateFit {

    const val FIT = 0
    const val STRETCH = 1
    const val FILL = 2

    /** Every mode this build knows, in the order the import sheet offers them. */
    val MODES: List<Int> = listOf(FIT, STRETCH, FILL)

    /** A mode read out of a row's `flags`, clamped to one this build can draw. */
    fun sanitize(fit: Int?): Int = if (fit in MODES) fit!! else FIT

    /** `(left, top, right, bottom)` — the two rects [rects] returns, in pixels. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** What to blit where. Null when either size is degenerate — there is nothing to draw. */
    data class Plan(val src: Rect, val dst: Rect)

    fun plan(fit: Int, srcWidth: Int, srcHeight: Int, pageWidth: Int, pageHeight: Int): Plan? {
        if (srcWidth <= 0 || srcHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) return null
        val sw = srcWidth.toFloat()
        val sh = srcHeight.toFloat()
        val pw = pageWidth.toFloat()
        val ph = pageHeight.toFloat()
        val whole = Rect(0f, 0f, sw, sh)
        val page = Rect(0f, 0f, pw, ph)

        return when (sanitize(fit)) {
            STRETCH -> Plan(whole, page)

            FILL -> {
                // Scale to cover, then crop the overhang off the source symmetrically. Cropping the
                // source rather than over-drawing the destination keeps the blit inside the page,
                // which is what makes the render a plain bitmap the same size as every other one.
                val scale = maxOf(pw / sw, ph / sh)
                val keepW = minOf(sw, pw / scale)
                val keepH = minOf(sh, ph / scale)
                val left = (sw - keepW) / 2f
                val top = (sh - keepH) / 2f
                Plan(Rect(left, top, left + keepW, top + keepH), page)
            }

            else -> {
                val scale = minOf(pw / sw, ph / sh)
                val w = sw * scale
                val h = sh * scale
                val left = (pw - w) / 2f
                val top = (ph - h) / 2f
                Plan(whole, Rect(left, top, left + w, top + h))
            }
        }
    }
}
