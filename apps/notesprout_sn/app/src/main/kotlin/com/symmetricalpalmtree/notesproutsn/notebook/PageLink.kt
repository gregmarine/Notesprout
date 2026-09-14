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
    /** Arc 28 (H1): wrapped texts, shapes and stickies, page-absolute, each in z-order. A wrapped
     *  sticky's own content strokes are its grandchildren — local to the note, read through
     *  `StickyStore.content`, and **not** in [childIds] (the sticky store carries them). */
    val texts: List<PageText> = emptyList(),
    val shapes: List<PageShape> = emptyList(),
    val stickies: List<PageSticky> = emptyList(),
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    /** Ids of everything this link wraps — the re-parent / soft-delete / restore set. A wrapped
     *  sticky's content is not here: it hangs under the sticky, whose own store takes it down and
     *  brings it back. */
    val childIds: List<String> get() =
        strokes.map { it.id } + headings.map { it.id } + texts.map { it.id } +
            shapes.map { it.id } + stickies.map { it.id }

    /** Shifts the link **and** every wrapped child: the children stay page-absolute (a sticky's
     *  icon moves; its local content does not, by construction). */
    fun translated(dx: Float, dy: Float): PageLink = copy(
        x = x + dx, y = y + dy,
        strokes = strokes.map { it.translated(dx, dy) },
        headings = headings.map { it.translated(dx, dy) },
        texts = texts.map { it.translated(dx, dy) },
        shapes = shapes.map { it.translated(dx, dy) },
        stickies = stickies.map { it.translated(dx, dy) },
    )

    /**
     * Grow the bottom band to what [Companion.unionBounds] now asks for, when the stored bounds
     * reserve less — a link written under an earlier, tighter band would otherwise keep drawing
     * its underline against the ink for the rest of the file's life. Idempotent (it is the
     * wrap-time formula, re-applied) and it **only ever grows**: a foreign file may wrap children
     * this build cannot decode, and shrinking to the union of what we *can* read would cut the
     * link down. Applied at page load, next to the heading remeasure.
     */
    /**
     * The wrapped texts re-measured by [measure] (og's rule: a text's width is `pageWidth − x`,
     * derived on every load, never stored as truth) and the box grown to hold them. A loose text
     * gets this from `PageObjects.remeasured`; a wrapped one lives under the link and would
     * otherwise keep the width it was written at — a verses block (arc 40) fills its column, so
     * a drag to the right left its lines clipped at the page edge. Idempotent; the row is
     * corrected whenever the link is next written.
     */
    fun withTextsRemeasured(measure: (PageText) -> Pair<Float, Float>, density: Float): PageLink {
        if (texts.isEmpty()) return this
        var changed = false
        val sized = texts.map { t ->
            val (w, h) = measure(t)
            if (w == t.width && h == t.height) t else { changed = true; t.copy(width = w, height = h) }
        }
        if (!changed) return this
        val b = unionBounds(strokes, headings, density, sized, shapes, stickies) ?: return copy(texts = sized)
        // The union holds everything wrapped, so the right edge follows it both ways (a text
        // narrows when it moves right); the band below only ever grows, as withUnderlineBand's.
        return copy(texts = sized, width = b.right - x, height = max(height, b.bottom - y))
    }

    fun withUnderlineBand(density: Float): PageLink {
        val b = unionBounds(strokes, headings, density, texts, shapes, stickies) ?: return this
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
         *
         * Arc 28 (H1): a **text** object and a **sticky icon** have a box of their own, like a
         * heading, and count as-is; a **shape** is measured by its rotated point-tight outline
         * ([ShapeGeometry.tightBounds]) grown by half its own outline width, the stroke rule in
         * geometry form. The three new lists are trailing defaults so the arc-6 call sites keep
         * compiling unchanged.
         */
        fun bandBottom(
            strokes: List<Stroke>,
            headings: List<Heading>,
            density: Float,
            texts: List<PageText> = emptyList(),
            shapes: List<PageShape> = emptyList(),
            stickies: List<PageSticky> = emptyList(),
        ): Float? {
            val pad = HeadingTypography.paddingPx(density)
            var box: Float? = null
            for (s in strokes) box = max(box ?: Float.NEGATIVE_INFINITY, s.bounds.bottom + s.width / 2f + pad)
            for (h in headings) box = max(box ?: Float.NEGATIVE_INFINITY, h.bounds.bottom)
            for (t in texts) box = max(box ?: Float.NEGATIVE_INFINITY, t.bounds.bottom)
            for (s in shapes) box = max(box ?: Float.NEGATIVE_INFINITY, shapeBox(s).bottom)
            for (s in stickies) box = max(box ?: Float.NEGATIVE_INFINITY, s.bounds.bottom)
            return box?.plus(UNDERLINE_CLEARANCE_DP * density)
        }

        /**
         * Union of the wrapped content's bounds, with the bottom carried down to [bandBottom] (the
         * underline band). Null when there is nothing to wrap. Pure — JVM-tested.
         */
        fun unionBounds(
            strokes: List<Stroke>,
            headings: List<Heading>,
            density: Float,
            texts: List<PageText> = emptyList(),
            shapes: List<PageShape> = emptyList(),
            stickies: List<PageSticky> = emptyList(),
        ): Bounds? {
            var union: Bounds? = null
            for (s in strokes) union = union?.union(s.bounds) ?: s.bounds
            for (h in headings) union = union?.union(h.bounds) ?: h.bounds
            for (t in texts) union = union?.union(t.bounds) ?: t.bounds
            for (s in shapes) { val r = shapeBox(s); union = union?.union(r) ?: r }
            for (s in stickies) union = union?.union(s.bounds) ?: s.bounds
            val b = union ?: return null
            val bottom = bandBottom(strokes, headings, density, texts, shapes, stickies) ?: b.bottom
            return Bounds(b.left, b.top, b.right, bottom)
        }

        /** A shape's drawn extent: the rotated outline's tight box grown by half the outline width
         *  (the `Stroke.bounds` trap, in geometry). Density-free — a shape's width is stored in px. */
        private fun shapeBox(s: PageShape): Bounds =
            ShapeGeometry.tightBounds(s).inflated(s.strokeWidth / 2f)
    }
}
