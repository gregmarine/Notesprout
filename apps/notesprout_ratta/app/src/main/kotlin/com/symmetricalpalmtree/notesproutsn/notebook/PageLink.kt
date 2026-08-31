package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.markdown.HeadingTypography
import kotlin.math.max

/**
 * A link on a page (arc 6) — the in-memory form of a `link` row. A link wraps a lasso selection
 * into one tappable navigation object: the wrapped children ([strokes] + [headings]) keep their
 * ids and their **page-absolute** coordinates — a wrap only flips their `parentId` page → link
 * (the re-parent model, Paper L1: no id churn, no embedded copies), and an unlink flips it back.
 *
 * Unlike Paper, SN's core owns link *meaning* too: [payload] is the v1 grammar the core itself
 * wrote ([LinkPayload]), and [chrome] is its decoded chrome — parsed once at load / create, never
 * persisted anywhere but inside the payload (`flags` stays null, the locked no-cache decision).
 * A payload the codec cannot read (foreign, future) leaves [chrome] at [LinkPayload.CHROME_NONE];
 * the content still renders and a follow explains itself (the dead-target rule, K4).
 *
 * The bounds are the union of the wrapped content plus the underline clearance at the bottom
 * ([unionBounds]), so the chrome the renderer draws never overlaps the writing.
 *
 * Pure Kotlin (g-paper's [Bounds] / [Stroke] are pure too) — JVM-tested through [LinkRows].
 */
data class PageLink(
    val id: String,
    /** The stored v1 payload — capped at [LinkPayload.MAX_PAYLOAD_CHARS] in both directions. */
    val payload: String,
    /** `LinkPayload.chromeOf(payload)` — held here so render never re-parses. */
    val chrome: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Z-order among the page's links (`"order"` column). */
    val order: Int,
    /** Wrapped stroke children, page-absolute, in writing order. */
    val strokes: List<Stroke>,
    /** Wrapped heading children, page-absolute, in z-order. */
    val headings: List<Heading>,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    /** Ids of everything this link wraps — the re-parent / soft-delete / restore set. */
    val childIds: List<String> get() = strokes.map { it.id } + headings.map { it.id }

    /** Shifts the link **and** every wrapped child: the children stay page-absolute. */
    fun translated(dx: Float, dy: Float): PageLink = copy(
        x = x + dx, y = y + dy,
        strokes = strokes.map { it.translated(dx, dy) },
        headings = headings.map { it.translated(dx, dy) },
    )

    /**
     * Grow the bottom band to what [Companion.unionBounds] now asks for, when the stored bounds
     * reserve less — a link written under an earlier, tighter band would otherwise keep drawing
     * its underline against the ink for the rest of the file's life. Idempotent (it is the
     * wrap-time formula, re-applied) and it **only ever grows**: a foreign file may wrap children
     * this build cannot decode, and shrinking to the union of what we *can* read would cut the
     * link down. Applied at page load, next to the heading remeasure.
     */
    fun withUnderlineBand(density: Float): PageLink {
        val b = unionBounds(strokes, headings, density) ?: return this
        val needed = b.bottom - y
        return if (needed > height) copy(height = needed) else this
    }

    companion object {
        /** The clear space kept between the wrapped content's box and the underline, in dp. */
        const val UNDERLINE_CLEARANCE_DP = 4f

        /**
         * Where the underline sits: the lowest wrapped **box** bottom plus [UNDERLINE_CLEARANCE_DP].
         * Null when there is nothing to wrap.
         *
         * A heading's box *is* its bounds — [HeadingTypography.PADDING_DP] of breathing room is
         * already built in around its line, and that gap is the one the user calls right. Loose ink
         * has no box, so this gives it the same one: a g-paper `Stroke.bounds` is the tight bounds
         * of its *points* (no stroke width — the trap [LinkComposite.padOf] pads for), so a
         * stroke's box is its ink extent (`bounds.bottom + width / 2`) plus that same padding.
         * Ink and headings then arrive at the line looking alike.
         */
        fun bandBottom(strokes: List<Stroke>, headings: List<Heading>, density: Float): Float? {
            val pad = HeadingTypography.paddingPx(density)
            var box: Float? = null
            for (s in strokes) box = max(box ?: Float.NEGATIVE_INFINITY, s.bounds.bottom + s.width / 2f + pad)
            for (h in headings) box = max(box ?: Float.NEGATIVE_INFINITY, h.bounds.bottom)
            return box?.plus(UNDERLINE_CLEARANCE_DP * density)
        }

        /**
         * Union of the wrapped content's bounds, with the bottom carried down to [bandBottom] (the
         * underline band). Null when there is nothing to wrap. Pure — JVM-tested.
         */
        fun unionBounds(strokes: List<Stroke>, headings: List<Heading>, density: Float): Bounds? {
            var union: Bounds? = null
            for (s in strokes) union = union?.union(s.bounds) ?: s.bounds
            for (h in headings) union = union?.union(h.bounds) ?: h.bounds
            val b = union ?: return null
            return Bounds(b.left, b.top, b.right, bandBottom(strokes, headings, density) ?: b.bottom)
        }
    }
}
