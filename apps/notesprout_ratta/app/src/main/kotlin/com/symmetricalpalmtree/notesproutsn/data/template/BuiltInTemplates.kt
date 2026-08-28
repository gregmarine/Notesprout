package com.symmetricalpalmtree.notesproutsn.data.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Paints a [TemplateKind] onto a bitmap. **Thin on purpose** — every position comes from
 * [TemplateGeometry]; this file only knows how to hold a brush.
 *
 * The result is baked into the notebook's `template` row at creation time (lossless WEBP), so
 * changing a constant here affects new notebooks only — existing files keep the background they
 * were born with. That is the point: a page must not silently re-rule itself under old ink.
 */
object BuiltInTemplates {

    /**
     * The page-sized render that gets baked into the file: real 8 mm spacing at the panel's [dpi].
     * Null for [TemplateKind.BLANK] — a blank notebook has no template row and its page's `refId`
     * is `""`.
     */
    fun render(kind: TemplateKind, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? = renderWith(
        kind, widthPx, heightPx,
        spacingPx = TemplateGeometry.spacingPx(dpi),
        lineWidthPx = TemplateGeometry.lineWidthPx(dpi),
        dotRadiusPx = TemplateGeometry.dotRadiusPx(dpi),
    )

    /**
     * A card-sized thumbnail of the same pattern for a notebook that has no cover snapshot yet.
     * Real 8 mm spacing inside a 3 cm card would draw two rules and read as nothing, so the
     * placeholder squeezes the pattern to a fixed number of rows — it is a *hint at* the template,
     * not a scale model of it.
     */
    fun placeholder(kind: TemplateKind, widthPx: Int, heightPx: Int, density: Float): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val feature = maxOf(1f, density)
        return renderWith(
            kind, widthPx, heightPx,
            spacingPx = (heightPx / PLACEHOLDER_ROWS).toFloat().coerceAtLeast(2f),
            lineWidthPx = feature,
            dotRadiusPx = feature,
            config = Bitmap.Config.RGB_565,
        )
    }

    /**
     * A **true miniature** of the same paper (arc 13): the page pattern scaled honestly to a card,
     * not the squeezed hint [placeholder] draws. `scale` is the card's width over the page's, so
     * 8 mm ruling on a quarter-width card is 2 mm of card — which is exactly what makes a dense
     * grid look dense and tells two variants apart at a glance.
     *
     * Feature sizes floor at 1 px: below that a rule stops being drawn at all, and a card showing
     * nothing would read as Blank.
     */
    fun miniature(kind: TemplateKind, widthPx: Int, heightPx: Int, scale: Float, dpi: Float): Bitmap? {
        if (scale <= 0f) return null
        return renderWith(
            kind, widthPx, heightPx,
            spacingPx = TemplateGeometry.spacingPx(dpi) * scale,
            lineWidthPx = maxOf(1f, TemplateGeometry.lineWidthPx(dpi) * scale),
            dotRadiusPx = maxOf(1f, TemplateGeometry.dotRadiusPx(dpi) * scale),
            config = Bitmap.Config.RGB_565,
        )
    }

    /**
     * [config] is **ARGB_8888 for anything that gets stored, RGB_565 for anything that is only
     * ever shown.** The page-sized [render] is encoded to lossless WEBP and becomes the page's
     * actual paper, so it keeps full depth; the two card renders ([miniature], [placeholder]) are
     * throwaway pixels bound for an `ImageView`, and at 2 bytes a pixel instead of 4 they halve
     * what the thumbnail cache holds. Safe for both because every render here is **opaque** —
     * erased to white before anything is drawn, so there is no alpha channel to lose.
     */
    private fun renderWith(
        kind: TemplateKind,
        widthPx: Int,
        heightPx: Int,
        spacingPx: Float,
        lineWidthPx: Float,
        dotRadiusPx: Float,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        if (kind == TemplateKind.BLANK || widthPx <= 0 || heightPx <= 0) return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, config)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        when (kind) {
            TemplateKind.LINED -> {
                paint.strokeWidth = lineWidthPx
                for (y in TemplateGeometry.linePositions(heightPx, spacingPx)) {
                    canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                }
            }
            TemplateKind.DOTTED -> {
                paint.style = Paint.Style.FILL
                for ((x, y) in TemplateGeometry.dotPositions(widthPx, heightPx, spacingPx)) {
                    canvas.drawCircle(x, y, dotRadiusPx, paint)
                }
            }
            TemplateKind.GRID -> {
                paint.strokeWidth = lineWidthPx
                for (y in TemplateGeometry.gridPositionsY(heightPx, spacingPx)) {
                    canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
                }
                for (x in TemplateGeometry.gridPositionsX(widthPx, spacingPx)) {
                    canvas.drawLine(x, 0f, x, heightPx.toFloat(), paint)
                }
            }
            TemplateKind.BLANK -> {}
        }
        return bitmap
    }

    /** How many pattern rows a card-sized placeholder shows, whatever the card's real size is. */
    private const val PLACEHOLDER_ROWS = 12

    /**
     * **Lossy WEBP at quality 100** — the same encoder og Notesprout's `core/ImageCodec` uses for
     * every blob it stores, and the same one `CoverSnapshot.encode` already used here.
     *
     * This was `WEBP_LOSSLESS` until 2026-08-27, on the untested assumption that a template is line
     * art and a lossy codec would fuzz every rule. **`DebugMenu`'s "WEBP encoder measurement"
     * ([WebpProbe]) refuted that on both Supernotes**, and the numbers were not close:
     *
     * ```
     *                          lossless        q100        (Manta 1920x2560)
     *   page lined              258K/663ms       9K/1294ms   26x smaller
     *   page dotted             521K/1038ms     58K/1470ms    9x smaller
     *   page grid                 3K/463ms      28K/1415ms    9x LARGER  <- the one exception
     *   photo-like import      3669K/102890ms 2903K/3695ms   28x FASTER
     * ```
     *
     * Two of those lines are the whole argument. Skia's lossless encoder bloats opaque line art by
     * ~10x PNG — exactly the effect og measured and rejected, which we had discounted because og
     * measured it on *alpha* content. And on an imported picture it took **103 seconds** on the
     * Manta to produce a *larger* file than q100 managed in under four: [TemplateTransfer] encodes
     * before it checks [TemplateImport.MAX_BLOB_BYTES], so lossless both stalled every import and
     * inflated a band of good pictures past the cap into a refusal.
     *
     * **Grid is a real, reproducible exception and is knowingly accepted.** It is the only case
     * lossless wins, identically on both devices, and why remains unexplained — but across the
     * three built-ins together lossless is 782K against q100's 95K, so the trade is not close.
     *
     * **No migration, and none is needed.** Every read path decodes through `BitmapFactory`, which
     * detects the format from the byte header, so lossless blobs already written keep decoding
     * forever alongside new lossy ones. This is the same reason og could change format without one.
     *
     * The API-29 branch is not a fallback of a different kind: the legacy
     * `Bitmap.CompressFormat.WEBP` constant *is* this encoder — q100 lossy, exactly what
     * `WEBP_LOSSY` names on 30+ — so both branches now agree. (minSdk 29 is a family-wide floor;
     * every Supernote runs 30+.)
     *
     * Do not raise q90 here without re-running the probe: it beat q100 on every lossy row, but this
     * is the one encoder in the app whose output is **stored paper**, and q100 is the floor og
     * settled on for the same reason.
     */
    fun toWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, 100, out)
        return out.toByteArray()
    }
}
