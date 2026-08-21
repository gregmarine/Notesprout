package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The library card's cover: the current page rendered by g-paper (template + committed ink),
 * scaled to [LONG_EDGE_PX] on its long side, lossy WEBP q100, stored on the notebook's index row.
 * Only ever the cover — never any other content — and only in the encrypted index.
 */
object CoverSnapshot {

    private const val TAG = "CoverSnapshot"
    const val LONG_EDGE_PX = 512

    /** Render on the caller's (main) thread, encode + store on IO. Never throws. */
    suspend fun capture(paper: PaperView, notebookId: String, repo: IndexRepository) {
        val full = try { paper.renderToBitmap() } catch (e: Exception) { Log.w(TAG, "render failed", e); null } ?: return
        withContext(Dispatchers.IO) {
            try {
                repo.setCover(notebookId, encode(full))
            } catch (e: Exception) {
                Log.w(TAG, "cover store failed for $notebookId", e)
            } finally {
                full.recycle()
            }
        }
    }

    /** `WEBP_LOSSY` is API 30; on 29 the legacy `WEBP` at q100 is the closest available
     *  (minSdk is 29 for the family; every Supernote actually runs 30+). */
    fun encode(full: Bitmap): ByteArray {
        val scaled = scaleToLongEdge(full, LONG_EDGE_PX)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        scaled.compress(format, 100, out)
        if (scaled !== full) scaled.recycle()
        return out.toByteArray()
    }

    private fun scaleToLongEdge(src: Bitmap, edge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= edge) return src
        val f = edge.toFloat() / long
        val w = (src.width * f).toInt().coerceAtLeast(1)
        val h = (src.height * f).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
