package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import java.io.IOException

/**
 * **A page's sketch, drawn** (arc 43 / K7) — [PageRaster]'s sibling for the other half of a sketch
 * notebook's page: the stored PNG composited over plain white at the page's own pixel size and
 * encoded exactly as an ink page is, so an export's two pages of one page are the same kind of
 * picture in the same container.
 *
 * It is a file of its own rather than a branch in [PageRaster] because the two draw nothing in
 * common: an ink page is a template plus rows walked in the layering order, a sketch page is one
 * decode and one blit. What they share is the *rules* — [Bitmap.Config.RGB_565] over an opaque
 * ground, lossy-WEBP q100 ([BuiltInTemplates.toWebp], the app's one measured encoder), the page's
 * own size at scale 1, and one page bitmap alive at a time — and those are kept here by repetition,
 * which is cheaper than a shared abstraction over two unlike drawings.
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
     * [png] over white at [widthPx] × [heightPx], encoded. The bytes are the guarded ones — the
     * caller has already put them past the shared header guard
     * ([com.symmetricalpalmtree.notesproutsn.data.soil.SketchRows.fitsPage]: a PNG of exactly this
     * page's size, and nothing else) — so the decode here is expected to succeed, and a decode that
     * does not is a *failure*, not a blank page.
     *
     * Throws [IOException] when the decode or the ground would not allocate, the bake's rule kept
     * whole ([PageRaster.toWebp] says the same): a caller that cannot draw a page has a sentence
     * for it, and a truncated or silently wrong picture must never reach the bundle. `Throwable` is
     * caught around the decode because an allocation the device refuses arrives as an
     * `OutOfMemoryError`, which is not an `Exception` ([SketchCover] catches it in the same place).
     *
     * **Two bitmaps at the high-water mark, both gone before the next page starts**: the decoded
     * sketch is what the page is drawn from, the `RGB_565` ground is what is encoded, and the
     * `finally` recycles both whichever way this exits. Full size, not sampled — this is the
     * exported page itself, where [SketchCover] is a card and can afford to lose the pixels.
     */
    fun toWebp(widthPx: Int, heightPx: Int, png: ByteArray): ByteArray {
        val decoded = try {
            // ARGB_8888: a sketch is transparent where it is empty, and the transparency is the
            // whole point of compositing it over the ground rather than blitting it.
            BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (e: Throwable) {
            throw IOException("a ${widthPx}x$heightPx sketch page would not allocate", e)
        }
        val sketch = decoded ?: throw IOException("a ${widthPx}x$heightPx sketch page would not decode")
        return try {
            draw(widthPx, heightPx) { canvas -> canvas.drawBitmap(sketch, 0f, 0f, null) }
        } finally {
            sketch.recycle()
        }
    }

    /**
     * The white sheet alone — the honest stand-in for a sketch page the bundle has **already
     * declared** and the file can no longer produce (the row was refused by the header guard, or
     * vanished between the plan and the bake). See `ExportRender`'s bake for why a blank page is
     * written rather than one page fewer.
     */
    fun blank(widthPx: Int, heightPx: Int): ByteArray = draw(widthPx, heightPx) {}

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
