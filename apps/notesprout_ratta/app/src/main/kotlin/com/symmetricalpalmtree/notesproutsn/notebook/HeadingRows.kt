package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * A heading object as the notebook screen holds it: recognized (or edited) markdown text with its
 * hash prefix, the authoritative level, and its box in page px. The `text` prefix is derived from
 * [level] via `HeadingPrefix.applyLevel` at every write — never the other way around.
 */
data class Heading(
    val id: String,
    /** Hash-prefixed markdown, e.g. `"## Meeting notes"`. Always non-null in SN — a heading with no
     *  recognized text never exists (the og stroke-fallback state is deliberately absent here). */
    val text: String,
    /** 1..6 — the authoritative level (`flags` column). */
    val level: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Z-order among the page's headings (`"order"` column). */
    val order: Int,
) {
    val bounds: Bounds get() = Bounds(x, y, x + width, y + height)

    fun translated(dx: Float, dy: Float): Heading = copy(x = x + dx, y = y + dy)
}

/**
 * The one place a [Heading] becomes a `heading` row and back — the arc-3 additive family row type
 * (`SoilSchema.TYPE_HEADING` documents the column contract). Pure Kotlin — JVM-tested.
 */
object HeadingRows {

    fun toRow(heading: Heading, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = heading.id, parentId = pageId, type = SoilSchema.TYPE_HEADING, order = heading.order,
        createdAt = now, updatedAt = now,
        text = heading.text, flags = heading.level,
        x = heading.x, y = heading.y, width = heading.width, height = heading.height,
    )

    /**
     * Decode one row; null when `text` is missing — the contract says it never is, but a row
     * written by something else must degrade to "not rendered", never crash the page. The level is
     * clamped for the same reason.
     */
    fun toHeading(row: SoilObjectEntity): Heading? {
        val text = row.text ?: return null
        return Heading(
            id = row.id, text = text,
            level = (row.flags ?: 1).coerceIn(1, 6),
            x = row.x ?: 0f, y = row.y ?: 0f,
            width = row.width ?: 0f, height = row.height ?: 0f,
            order = row.order,
        )
    }
}
