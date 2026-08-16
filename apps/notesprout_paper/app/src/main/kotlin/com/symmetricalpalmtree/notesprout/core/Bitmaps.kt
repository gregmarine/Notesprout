package com.symmetricalpalmtree.notesprout.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Bitmaps {
    /**
     * Header-only probe: true if [bytes] decode to an image with a positive size. Cheap (no pixel
     * allocation) — used to reject an undecodable payload before it is stored anywhere.
     */
    fun isDecodable(bytes: ByteArray?): Boolean = bounds(bytes) != null

    /** Header-only probe: the image's `width to height`, or null if [bytes] is not a decodable image. */
    fun imageSize(bytes: ByteArray?): Pair<Int, Int>? = bounds(bytes)?.let { it.outWidth to it.outHeight }

    /**
     * Sampled decode capped at [maxEdge] px on the long side, so a hostile or oversized blob
     * (template, cover) can never allocate an unbounded bitmap. Null on garbage.
     */
    fun decodeBounded(bytes: ByteArray?, maxEdge: Int): Bitmap? {
        val bounds = bounds(bytes) ?: return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try { BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size, opts) } catch (_: OutOfMemoryError) { null }
    }

    /** Header decode only; null unless [bytes] is a non-empty image with a positive size. */
    private fun bounds(bytes: ByteArray?): BitmapFactory.Options? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds else null
    }
}
