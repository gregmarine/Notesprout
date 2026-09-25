package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import java.io.IOException

/**
 * **One page, drawn** (arc 31 / HV2) — the app's single recipe for turning a page's rows into
 * finished pixels, extracted from the export bake
 * ([com.symmetricalpalmtree.notesproutsn.export.ExportRender]) the moment a second caller wanted
 * exactly the same picture: **Save as template**, which turns the page on the glass into paper.
 *
 * It lives here rather than in `export/` because it is a *page* question, not an export one — what
 * a page looks like is the notebook's answer to give, and the bake was only ever its first reader.
 * Nothing about the drawing changed in the move: the same white ground, the same template rect, the
 * same [PagePreview.drawContent] layering, the same [Bitmap.Config.ARGB_8888] over an opaque ground
 * (arc 49 / P4 — `RGB_565` until then) and the same lossy-WEBP q100 encoder.
 *
 * Neither neighbour would do. [PagePreview.render] draws a **card**: it scales the page down and
 * rules an edge around it, which is chrome. [CoverSnapshot] renders at the *cover's* size. This one
 * renders the page at the **page's own** pixel size and scale 1 — a page authored on another panel
 * keeps its own edge, and the screen's size never enters this file.
 *
 * **Not JVM-testable.** Every line of it is `Bitmap`, `Canvas` and the platform encoder, all of
 * which are stubs off-device — so the rule this file carries is pinned by the walk and by its one
 * caller's tests, never by a unit test here. What *is* pure (the seeded name, the scope, the
 * naming) lives in its own objects with its own tests.
 */
object PageRaster {

    private const val TAG = "PageRaster"

    /** The page's paper, or null for blank — and null again when the row has gone or will not
     *  decode: paper that will not draw is not paper that is absent, but a page is still the ink
     *  that is on it, so the raster goes ahead on white (the arc-13 rule read from the export side). */
    suspend fun decodeTemplate(dao: SoilDao, templateId: String): Bitmap? {
        if (templateId.isEmpty()) return null
        val row: SoilObjectEntity = dao.byId(templateId) ?: return null
        val bitmap = Bitmaps.decodeBounded(row.blob, NotebookSession.MAX_TEMPLATE_EDGE)
        if (bitmap == null) Log.w(TAG, "a page's template would not decode — rendering it on white")
        return bitmap
    }

    /**
     * One page, full fidelity at its own pixel size. Opaque by construction: erased to white, and
     * every layer lands on top. **[Bitmap.Config.ARGB_8888] since arc 49 / P4** (the user's
     * decision 4: "exports bake `ARGB_8888`"): the pen writes in sixteen greys now, and `RGB_565`
     * rounds every tone to five or six bits — `#505050` comes out `#525152`, a slightly warm, slightly
     * lighter grey that is not the one that was chosen. The F5 rule (half the bytes, no alpha to
     * lose) stood while every stroke was black; it costs one page-sized image at a time, which the
     * one-page-in-memory rule already bounds.
     *
     * [template] is drawn into the whole page rect rather than blitted 1:1. It is authored at the
     * page's size in every file this app writes, so the rect is normally a no-op scale; a template
     * that disagrees (a page pasted from a panel of another size, a sampled decode of an oversized
     * import) then rules the page edge to edge instead of leaving a bare band.
     *
     * Throws [IOException] when the page would not allocate — the bake's rule, kept: a caller that
     * cannot draw a page has a sentence for it, and a truncated picture must never reach either the
     * bundle or the library.
     */
    fun toWebp(
        widthPx: Int,
        heightPx: Int,
        template: Bitmap?,
        content: PageContent,
        density: Float,
        paints: PagePreview.Paints,
    ): ByteArray {
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${widthPx}x$heightPx page would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            if (template != null) {
                canvas.drawBitmap(template, null, Rect(0, 0, widthPx, heightPx), templatePaint)
            }
            PagePreview.drawContent(canvas, content, density, paints)
            BuiltInTemplates.toWebp(bitmap)
        } finally {
            // Before the next page starts — the memory rule, kept where it cannot be forgotten.
            bitmap.recycle()
        }
    }

    /** Filtered because a template may be scaled into the page rect; no alpha involved either way. */
    private val templatePaint = Paint(Paint.FILTER_BITMAP_FLAG)
}
