package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The one place a [PageLink] becomes a `link` row and back. `style` carries the provider identity
 * (opaque provenance), `text` the opaque payload (capped at
 * [ExtensionContract.MAX_LINK_PAYLOAD_CHARS] in both directions — the file is untrusted input too),
 * `x`/`y`/`width`/`height` the bounds in page px, `"order"` the z-order among the page's links.
 * `refId`, `color`, `strokeWidth`, `flags`, `blob` stay null. The wrapped children are rows of their
 * own — they are passed in and out, never encoded here. Pure Kotlin — JVM-tested ([PageLinkTest]).
 */
object LinkRows {

    fun toRow(link: PageLink, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = link.id, parentId = pageId, type = SoilSchema.TYPE_LINK, order = link.order,
        createdAt = now, updatedAt = now,
        text = cap(link.payload), style = link.providerIdentity,
        x = link.x, y = link.y, width = link.width, height = link.height,
    )

    /**
     * Decode one row with its already-decoded children; null when it is not a usable link — wrong
     * type, no provider identity, or any of the four bounds missing/unusable (the caller drops it and
     * the page still renders). A null payload reads as "" (a provider may store nothing yet).
     */
    fun toLink(row: SoilObjectEntity, strokes: List<Stroke>, objects: List<PageObject>): PageLink? {
        if (row.type != SoilSchema.TYPE_LINK) return null
        val identity = row.style?.takeIf { it.isNotBlank() } ?: return null
        val x = row.x ?: return null
        val y = row.y ?: return null
        val w = row.width ?: return null
        val h = row.height ?: return null
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        return PageLink(
            id = row.id, providerIdentity = identity, payload = cap(row.text ?: ""),
            x = x, y = y, width = w, height = h, order = row.order,
            strokes = strokes, objects = objects,
        )
    }

    /** Truncate a payload to the contract cap. */
    fun cap(payload: String): String =
        if (payload.length <= ExtensionContract.MAX_LINK_PAYLOAD_CHARS) payload
        else payload.substring(0, ExtensionContract.MAX_LINK_PAYLOAD_CHARS)
}
