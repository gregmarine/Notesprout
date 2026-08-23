package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.library.GridMath

/**
 * Sizing for the link picker's page previews (arc 6 / K2). The locked decision: a preview keeps
 * the **real page's aspect ratio, scaled to the grid-cell width** — the card itself keeps the
 * library-card footprint ([GridMath.CARD_ASPECT], so `GridMath` is reused unchanged) and the
 * preview is fit-centred inside the card's image band, undistorted.
 *
 * Page dimensions come out of a `.soil` file, which is untrusted input: a degenerate size falls
 * back to the library-card aspect and an extreme one is clamped, so a foreign file can neither
 * divide by zero nor demand an absurd bitmap. Pure Kotlin — JVM-tested.
 */
object PreviewMath {

    /** Clamp band for a page's height/width ratio — anything real is well inside. */
    const val MIN_ASPECT = 0.5f
    const val MAX_ASPECT = 3f

    /** Preview bitmaps never exceed this edge — cards are small; the cap only bounds bad input. */
    const val MAX_RENDER_EDGE_PX = 1024

    /** `height / width` of the page, clamped; the library-card aspect when the size is unusable. */
    fun aspect(pageWidth: Int, pageHeight: Int): Float =
        if (pageWidth <= 0 || pageHeight <= 0) GridMath.CARD_ASPECT
        else (pageHeight.toFloat() / pageWidth).coerceIn(MIN_ASPECT, MAX_ASPECT)

    /** `(width, height)` of the preview bitmap for a card [cellWidthPx] wide. */
    fun renderSize(cellWidthPx: Int, pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val w = cellWidthPx.coerceIn(1, MAX_RENDER_EDGE_PX)
        val h = (w * aspect(pageWidth, pageHeight)).toInt().coerceIn(1, MAX_RENDER_EDGE_PX)
        return w to h
    }
}
