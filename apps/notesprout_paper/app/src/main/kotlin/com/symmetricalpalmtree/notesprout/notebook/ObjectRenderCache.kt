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
     * is still right for [maxWidth]: rendered at this very width, **or** rendered unconstrained and
     * still narrower than the width asked now — a move that doesn't push the object against the
     * page's right edge is not a re-render. "Unconstrained" = the image stopped **more than
     * [SLACK_DP] short** of the width it was given: an END-ellipsized line also lands a little under
     * its width (the glyph that didn't fit), so a bare `width < maxWidth` test would keep a truncated
     * image after the object was dragged back to where the whole text fits (H5). The slack is
     * generous (wider than any one heading glyph); the cost of guessing wrong on this side is one
     * identical re-render, on the other side a stale ellipsis.
     */
    fun get(objectId: String, payload: String, maxWidth: Int, dpi: Float): Bitmap? {
        val e = entries[objectId] ?: return null
        if (e.payloadHash != payload.hashCode() || e.dpi != dpi || e.bitmap.isRecycled) return null
        val slack = SLACK_DP * dpi / 160f
        val unconstrained = e.bitmap.width < e.maxWidth - slack
        val fits = e.maxWidth == maxWidth || (unconstrained && e.bitmap.width <= maxWidth)
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

    /** Keep only [objectIds] (the page just loaded); everything else is recycled — the cache is bounded by one page. */
    fun retain(objectIds: Set<String>) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in objectIds) { e.value.bitmap.recycle(); it.remove() }
        }
    }

    private companion object {
        /** See [get]: how far short of its render width an image must stop to count as unconstrained. */
        const val SLACK_DP = 64f
    }

    /** Drop everything (notebook close). */
    fun clear() {
        for (e in entries.values) e.bitmap.recycle()
        entries.clear()
    }
}
