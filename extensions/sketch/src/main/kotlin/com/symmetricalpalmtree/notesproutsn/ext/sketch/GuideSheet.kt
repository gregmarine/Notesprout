package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import com.symmetricalpalmtree.notesproutsn.core.ImageFit
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The **guide sheet** (arc 51 "Guides" / J3) — the one page-sized picture the face hands g-paper's
 * `PaperView.setSheet`: the reference image at its opacity, and the grid over it. The sheet is a
 * tool and never a mark — g-paper draws it over white and under both rasters, on the window and on
 * the Supernote panel, and never exports, covers, rubs or smudges it (Phase 46). Nothing here is
 * ever drawn into a raster.
 *
 * **The walk knobs live here and nowhere else** (the user's decision 7): the grid's tone, its line
 * weight and dot size, the two ladders the panel offers, their defaults, and the reference image's
 * encode quality. The host never learns any of them — a count and a percent cross the seam, not a
 * ladder position — so retuning one never strands a stored value.
 *
 * The grid tone is **page content, not chrome**: it is the grey the sheet is made of on the paper,
 * exactly as a pencil's shade is, and appears in no button or bar.
 */
object GuideSheet {

    /** Atelier's level 9 (`#aaaaaa`) — light enough to draw over, dark enough to dither to a
     *  visible dotted line on the panel. */
    const val GRID_TONE: Int = 0xFFAAAAAA.toInt()

    /** A grid line's weight, in page px. */
    const val LINE_PX: Int = 2

    /** A grid dot's radius, in page px. */
    const val DOT_RADIUS_PX: Float = 4f

    /** Cells across the page's width, as the panel offers them (the user's decision 7; grown
     *  past 12 on the J4 walk — "let's go up to 24", then "up to 32", and "drop the 3"). */
    val COUNTS: List<Int> = listOf(2, 4, 6, 8, 12, 16, 20, 24, 28, 32)

    /** The panel lays the counts out in rows of this many (the J4 walk: "put the counts into 2
     *  rows to fit better") — `2 4 6 8 12` over `16 20 24 28 32`. */
    const val COUNT_ROW_BREAK: Int = 5

    /** [COUNTS] as the panel's rows. */
    fun countRows(): List<List<Int>> = COUNTS.chunked(COUNT_ROW_BREAK)

    /** The reference image's opacities, percent (the user's decision 7). */
    val OPACITIES: List<Int> = listOf(10, 25, 50, 75)

    const val DEFAULT_COUNT: Int = 4
    const val DEFAULT_OPACITY: Int = 25

    /** The reference image's lossy WebP quality (the user's decision 4). The image is a thing to
     *  trace, seen at a quarter strength through a dither — never a thing to keep. */
    const val IMAGE_QUALITY: Int = 90

    /**
     * The sheet for [state] on a [pageWidth] × [pageHeight] page — `ARGB_8888`, transparent where
     * nothing is laid — or **null** when nothing is shown (both absent or hidden), which is
     * `setSheet(null)`.
     *
     * [image] is the stored reference: already page-sized with the fit baked in, so it is drawn at
     * the origin, 1:1, at the state's opacity. The grid goes **over** it (decision 6). An image the
     * state says is shown but which is null (it would not decode) simply is not drawn.
     *
     * Off the main thread: a page-sized allocation and, with an image, a page-sized blit.
     */
    fun render(pageWidth: Int, pageHeight: Int, state: GuideState, image: Bitmap?): Bitmap? {
        val drawImage = state.showsImage && image != null && !image.isRecycled
        val grid = if (state.showsGrid) {
            GridLayout.plan(state.gridKind, state.gridCount, pageWidth, pageHeight)
        } else {
            null
        }
        if (!drawImage && grid == null) return null
        if (pageWidth <= 0 || pageHeight <= 0) return null

        val sheet = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        if (drawImage) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = state.imageOpacity * 255 / 100
            }
            canvas.drawBitmap(image!!, 0f, 0f, paint)
        }
        if (grid != null) drawGrid(canvas, grid, pageWidth, pageHeight)
        return sheet
    }

    /** Lines as whole-pixel bars (crisp on the dither — an antialiased 2 px line at a fractional
     *  position is two grey pixels, not a line); dots as antialiased discs at every crossing. */
    private fun drawGrid(canvas: Canvas, plan: GridLayout.Plan, pageWidth: Int, pageHeight: Int) {
        if (plan.kind == SketchContract.GRID_DOTS) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID_TONE; style = Paint.Style.FILL }
            for (y in plan.ys) for (x in plan.xs) canvas.drawCircle(x, y, DOT_RADIUS_PX, paint)
            return
        }
        val paint = Paint().apply { color = GRID_TONE; style = Paint.Style.FILL }
        val w = pageWidth.toFloat()
        val h = pageHeight.toFloat()
        for (x in plan.xs) {
            val left = barStart(x).toFloat()
            canvas.drawRect(left, 0f, left + LINE_PX, h, paint)
        }
        for (y in plan.ys) {
            val top = barStart(y).toFloat()
            canvas.drawRect(0f, top, w, top + LINE_PX, paint)
        }
    }

    /** The first pixel of a [LINE_PX]-wide bar centred on [at]. */
    private fun barStart(at: Float): Int = (at - LINE_PX / 2f).roundToInt()

    /**
     * A picked picture [source] as the page's stored reference: a fresh page-sized transparent
     * `ARGB_8888` with the picture **fit** into it — whole, centred, aspect kept ([ImageFit], the
     * user's decision 4). Null when either size is degenerate. Off the main thread.
     *
     * **One pixel is never fully opaque**, on purpose: libwebp writes a *simple* `VP8 ` file — no
     * `VP8X` header — for a picture with no transparency at all, and both the host's save guard and
     * this face's load guard read the page size from `VP8X`. A picture whose aspect is exactly the
     * page's fills every pixel, so the corner pixel's alpha is taken to 254 — invisible at any
     * opacity the panel offers, and the difference between that picture saving and being refused.
     */
    fun fitToPage(source: Bitmap, pageWidth: Int, pageHeight: Int): Bitmap? {
        val fit = ImageFit.plan(source.width, source.height, pageWidth, pageHeight) ?: return null
        val page = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        Canvas(page).drawBitmap(
            source,
            null,
            RectF(fit.left, fit.top, fit.right, fit.bottom),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val corner = page.getPixel(0, 0)
        if (corner ushr 24 == 0xFF) page.setPixel(0, 0, (corner and 0x00FFFFFF) or (0xFE shl 24))
        return page
    }

    /** [page] as the stored reference's bytes: lossy WebP with alpha at [IMAGE_QUALITY]
     *  (`WEBP_LOSSY` from API 30; `WEBP` below 100 is lossy on 29). Off the main thread. */
    fun encode(page: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            page.compress(Bitmap.CompressFormat.WEBP_LOSSY, IMAGE_QUALITY, out)
        } else {
            @Suppress("DEPRECATION")
            page.compress(Bitmap.CompressFormat.WEBP, IMAGE_QUALITY, out)
        }
        return out.toByteArray()
    }

    /** The smallest `inSampleSize` power of two that keeps the decoded picture's long edge at or
     *  above the page's long edge — so the fit only ever scales down, and a 48 MP photo never
     *  arrives in memory at full size. Pure. */
    fun sampleSize(srcWidth: Int, srcHeight: Int, pageWidth: Int, pageHeight: Int): Int {
        val srcLong = maxOf(srcWidth, srcHeight)
        val pageLong = maxOf(pageWidth, pageHeight)
        if (srcLong <= 0 || pageLong <= 0) return 1
        var sample = 1
        while (srcLong / (sample * 2) >= pageLong) sample *= 2
        return sample
    }

    private const val INITIAL_BUFFER_BYTES = 256 * 1024
}
