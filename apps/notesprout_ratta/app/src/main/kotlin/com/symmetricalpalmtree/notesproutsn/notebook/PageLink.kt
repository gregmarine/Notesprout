package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke

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

    companion object {
        /** The band below the wrapped content the underline draws in — added to [unionBounds]
         *  at wrap time (in px: `UNDERLINE_CLEARANCE_DP * density`). */
        const val UNDERLINE_CLEARANCE_DP = 2f

        /**
         * Union of the wrapped content's bounds with [bottomClearancePx] added below (the
         * underline band). Null when there is nothing to wrap. Pure — JVM-tested.
         */
        fun unionBounds(strokes: List<Stroke>, headings: List<Heading>, bottomClearancePx: Float): Bounds? {
            var union: Bounds? = null
            for (s in strokes) union = union?.union(s.bounds) ?: s.bounds
            for (h in headings) union = union?.union(h.bounds) ?: h.bounds
            val b = union ?: return null
            return Bounds(b.left, b.top, b.right, b.bottom + bottomClearancePx)
        }
    }
}
