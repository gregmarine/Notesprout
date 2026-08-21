package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * A link on a page (arc 7) — the in-memory form of a `link` row. The core owns link *structure*: the
 * identity, the bounds in page px, the z-order among the page's links, and the wrapped children
 * ([strokes] + [objects], which keep their ids and their **page-absolute** coordinates — a wrap only
 * flips their `parentId`). Link *meaning* belongs to the provider: [payload] and [providerIdentity]
 * are opaque here — never parsed, never logged, stored capped at `MAX_LINK_PAYLOAD_CHARS`.
 *
 * The bounds are the union of the wrapped content plus the underline clearance at the bottom, so the
 * chrome the core draws never overlaps the writing ([unionBounds]).
 *
 * Pure Kotlin (g-paper's [Bounds] and [Stroke] are pure too) — JVM-tested through [LinkRows].
 */
data class PageLink(
    val id: String,
    val providerIdentity: String,
    val payload: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val order: Int,
    /** Wrapped stroke children, page-absolute, in writing order. */
    val strokes: List<Stroke>,
    /** Wrapped object children, page-absolute, in z-order. */
    val objects: List<PageObject>,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    /** Ids of everything this link wraps — the re-parent / soft-delete / restore set. */
    val childIds: List<String> get() = strokes.map { it.id } + objects.map { it.id }

    /** Shifts the link **and** every wrapped child: the children stay page-absolute. */
    fun translated(dx: Float, dy: Float): PageLink = copy(
        x = x + dx, y = y + dy,
        strokes = strokes.map { it.translated(dx, dy) },
        objects = objects.map { it.translated(dx, dy) },
    )

    companion object {
        /**
         * Union of the wrapped content's bounds with [bottomClearancePx] added below (the underline
         * band). Null when there is nothing to wrap. Pure — JVM-tested.
         */
        fun unionBounds(strokes: List<Stroke>, objects: List<PageObject>, bottomClearancePx: Float): Bounds? {
            var union: Bounds? = null
            for (s in strokes) union = union?.union(s.bounds) ?: s.bounds
            for (o in objects) union = union?.union(o.bounds) ?: o.bounds
            val b = union ?: return null
            return Bounds(b.left, b.top, b.right, b.bottom + bottomClearancePx)
        }
    }
}
