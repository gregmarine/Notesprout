package com.symmetricalpalmtree.notesprout.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Bitmaps {
    /**
     * Sampled decode capped at [maxEdge] px on the long side, so a hostile or oversized blob
     * (template, cover) can never allocate an unbounded bitmap. Null on garbage.
     */
    fun decodeBounded(bytes: ByteArray?, maxEdge: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) } catch (_: OutOfMemoryError) { null }
    }
}
