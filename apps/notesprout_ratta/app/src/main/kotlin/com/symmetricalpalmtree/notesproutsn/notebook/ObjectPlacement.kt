package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds

/**
 * Where a pasted set of objects lands (arc 8). Pure arithmetic over a bounding box, a target and a
 * page — no rows, no Android — so the placement half of paste is provable off-device, exactly like
 * [PageMath] for the page list and [ObjectClip] for the rows.
 *
 * Two ways in, one rule out:
 *  - [centredOn] — the pen tap on bare paper. What the user pointed at becomes the middle of what
 *    they pasted; a tap is an aim, not a corner.
 *  - [atSource] — the popup's Paste, which has no tap to aim at. The set keeps the coordinates it
 *    was copied from, so pasting into the same (or a same-size) page reproduces the original layout
 *    exactly.
 *
 * Both then **clamp**: the box is shifted until it sits inside the page, silently — a placement the
 * user is about to see land needs no toast. Content **larger than the page** on an axis cannot be
 * made to fit, so it pastes from that edge (offset 0 on that axis) rather than being centred into
 * equal overflow on both sides: the top-left of a too-big paste is the part worth keeping on screen.
 *
 * A non-positive page dimension means "unknown" (a page row with no size) and simply does not clamp
 * on that axis — inventing a page edge would move ink for no reason.
 */
object ObjectPlacement {

    /** The shift to apply to every pasted object, in page px. */
    data class Offset(val dx: Float, val dy: Float) {
        companion object {
            val NONE = Offset(0f, 0f)
        }
    }

    /** [box] centred on ([tapX], [tapY]), then clamped onto the page. */
    fun centredOn(box: Bounds, tapX: Float, tapY: Float, pageWidth: Float, pageHeight: Float): Offset =
        clampedTo(box, tapX - box.width / 2f, tapY - box.height / 2f, pageWidth, pageHeight)

    /** [box] where it already is, clamped onto the page — the popup Paste's placement. */
    fun atSource(box: Bounds, pageWidth: Float, pageHeight: Float): Offset =
        clampedTo(box, box.left, box.top, pageWidth, pageHeight)

    /** The shift that puts [box]'s top-left at ([targetLeft], [targetTop]) and then pulls the whole
     *  box back inside the page. */
    private fun clampedTo(
        box: Bounds,
        targetLeft: Float,
        targetTop: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): Offset = Offset(
        dx = axis(box.left, box.width, targetLeft, pageWidth),
        dy = axis(box.top, box.height, targetTop, pageHeight),
    )

    /**
     * One axis: the shift from [from] to [target], corrected so `[from] + shift` and
     * `from + size + shift` both lie inside `0..[page]`.
     *
     * Order is load-bearing — pull back from the far edge first, then off the near edge — so a box
     * that fits ends flush against the near edge rather than the far one when both corrections fire.
     */
    private fun axis(from: Float, size: Float, target: Float, page: Float): Float {
        if (!from.isFinite() || !size.isFinite() || !target.isFinite()) return 0f
        var shift = target - from
        if (page <= 0f) return shift
        if (size > page) return -from            // cannot fit: paste from the page's edge
        val overshoot = (from + size + shift) - page
        if (overshoot > 0f) shift -= overshoot
        val undershoot = from + shift
        if (undershoot < 0f) shift -= undershoot
        return shift
    }
}
