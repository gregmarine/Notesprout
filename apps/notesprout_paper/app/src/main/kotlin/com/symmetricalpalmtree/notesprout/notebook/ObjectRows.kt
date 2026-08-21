package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesprout.data.soil.SoilSchema
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The one place a [PageObject] becomes an `object` row and back. `style` carries the provider
 * identity, `text` the opaque payload (capped at [ExtensionContract.MAX_OBJECT_TEXT_CHARS] in both
 * directions — the file is untrusted input too), `x`/`y`/`width`/`height` the bounds in page px,
 * `"order"` the z-order. `refId`, `color`, `strokeWidth`, `flags`, `blob` stay null. Pure Kotlin —
 * JVM-tested ([ObjectRowsTest]).
 */
object ObjectRows {

    fun toRow(obj: PageObject, pageId: String, now: Long): SoilObjectEntity = SoilObjectEntity(
        id = obj.id, parentId = pageId, type = SoilSchema.TYPE_OBJECT, order = obj.order,
        createdAt = now, updatedAt = now,
        text = cap(obj.payload), style = obj.providerIdentity,
        x = obj.x, y = obj.y, width = obj.width, height = obj.height,
    )

    /**
     * Decode one row; null when it is not a usable object — wrong type, no provider identity, or any
     * of the four bounds missing (the caller drops it and the page still renders). A null payload
     * reads as "" (a provider may legitimately store nothing yet).
     */
    fun toObject(row: SoilObjectEntity): PageObject? {
        if (row.type != SoilSchema.TYPE_OBJECT) return null
        val identity = row.style?.takeIf { it.isNotBlank() } ?: return null
        val x = row.x ?: return null
        val y = row.y ?: return null
        val w = row.width ?: return null
        val h = row.height ?: return null
        if (!(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite()) || w < 0f || h < 0f) return null
        return PageObject(
            id = row.id, providerIdentity = identity, payload = cap(row.text ?: ""),
            x = x, y = y, width = w, height = h, order = row.order,
        )
    }

    /** Truncate a payload to the contract cap. */
    fun cap(payload: String): String =
        if (payload.length <= ExtensionContract.MAX_OBJECT_TEXT_CHARS) payload
        else payload.substring(0, ExtensionContract.MAX_OBJECT_TEXT_CHARS)
}
