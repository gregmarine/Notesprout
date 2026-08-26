package com.symmetricalpalmtree.notesproutsn.data.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Paints a template onto a bitmap. **Thin on purpose** — every position, size and grey comes from
 * [TemplateGeometry] as a [TemplatePlan]; this file only knows how to hold a brush.
 *
 * The result is baked into the notebook's `template` row at creation time (lossless WEBP), so
 * changing a constant here affects new notebooks only — existing files keep the background they
 * were born with. That is the point: a page must not silently re-rule itself under old ink.
 *
 * Arc 13 / G2: the entry point is a [TemplateSpec], and the four kinds are the stock specs. Nothing
 * about a stock render changed — [TemplateGeometry] resolves it to the same positions it always
 * did, and `TemplateGeometryTest` pins that.
 */
object BuiltInTemplates {

    /**
     * The page-sized render that gets baked into the file: real millimetres at the panel's [dpi].
     * Null for [TemplateKind.BLANK] — a blank notebook has no template row and its page's `refId`
     * is `""`.
     */
    fun render(kind: TemplateKind, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? =
        if (kind == TemplateKind.BLANK) null else render(TemplateSpec.stock(kind), widthPx, heightPx, dpi)

    /** The page-sized render of an adjusted generator (arc 13 / G2). */
    fun render(spec: TemplateSpec, widthPx: Int, heightPx: Int, dpi: Float): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        return paint(TemplateGeometry.plan(spec, widthPx, heightPx, dpi), widthPx, heightPx)
    }

    /**
     * A card-sized thumbnail of the same pattern for a notebook that has no cover snapshot yet.
     * Real 8 mm spacing inside a 3 cm card would draw two rules and read as nothing, so the
     * placeholder squeezes the pattern to a fixed number of rows — it is a *hint at* the template,
     * not a scale model of it.
     */
    fun placeholder(kind: TemplateKind, widthPx: Int, heightPx: Int, density: Float): Bitmap? {
        if (kind == TemplateKind.BLANK || widthPx <= 0 || heightPx <= 0) return null
        return paint(TemplateGeometry.placeholderPlan(kind, widthPx, heightPx, density), widthPx, heightPx)
    }

    /**
     * A **true miniature** of the same paper (arc 13): the page pattern scaled honestly to a card,
     * not the squeezed hint [placeholder] draws. `scale` is the card's width over the page's, so
     * 8 mm ruling on a quarter-width card is 2 mm of card — which is exactly what makes a dense
     * grid look dense and tells two variants apart at a glance.
     *
     * Scaling is a smaller **effective dpi**, not a factor applied to five numbers: a count stays a
     * count, a millimetre shrinks, and the insets shrink with it. Feature sizes floor at 1 px
     * inside the plan — below that a rule stops being drawn at all, and a card showing nothing
     * would read as Blank.
     */
    fun miniature(kind: TemplateKind, widthPx: Int, heightPx: Int, scale: Float, dpi: Float): Bitmap? =
        if (kind == TemplateKind.BLANK) null
        else miniature(TemplateSpec.stock(kind), widthPx, heightPx, scale, dpi)

    /** The miniature of an adjusted generator — see the [miniature] above for what `scale` means. */
    fun miniature(spec: TemplateSpec, widthPx: Int, heightPx: Int, scale: Float, dpi: Float): Bitmap? {
        if (scale <= 0f) return null
        return render(spec, widthPx, heightPx, dpi * scale)
    }

    /** One plan, one bitmap. The only place in the app that knows how a template is drawn. */
    private fun paint(plan: TemplatePlan, widthPx: Int, heightPx: Int): Bitmap? {
        if (plan.kind == TemplateKind.BLANK || widthPx <= 0 || heightPx <= 0) return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val ink = Color.rgb(plan.grey, plan.grey, plan.grey)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }

        when (plan.kind) {
            TemplateKind.LINED -> {
                paint.strokeWidth = plan.lineWidthPx
                for (y in plan.ys) canvas.drawLine(plan.left, y, plan.right, y, paint)
            }
            TemplateKind.DOTTED -> {
                paint.style = Paint.Style.FILL
                for (y in plan.ys) for (x in plan.xs) canvas.drawCircle(x, y, plan.dotRadiusPx, paint)
            }
            TemplateKind.GRID -> {
                paint.strokeWidth = plan.lineWidthPx
                for (y in plan.ys) canvas.drawLine(plan.left, y, plan.right, y, paint)
                for (x in plan.xs) canvas.drawLine(x, plan.top, x, plan.bottom, paint)
            }
            TemplateKind.BLANK -> {}
        }

        // The margin rule is not part of the pattern — it marks where the pattern stops — so it is
        // drawn last, in the same ink, and it spans the content rather than the whole page.
        plan.marginRuleX?.let { x ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = plan.lineWidthPx
            canvas.drawLine(x, plan.top, x, plan.bottom, paint)
        }
        return bitmap
    }

    /**
     * Lossless WEBP at quality 100 — a template is line art; a lossy codec would fuzz every rule.
     * `WEBP_LOSSLESS` is API 30; on 29 the legacy `WEBP` at quality 100 is the closest available.
     * (minSdk is 29 for the family; every Supernote actually runs 30+.)
     */
    fun toWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, 100, out)
        return out.toByteArray()
    }
}
