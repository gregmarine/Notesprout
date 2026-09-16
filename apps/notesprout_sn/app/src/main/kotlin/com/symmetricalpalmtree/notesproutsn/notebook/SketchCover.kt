package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.extension.PngHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A **sketch notebook's** library cover (arc 43 / K4, decision 6): the sketch of the page the face
 * ended on, composited **over paper white** and stored on the notebook's index row — the same slot
 * [CoverSnapshot] fills for a handwritten notebook and [TextCover] for a text document, filled by
 * the thing a sketch notebook actually has to show.
 *
 * It is drawn from the **stored row**, never from the extension's surface: the pixels are over
 * there, in another process, and the only honest picture this side can draw is the one that landed
 * — which is why the caller reads the row *after* the showing's `end()` flush has been joined. A
 * page with no sketch row is not this class's business at all; the caller falls back to the ink
 * bake, and only when the canvas has actually been loaded (a snapshot of an unloaded surface is a
 * blank card where a drawing used to be).
 *
 * **White, always** (decision 10). A page's sketch is a transparent PNG and the paper under it in
 * the sketch face is plain white — no template crosses that seam — so the card shows exactly what
 * the person was drawing on. Compositing onto a transparent card would leave the library's own
 * background showing through the strokes.
 *
 * **One page bitmap alive, and never a full-size one.** A Manta page is 1860 × 2480 — 18 MB at
 * ARGB_8888 — and the card needs 512 px on its long edge. So the PNG is decoded **sampled**
 * ([sampleFor], pure and tested): the decoder never allocates the full page, the white ground is an
 * `RGB_565` bitmap of the sampled size (half the memory, and the result has no alpha in it), and
 * the sampled sketch is recycled the moment it has been drawn.
 *
 * **The header guard stands in front of the decode**, as it does everywhere pixels are read
 * ([PngHeader]): a malformed or mis-sized PNG is refused here rather than handed to a decoder —
 * cheap to rule out in 33 bytes, expensive to discover afterwards.
 *
 * Never throws — a cover is a nicety, and a failed one leaves the card exactly as it was. Pixels
 * are never logged; sizes only.
 */
object SketchCover {

    private const val TAG = "SketchCover"

    /**
     * Render [png] over white and store it on [notebookId]'s row. Answers whether a cover was
     * actually written, so the caller can fall back to the ink bake when it was not.
     */
    suspend fun render(repo: IndexRepository, notebookId: String, png: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val ihdr = PngHeader.parse(png)
            if (ihdr == null) {
                Log.w(TAG, "sketch cover skipped for $notebookId: ${png.size} B with no PNG header")
                return@withContext false
            }
            val cover = try {
                draw(png, ihdr.width, ihdr.height)
            } catch (e: Exception) {
                // Includes the allocation the device refused. A card with no new cover is a card.
                Log.w(TAG, "sketch cover render failed: ${e.javaClass.simpleName}")
                null
            } ?: return@withContext false
            try {
                repo.setCover(notebookId, CoverSnapshot.encode(cover))
                Slog.d(TAG) { "sketch cover stored for $notebookId (${png.size} B source)" }
                true
            } catch (e: Exception) {
                Log.w(TAG, "sketch cover store failed for $notebookId: ${e.javaClass.simpleName}")
                false
            } finally {
                cover.recycle()
            }
        }

    /**
     * The white page with [png] drawn on it, at the sampled size. The caller owns the returned
     * bitmap and must recycle it; null when the decoder refused the bytes.
     */
    private fun draw(png: ByteArray, width: Int, height: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(width, height, CoverSnapshot.LONG_EDGE_PX)
            // The sketch is transparent where it is empty — the one place ARGB_8888 is needed, and
            // it is needed only for the sampled copy, which is thrown away three lines later.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sketch = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return null
        return try {
            Bitmap.createBitmap(sketch.width, sketch.height, Bitmap.Config.RGB_565).apply {
                val canvas = Canvas(this)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(sketch, 0f, 0f, null)
            }
        } finally {
            sketch.recycle()
        }
    }

    /**
     * `inSampleSize` for a [width] × [height] image wanted at about [edge] px on its long side: the
     * largest power of two that still leaves the long edge at [edge] or above, so the decode is as
     * cheap as it can be without the card ever being scaled **up**. Pure, so the arithmetic is
     * pinned by test rather than by looking at a device.
     */
    fun sampleFor(width: Int, height: Int, edge: Int): Int {
        require(edge > 0) { "non-positive edge" }
        var sample = 1
        var long = maxOf(width, height)
        while (long / 2 >= edge) {
            long /= 2
            sample *= 2
        }
        return sample
    }
}
