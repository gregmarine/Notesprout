package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import kotlin.math.max

/**
 * Where the verses of a passage land on a page (arc 40 "Verses") — pure, JVM-tested.
 *
 * **The column** (the user's decision 5): the left edge sits [LEFT_FRACTION] of the way across
 * the page and the text wraps at the page's right edge, because a text object's width is
 * re-derived as `pageWidth − x` on every load (position is authored, size is derived — a centred
 * 80 % column could not survive a reload). So the only free coordinate is `y`, and this object
 * chooses it: the first clear spot scanning outward from a preferred `y` in [STEP_DP] steps,
 * with [GAP_DP] of air ([FreePlacement]'s numbers). **No clear spot is no spot** — unlike
 * [FreePlacement], which falls back to the centre for a small object, a block of verses dropped
 * on top of what is there is unreadable twice over (the Nomad walk stacked one on another), so a
 * page with no room, like a box taller than the page, is the caller's "no room" refusal — the
 * page-fit rule the user asked for.
 */
object VersePlacement {

    /** The column's left edge as a fraction of the page width. */
    const val LEFT_FRACTION = 0.10f

    const val GAP_DP = 8f
    const val STEP_DP = 16f

    fun leftEdge(pageW: Float): Float = if (pageW.isFinite() && pageW > 0f) pageW * LEFT_FRACTION else 0f

    /** Whether a box [h] tall can be on a [pageH] page at all. */
    fun fits(h: Float, pageH: Float): Boolean = h.isFinite() && pageH > 0f && h <= pageH

    /**
     * The `y` for a [w] × [h] box at [x], as near [preferredY] as the page allows: [preferredY]
     * clamped when it is clear of [occupied], else the nearest clear `y` above or below it. Null
     * when the box does not [fits] the page, or when no `y` on the page is clear.
     */
    fun nearY(
        x: Float,
        preferredY: Float,
        w: Float,
        h: Float,
        pageH: Float,
        occupied: List<Bounds>,
        density: Float,
    ): Float? {
        if (!fits(h, pageH) || !w.isFinite()) return null
        val maxY = pageH - h
        val start = preferredY.coerceIn(0f, maxY)
        if (occupied.isEmpty()) return start
        val gap = GAP_DP * max(density, 0f)
        val step = (STEP_DP * max(density, 1f)).coerceAtLeast(1f)
        if (clear(x, start, w, h, gap, occupied)) return start
        var r = 1
        while (true) {
            val down = start + r * step
            val up = start - r * step
            val downIn = down <= maxY
            val upIn = up >= 0f
            if (!downIn && !upIn) return null
            if (downIn && clear(x, down, w, h, gap, occupied)) return down
            if (upIn && clear(x, up, w, h, gap, occupied)) return up
            r++
        }
    }

    /**
     * The spot directly below [anchor] — the placed reference the verses expand — with a gap,
     * when it is clear and fits; else [nearY] from there. Null when the box does not fit the page.
     * [occupied] should not include [anchor] itself.
     */
    fun below(
        anchor: Bounds,
        x: Float,
        w: Float,
        h: Float,
        pageH: Float,
        occupied: List<Bounds>,
        density: Float,
    ): Float? {
        if (!fits(h, pageH)) return null
        val gap = GAP_DP * max(density, 0f)
        val y = anchor.bottom + gap * 2f
        if (y + h <= pageH && clear(x, y, w, h, gap, occupied)) return y
        return nearY(x, y, w, h, pageH, occupied, density)
    }

    /** [occupied] without the boxes that lie inside [within] — the ink a conversion is about to
     *  erase must not block the spot it stood on. */
    fun without(occupied: List<Bounds>, within: Bounds): List<Bounds> = occupied.filterNot {
        it.left >= within.left && it.top >= within.top && it.right <= within.right && it.bottom <= within.bottom
    }

    private fun clear(x: Float, y: Float, w: Float, h: Float, gap: Float, occupied: List<Bounds>): Boolean {
        val box = Bounds(x - gap, y - gap, x + w + gap, y + h + gap)
        return occupied.none { it.intersects(box) }
    }
}
