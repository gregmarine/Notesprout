package com.notesprout.android.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Bounded image decode helper for embedded `.soil` assets.
 *
 * `.soil` files are portable user documents, so an oversized or maliciously crafted embedded
 * image (template / snapshot / cover) decoded at full resolution can OOM-crash the app on open
 * or export — a low threshold on e-ink devices (M-1). These helpers decode bounds-first and
 * apply `inSampleSize`, so decode memory is capped to roughly the target dimensions instead of
 * the source's. Mirrors the sampling used in `CoverDialog.encodeImageFromUri`.
 */
object BitmapDecode {

    /** Ceiling for decodes that have no natural target size (e.g. a cover defining a PDF page). */
    const val MAX_DIMENSION = 4096

    /**
     * Decode [bytes] downsampled so neither dimension greatly exceeds [reqW] × [reqH].
     * Returns null on empty input, non-positive bounds, or decode failure. The result is sampled
     * (a power-of-two fraction of the source), not scaled to an exact size — callers draw it into
     * a target rect anyway, so exact dimensions don't matter.
     */
    fun decodeSampled(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
        if (bytes.isEmpty() || reqW <= 0 || reqH <= 0) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var inSampleSize = 1
        while (srcW / inSampleSize > reqW * 2 || srcH / inSampleSize > reqH * 2) {
            inSampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
