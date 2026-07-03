package com.notesprout.android.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Central encoder for the raster images NoteSprout stores *inside* its databases — page snapshots,
 * notebook covers, and the template library (both the `.soil` embedded copy and the global index).
 *
 * These are all persisted as base64 in a `data` TEXT column. They used to be PNG. We store **WEBP at
 * quality 100** instead: on our high-contrast, transparent-alpha ink content this measured ~47%
 * smaller than PNG on-device and pixel-identical on sampled ink (WEBP keeps alpha lossless; q100 luma
 * is exact for flat ink). We deliberately do NOT use `WEBP_LOSSLESS` — Android's Skia lossless encoder
 * bloats to 2–6× PNG on alpha content (measured), the opposite of the goal. q100 lossy is the win.
 *
 * Decoding is deliberately NOT centralised — every read path already uses
 * [BitmapFactory.decodeByteArray], which detects PNG vs WEBP from the byte header, so old PNG blobs
 * and new WEBP blobs coexist with no format flag and no DB migration.
 *
 * External deliverables (PDF/image export in `NotebookExporter`) intentionally do **not** use this —
 * they stay in their portable PNG/JPEG formats.
 */
object ImageCodec {

    /** Compress [bmp] to WEBP q100 and return it base64-encoded ([Base64.NO_WRAP]). */
    fun encodeBase64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        compress(bmp, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Re-encode an existing base64 image (any format the platform can decode) as WEBP q100 base64.
     * Decodes at full resolution. Returns null on empty/undecodable input so the migration can
     * safely leave such a row untouched.
     */
    fun transcodeToWebpBase64(base64: String): String? {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) { return null }
        return transcodeBytesToWebpBase64(bytes)
    }

    /**
     * Decode raw image [bytes] (e.g. an imported PNG file) at full resolution and re-encode as WEBP
     * q100 base64. Returns null on empty/undecodable input; callers that must not lose the import
     * should fall back to storing the original bytes.
     */
    fun transcodeBytesToWebpBase64(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try { encodeBase64(bmp) } finally { bmp.recycle() }
    }

    /**
     * WEBP quality-100 compression. On API 30+ this is [Bitmap.CompressFormat.WEBP_LOSSY]; on API 29
     * the deprecated [Bitmap.CompressFormat.WEBP] is the same lossy encoder at that quality.
     */
    private fun compress(bmp: Bitmap, out: OutputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 100, out)
        } else {
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP, 100, out)
        }
    }
}
