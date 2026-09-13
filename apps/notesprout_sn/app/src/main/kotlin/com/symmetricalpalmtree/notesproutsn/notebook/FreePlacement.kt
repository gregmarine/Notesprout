package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import kotlin.math.ceil
import kotlin.math.max

/**
 * Where a dropped object lands when the page centre is taken (the user's decision 2026-09-13,
 * after B9's Send stacked "John 3" on the "Proverbs 3" that was already there): **the nearest
 * free spot to the centre**, searched outward in rings. Pure arithmetic beside [ObjectPlacement]
 * and [TextPlacement] — the occupied boxes come in, a top-left goes out — so the rule is provable
 * off-device.
 *
 * Every door that drops at the centre goes through here: the Insert bar's Text, the six shapes,
 * Sticky and Bible, and the reader's Send to notebook. None of them decides a position on its own
 * any more; the three per-kind default builders still size the object, and this places it.
 *
 * The rule:
 *  - The centre ([TextPlacement.centred]) is tried first and **kept when it is clear** — an empty
 *    page behaves exactly as before.
 *  - "Clear" means the box, grown by [GAP_DP] on every side, meets none of [occupied] — so a
 *    landed object never touches its neighbour either. What counts as occupied is the caller's
 *    (`NotebookActivity.occupiedBounds`: texts, shapes, stickies, headings, links **and the live
 *    ink** — a reference dropped over handwriting is the same stacking).
 *  - Otherwise candidates on a [STEP_DP] grid around the centre are tried ring by ring, each ring
 *    ordered by distance from the centre, and the first clear one wins — "as close to the middle
 *    as it can get". Every candidate is clamped onto the page first, so the rings never propose a
 *    spot that hangs off an edge; a ring reaching past every edge ends the search.
 *  - A box the page cannot hold on an axis, or a page with no clear spot anywhere, lands at the
 *    centre as it always did — a drop is never refused, and a full page is a page the user will
 *    tidy by hand.
 */
object FreePlacement {

    /** The clearance kept between a landed box and anything already there. */
    const val GAP_DP = 8f

    /** The grid the rings are tried on — fine enough to tuck a text beside a sticky icon. */
    const val STEP_DP = 16f

    /**
     * The top-left of a [w] × [h] box on a [pageW] × [pageH] page, at the centre when that is
     * clear of [occupied], else at the nearest clear spot; [density] scales the gap and the step.
     */
    fun nearCentre(
        pageW: Float,
        pageH: Float,
        w: Float,
        h: Float,
        occupied: List<Bounds>,
        density: Float,
    ): Pair<Float, Float> {
        val centre = TextPlacement.centred(pageW, pageH, w, h)
        if (occupied.isEmpty()) return centre
        if (!(pageW > 0f && pageH > 0f) || w > pageW || h > pageH || !w.isFinite() || !h.isFinite()) return centre
        val gap = GAP_DP * max(density, 0f)
        val step = (STEP_DP * max(density, 1f)).coerceAtLeast(1f)
        if (clear(centre.first, centre.second, w, h, gap, occupied)) return centre

        val (cx, cy) = centre
        val maxX = pageW - w
        val maxY = pageH - h
        // Rings until one reaches past every edge from the centre — nothing lies beyond that.
        val rings = ceil(max(max(cx, maxX - cx), max(cy, maxY - cy)) / step).toInt() + 1
        val tried = HashSet<Long>()
        for (r in 1..rings) {
            var best: Pair<Float, Float>? = null
            var bestD2 = Float.MAX_VALUE
            for (dy in -r..r) {
                for (dx in -r..r) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    val x = (cx + dx * step).coerceIn(0f, maxX)
                    val y = (cy + dy * step).coerceIn(0f, maxY)
                    // Clamping folds many ring cells onto one edge spot — test each spot once.
                    val key = (x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xFFFFFFFFL)
                    if (!tried.add(key)) continue
                    val ddx = x - cx
                    val ddy = y - cy
                    val d2 = ddx * ddx + ddy * ddy
                    if (d2 >= bestD2) continue
                    if (clear(x, y, w, h, gap, occupied)) { best = x to y; bestD2 = d2 }
                }
            }
            if (best != null) return best
        }
        return centre
    }

    private fun clear(x: Float, y: Float, w: Float, h: Float, gap: Float, occupied: List<Bounds>): Boolean {
        val box = Bounds(x - gap, y - gap, x + w + gap, y + h + gap)
        return occupied.none { it.intersects(box) }
    }
}
