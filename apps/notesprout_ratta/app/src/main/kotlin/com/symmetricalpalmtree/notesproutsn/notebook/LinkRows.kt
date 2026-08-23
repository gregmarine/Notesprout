package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * The one place a [PageLink] becomes a `link` row and back — the arc-6 additive family row type
 * (`SoilSchema.TYPE_LINK` documents the column contract). Family deltas from Paper's `LinkRows`,
 * both locked: `style` is **written null** (Paper put its provider identity there — SN has no
 * provider) and **read leniently** (never required, never routed on — a Paper-created link row
 * decodes fine); `chrome` is parsed out of the payload at decode ([LinkPayload.chromeOf]), never
 * cached in `flags`. The wrapped children are rows of their own — they are passed in and out,
 * never encoded here. Pure Kotlin — JVM-tested.
 */
object LinkRows {

    fun toRow(link: PageLink, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = link.id, parentId = pageId, type = SoilSchema.TYPE_LINK, order = link.order,
        createdAt = now, updatedAt = now,
        text = cap(link.payload),
        x = link.x, y = link.y, width = link.width, height = link.height,
    )

    /**
     * Decode one row with its already-decoded children; null when it is not a usable link — wrong
     * type or any of the four bounds missing/unusable (the caller drops it and the page still
     * renders). A null payload reads as `""` and an unusable one degrades to no chrome — content
     * still shows; only the follow explains itself (K4's dead-target rule).
     */
    fun toLink(row: SoilObjectEntity, strokes: List<Stroke>, headings: List<Heading>): PageLink? {
        if (row.type != SoilSchema.TYPE_LINK) return null
        val x = row.x ?: return null
        val y = row.y ?: return null
        val w = row.width ?: return null
        val h = row.height ?: return null
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        val payload = cap(row.text ?: "")
        return PageLink(
            id = row.id, payload = payload, chrome = LinkPayload.chromeOf(payload),
            x = x, y = y, width = w, height = h, order = row.order,
            strokes = strokes, headings = headings,
        )
    }

    /** Truncate a payload to the family cap (the file is untrusted input too). */
    fun cap(payload: String): String =
        if (payload.length <= LinkPayload.MAX_PAYLOAD_CHARS) payload
        else payload.substring(0, LinkPayload.MAX_PAYLOAD_CHARS)
}
