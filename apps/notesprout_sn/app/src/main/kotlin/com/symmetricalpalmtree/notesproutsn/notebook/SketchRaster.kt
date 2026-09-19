package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import java.io.IOException

/**
 * **A page's sketch, drawn** (arc 43 / K7; the two-raster flatten since arc 45 / G2) —
 * [PageRaster]'s sibling for the other half of a sketch notebook's page: the stored rasters
 * composited over plain white at the page's own pixel size and encoded exactly as an ink page is,
 * so an export's two pages of one page are the same kind of picture in the same container.
 *
 * It is a file of its own rather than a branch in [PageRaster] because the two draw nothing in
 * common: an ink page is a template plus rows walked in the layering order, a sketch page is two
 * decodes and two blits. What they share is the *rules* — [Bitmap.Config.RGB_565] over an opaque
 * ground, lossy-WEBP q100 ([BuiltInTemplates.toWebp], the app's one measured encoder), the page's
 * own size at scale 1, and one page bitmap alive at a time — and those are kept here by repetition,
 * which is cheaper than a shared abstraction over two unlike drawings.
 *
 * **The flatten is ink over graphite** (arc 46, the user's decision of 2026-09-19 — "a white gel
 * pen can write over anything"; g-paper 0.1.44's own flatten, said the same way here). Graphite is
 * drawn plain and ink is drawn **over** it, plain `SRC_OVER`. Arc 45 flattened with
 * [PorterDuff.Mode.DARKEN] — the darker per channel, order-independent — which could never show a
 * white or pale ink over darker graphite. The order is the media's, not a layer to record in the
 * file or explain: gel ink sits on the sheet over graphite, and graphite over dry ink mostly
 * slides off, so pencil over an ink line is hidden by it. Where a raster is transparent the other
 * stands; with a black pen the two operators are pixel-identical.
 *
 * **Plain white, never the template** (decision 10). The paper under a sketch in the sketch face is
 * white — no template crosses that seam — so an exported sketch page that ruled a grid under the
 * pencil would be showing something the person never drew on. The ink page beside it carries the
 * paper, and carries it under the ink it was written on.
 *
 * **Not JVM-testable**, like [PageRaster] and for the same reason: every line is `Bitmap`,
 * `BitmapFactory`, `Canvas` and the platform encoder, all of which are stubs off-device. The rules
 * above are pinned by the walk; what is pure (which pages get a sketch page, where each lands in
 * the bundle, what each is called) lives in
 * [com.symmetricalpalmtree.notesproutsn.export.ExportRender] and
 * [com.symmetricalpalmtree.notesproutsn.export.ExportNaming] with its own tests.
 *
 * Pixels are never logged here — the callers log bytes and ids.
 */
object SketchRaster {

    /**
     * [graphite] then [ink] over white at [widthPx] × [heightPx], flattened and encoded. Either may
     * be null or empty — a page drawn in pencil alone, or in the gel pen alone, is an ordinary page
     * with one raster — and a page with neither is [blank] by another road.
     *
     * The bytes are the guarded ones — the caller has already put each past the shared header guard
     * ([com.symmetricalpalmtree.notesproutsn.data.soil.SketchRows.fitsPage]: a WebP of exactly this
     * page's size, and nothing else) — so a decode here is expected to succeed, and a decode that
     * does not is a *failure*, not a blank page.
     *
     * Throws [IOException] when a decode or the ground would not allocate, the bake's rule kept
     * whole ([PageRaster.toWebp] says the same): a caller that cannot draw a page has a sentence
     * for it, and a truncated or silently wrong picture must never reach the bundle. `Throwable` is
     * caught around each decode because an allocation the device refuses arrives as an
     * `OutOfMemoryError`, which is not an `Exception` ([SketchCover] catches it in the same place).
     *
     * **Two bitmaps at the high-water mark, still** — the `RGB_565` ground and **one** decoded
     * raster: graphite is decoded, drawn and recycled *before* ink is decoded, so two page-sized
     * rasters are never alive together and the second picture costs nothing at the peak. Full size,
     * not sampled — this is the exported page itself, where [SketchCover] is a card and can afford
     * to lose the pixels.
     */
    fun toWebp(widthPx: Int, heightPx: Int, graphite: ByteArray?, ink: ByteArray?): ByteArray =
        draw(widthPx, heightPx) { canvas ->
            // Graphite plain: the first thing over the white ground, so there is nothing yet for a
            // composite to be about.
            blit(canvas, graphite, widthPx, heightPx, null)
            // Ink over it: plain SRC_OVER — ink on top wherever they meet (arc 46, g-paper 0.1.44).
            blit(canvas, ink, widthPx, heightPx, null)
        }

    /**
     * The white sheet alone — the honest stand-in for a sketch page the bundle has **already
     * declared** and the file can no longer produce (both rows were refused by the header guard, or
     * vanished between the plan and the bake). See `ExportRender`'s bake for why a blank page is
     * written rather than one page fewer.
     */
    fun blank(widthPx: Int, heightPx: Int): ByteArray = draw(widthPx, heightPx) {}

    /** Decode one raster, draw it with [paint], recycle it. Nothing at all for a layer with no
     *  bytes, which is what "a page with only ink" means one call up. */
    private fun blit(canvas: Canvas, bytes: ByteArray?, widthPx: Int, heightPx: Int, paint: Paint?) {
        if (bytes == null || bytes.isEmpty()) return
        val decoded = try {
            // ARGB_8888: a raster is transparent where it is empty, and the transparency is the
            // whole point of compositing it over the ground rather than blitting it.
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (e: Throwable) {
            throw IOException("a ${widthPx}x$heightPx sketch page would not allocate", e)
        }
        val raster = decoded ?: throw IOException("a ${widthPx}x$heightPx sketch page would not decode")
        try {
            canvas.drawBitmap(raster, 0f, 0f, paint)
        } finally {
            // Before the next decode — which is what keeps the high-water mark at two bitmaps.
            raster.recycle()
        }
    }

    /** The white ground at the page's size, drawn on and encoded, recycled whatever happens. */
    private fun draw(widthPx: Int, heightPx: Int, onto: (Canvas) -> Unit): ByteArray {
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${widthPx}x$heightPx sketch page would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            onto(Canvas(bitmap))
            BuiltInTemplates.toWebp(bitmap)
        } finally {
            // Before the next page starts — the memory rule, kept where it cannot be forgotten.
            bitmap.recycle()
        }
    }
}
