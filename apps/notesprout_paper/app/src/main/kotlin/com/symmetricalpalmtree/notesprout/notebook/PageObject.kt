package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds

/**
 * A content object on a page (arc 4) — the in-memory form of an `object` row. The core knows its
 * identity, its provider ([providerIdentity] = `<extension package>:<typeId>`, the same shape as a
 * template identity), its bounds in page px and its z-order among the page's objects; the
 * [payload] is the owning provider's **opaque** text (for a heading: the markdown source). The core
 * never parses it, never logs it, and stores it capped at `MAX_OBJECT_TEXT_CHARS`.
 *
 * Pure Kotlin (g-paper's [Bounds] is pure too) — JVM-tested through [ObjectRows].
 */
data class PageObject(
    val id: String,
    val providerIdentity: String,
    val payload: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val order: Int,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    fun translated(dx: Float, dy: Float): PageObject = copy(x = x + dx, y = y + dy)
}
