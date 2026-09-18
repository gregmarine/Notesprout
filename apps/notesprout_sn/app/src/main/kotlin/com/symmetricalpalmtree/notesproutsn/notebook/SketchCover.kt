package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A **sketch notebook's** library cover (arc 43 / K4, decision 6; the two-raster flatten since arc
 * 45 / G2): the sketch of the page the face ended on, composited **over paper white** and stored on
 * the notebook's index row — the same slot [CoverSnapshot] fills for a handwritten notebook and
 * [TextCover] for a text document, filled by the thing a sketch notebook actually has to show.
 *
 * It is drawn from the **stored rows**, never from the extension's surface: the pixels are over
 * there, in another process, and the only honest picture this side can draw is the one that landed
 * — which is why the caller reads the rows *after* the showing's `end()` flush has been joined. A
 * page with neither raster is not this class's business at all; the caller falls back to the ink
 * bake, and only when the canvas has actually been loaded (a snapshot of an unloaded surface is a
 * blank card where a drawing used to be).
 *
 * **The flatten is [SketchRaster]'s, sampled** — graphite plain, then ink with
 * [PorterDuff.Mode.DARKEN] over it, each channel the darker of the two. Order-independent, so the
 * card shows the same picture the export does and the face does, with no layer order to keep in
 * step. Both decodes take the **same** sample size, computed from the page's own size rather than
 * from each image, so the two register pixel-for-pixel: a card whose ink was sampled one step
 * differently from its graphite would be two drawings sliding over each other.
 *
 * **White, always** (decision 10). A page's rasters are transparent where they are empty and the
 * paper under them in the sketch face is plain white — no template crosses that seam — so the card
 * shows exactly what the person was drawing on. Compositing onto a transparent card would leave the
 * library's own background showing through the strokes.
 *
 * **One page bitmap alive, and never a full-size one.** A Manta page is 1860 × 2480 — 18 MB at
 * ARGB_8888 — and the card needs 512 px on its long edge. So each raster is decoded **sampled**
 * ([sampleFor], pure and tested): the decoder never allocates the full page, the white ground is an
 * `RGB_565` bitmap of the sampled size (half the memory, and the result has no alpha in it), and
 * each sampled raster is recycled the moment it has been drawn — so the second one costs nothing at
 * the peak.
 *
 * **The header guard stands in front of every decode**, as it does everywhere pixels are read
 * ([ImageHeader]): a malformed or mis-sized image is refused here rather than handed to a decoder —
 * cheap to rule out in 30 bytes, expensive to discover afterwards. A raster whose header does not
 * read is simply left out of the flatten; the other one still makes a card.
 *
 * Never throws — a cover is a nicety, and a failed one leaves the card exactly as it was. Pixels
 * are never logged; sizes only.
 */
object SketchCover {

    private const val TAG = "SketchCover"

    /**
     * Render [graphite] and [ink] flattened over white and store the result on [notebookId]'s row.
     * Either may be null or empty. Answers whether a cover was actually written, so the caller can
     * fall back to the ink bake when it was not — which includes the case where **both** rasters are
     * absent or unreadable.
     */
    suspend fun render(
        repo: IndexRepository,
        notebookId: String,
        graphite: ByteArray?,
        ink: ByteArray?,
    ): Boolean = withContext(Dispatchers.IO) {
        // The page's size, from whichever raster can say — they are both exactly the page's size or
        // they would not have got past the guard, so the first readable header settles it for both.
        val header = headerOf(graphite) ?: headerOf(ink)
        if (header == null) {
            Log.w(
                TAG,
                "sketch cover skipped for $notebookId: " +
                    "${graphite?.size ?: 0} B graphite + ${ink?.size ?: 0} B ink with no WebP header",
            )
            return@withContext false
        }
        val cover = try {
            draw(graphite, ink, header.width, header.height)
        } catch (e: Exception) {
            // Includes the allocation the device refused. A card with no new cover is a card.
            Log.w(TAG, "sketch cover render failed: ${e.javaClass.simpleName}")
            null
        } ?: return@withContext false
        try {
            repo.setCover(notebookId, CoverSnapshot.encode(cover))
            Slog.d(TAG) {
                "sketch cover stored for $notebookId " +
                    "(${graphite?.size ?: 0} B graphite + ${ink?.size ?: 0} B ink source)"
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sketch cover store failed for $notebookId: ${e.javaClass.simpleName}")
            false
        } finally {
            cover.recycle()
        }
    }

    /** A raster's header, or null for an absent, empty or unreadable one. */
    private fun headerOf(bytes: ByteArray?): ImageHeader.Header? =
        if (bytes == null || bytes.isEmpty()) null else ImageHeader.parse(bytes)

    /**
     * The white page with the two rasters flattened onto it, at the sampled size. The caller owns
     * the returned bitmap and must recycle it; null when neither raster would decode.
     *
     * The ground is allocated from the **sampled page size** rather than from a decoded bitmap, so
     * it exists before either decode and both rasters draw onto the same sheet at the same scale.
     */
    private fun draw(graphite: ByteArray?, ink: ByteArray?, width: Int, height: Int): Bitmap? {
        val sample = sampleFor(width, height, CoverSnapshot.LONG_EDGE_PX)
        // Ceiling division: `inSampleSize` rounds a decoded edge up, and a ground one pixel short
        // would clip the last row of the drawing.
        val groundW = (width + sample - 1) / sample
        val groundH = (height + sample - 1) / sample
        if (groundW < 1 || groundH < 1) return null
        val cover = Bitmap.createBitmap(groundW, groundH, Bitmap.Config.RGB_565)
        // The ground is handed to the caller only if something was actually drawn on it; on every
        // other way out of this function — including a throw — it is recycled here.
        var keep = false
        return try {
            val canvas = Canvas(cover)
            canvas.drawColor(Color.WHITE)
            var drew = blit(canvas, graphite, sample, null)
            drew = blit(canvas, ink, sample, darkenPaint()) || drew
            keep = drew
            if (drew) cover else null
        } finally {
            if (!keep) cover.recycle()
        }
    }

    /** Decode one raster at [sample], draw it with [paint], recycle it. False when there was
     *  nothing to draw or the decoder refused the bytes — a card with one raster is still a card. */
    private fun blit(canvas: Canvas, bytes: ByteArray?, sample: Int, paint: Paint?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // The raster is transparent where it is empty — the one place ARGB_8888 is needed, and
            // it is needed only for the sampled copy, which is thrown away two lines later.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raster = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        return try {
            canvas.drawBitmap(raster, 0f, 0f, paint)
            true
        } finally {
            raster.recycle()
        }
    }

    /** [SketchRaster]'s composite, said the same way here — the darker of the two per channel, and
     *  a fresh `Paint` per call for the same reason it is fresh there. */
    private fun darkenPaint(): Paint =
        Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN) }

    /**
     * `inSampleSize` for a [width] × [height] image wanted at about [edge] px on its long side: the
     * largest power of two that still leaves the long edge at [edge] or above, so the decode is as
     * cheap as it can be without the card ever being scaled **up**. Pure, so the arithmetic is
     * pinned by test rather than by looking at a device — and one number for both rasters, which is
     * what makes them register.
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
