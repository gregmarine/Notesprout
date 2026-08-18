package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Bitmap

/**
 * Rendered object bitmaps for the **open notebook only** (arc 4) — nothing is ever persisted (the
 * user's decision: text-only rows, no stored bitmap). One entry per object id, valid for exactly one
 * (payload, maxWidth, dpi) triple: a changed payload or a different render width/dpi is a miss, so
 * a stale image is never drawn. H1 builds the cache and the placeholder path; the render pass that
 * fills it (through the object provider → Markdown proxy) arrives in H4. Main thread only.
 */
class ObjectRenderCache {

    private class Entry(val payloadHash: Int, val maxWidth: Int, val dpi: Float, val bitmap: Bitmap)

    private val entries = HashMap<String, Entry>()

    /** The cached bitmap for [objectId] if it was rendered from exactly this payload at this width + dpi. */
    fun get(objectId: String, payload: String, maxWidth: Int, dpi: Float): Bitmap? {
        val e = entries[objectId] ?: return null
        if (e.payloadHash != payload.hashCode() || e.maxWidth != maxWidth || e.dpi != dpi || e.bitmap.isRecycled) return null
        return e.bitmap
    }

    fun put(objectId: String, payload: String, maxWidth: Int, dpi: Float, bitmap: Bitmap) {
        entries.remove(objectId)?.bitmap?.let { if (it !== bitmap) it.recycle() }
        entries[objectId] = Entry(payload.hashCode(), maxWidth, dpi, bitmap)
    }

    /** True when an image is cached for the object at all (any payload / width). */
    fun has(objectId: String): Boolean = entries.containsKey(objectId)

    fun remove(objectId: String) {
        entries.remove(objectId)?.bitmap?.recycle()
    }

    /** Drop everything (notebook close). */
    fun clear() {
        for (e in entries.values) e.bitmap.recycle()
        entries.clear()
    }
}
