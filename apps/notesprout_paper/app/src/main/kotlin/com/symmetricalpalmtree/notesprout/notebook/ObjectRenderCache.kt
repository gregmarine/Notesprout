package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Bitmap

/**
 * Rendered object bitmaps for the **open notebook only** (arc 4) — nothing is ever persisted (the
 * user's decision: text-only rows, no stored bitmap). One entry per object id, valid for exactly one
 * (payload, maxWidth, dpi) triple: a changed payload or a different render width/dpi is a miss, so
 * a stale image is never drawn. H1 built the cache and the placeholder path; `ObjectRenderPass` fills it
 * (through the object provider → Markdown proxy) since H4. Main thread only.
 */
class ObjectRenderCache {

    private class Entry(val payloadHash: Int, val maxWidth: Int, val dpi: Float, val bitmap: Bitmap)

    private val entries = HashMap<String, Entry>()

    /**
     * The cached bitmap for [objectId] if it was rendered from exactly this payload at this dpi and
     * is still right for [maxWidth]: rendered at this very width, **or** rendered unconstrained
     * (narrower than the width it was given) and still narrower than the width asked now — a move
     * that doesn't push the object against the page's right edge is not a re-render.
     */
    fun get(objectId: String, payload: String, maxWidth: Int, dpi: Float): Bitmap? {
        val e = entries[objectId] ?: return null
        if (e.payloadHash != payload.hashCode() || e.dpi != dpi || e.bitmap.isRecycled) return null
        val fits = e.maxWidth == maxWidth || (e.bitmap.width < e.maxWidth && e.bitmap.width <= maxWidth)
        return if (fits) e.bitmap else null
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
