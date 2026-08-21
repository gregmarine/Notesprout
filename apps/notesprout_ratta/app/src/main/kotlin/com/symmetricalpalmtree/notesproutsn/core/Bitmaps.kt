package com.symmetricalpalmtree.notesproutsn.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Bitmaps {
    /**
     * Sampled decode capped at [maxEdge] px on the long side. A cover blob is written by this app,
     * but it is still bytes out of a database file — a corrupt or oversized one must not be able to
     * allocate an unbounded bitmap on a memory-tight e-ink device. Null on garbage.
     */
    fun decodeBounded(bytes: ByteArray?, maxEdge: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty() || maxEdge <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
