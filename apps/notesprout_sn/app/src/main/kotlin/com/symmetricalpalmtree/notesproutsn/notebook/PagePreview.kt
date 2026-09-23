package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer

/**
 * Renders one page-preview bitmap for the link picker (arc 6 / K2): white paper, the page's
 * content scaled uniformly from page px to the preview's width, in the paper's own layering
 * (content renderers sit
 * [below the strokes][com.symmetricalpalmtree.gpaper.core.render.ContentLayer.BELOW_STROKES]).
 *
 * Written content only, the locked scope: no template render, and no link chrome — a preview shows
 * what was *written*. Every kind goes through the same static draw recipe its on-page renderer
 * uses — strokes through g-paper's [StrokeRasterizer], headings through
 * [HeadingRenderer.drawHeading], text through [TextRenderer.drawText], shapes through
 * [ShapeRenderer.drawShape] and sticky icons through [StickyRenderer.drawSticky] — so a thumbnail
 * is the page in miniature, not an approximation. A **sticky's content never draws here**, exactly
 * as it never draws on the page (D2).
 *
 * Thread-safe off Main (StaticLayout + plain canvas — the [HeadingRenderer] contract), which is
 * where the picker calls it: previews render async per grid page, behind placeholder cards. That
 * is what [Paints] is for: a `Drawable` and a `Paint` both carry mutable state, so each caller
 * builds **one** set and keeps it to itself.
 *
 * The layering itself lives in [drawContent], which the export bake shares (arc 18 / D1) — the
 * preview adds the scale and the card's edge around it.
 */
object PagePreview {

    /**
     * The paints and the sticky icon one caller builds **once** and hands to every page it draws
     * (arc 28 / H1). A `Paint` is mutated per shape ([ShapeRenderer.drawShape] sets the outline
     * width) and a `Drawable` is mutated per icon ([StickyRenderer.drawSticky] sets its bounds), so
     * a set belongs to one thread: build it where you draw, never share one between the engine's
     * renderers and a background bake.
     *
     * [stickyIcon] is nullable and skipping is the whole failure mode: a caller that could not load
     * `R.drawable.ic_sticker_2` draws the page without its note icons rather than not at all.
     */
    class Paints(
        /** Heading text, at [HeadingRenderer]'s own sizes. */
        val heading: TextPaint,
        /** On-page Markdown text objects, at [TextRenderer.BASE_SP]. */
        val text: TextPaint,
        /** Shape outlines — stroke-only; its width is set per shape. */
        val shape: Paint,
        /** `R.drawable.ic_sticker_2`, already `mutate()`d by the caller. Null = no icons drawn. */
        val stickyIcon: Drawable?,
    ) {
        companion object {
            fun of(scaledDensity: Float, stickyIcon: Drawable?): Paints = Paints(
                heading = HeadingRenderer.basePaint(scaledDensity),
                text = TextRenderer.basePaint(scaledDensity),
                shape = ShapeRenderer.basePaint(),
                stickyIcon = stickyIcon,
            )
        }
    }

    /** Build the preview, or null when the sizes are unusable or the allocation failed (the card
     *  keeps its placeholder — a missing preview is never an error the user must see). */
    fun render(
        page: PickerPage,
        content: PageContent,
        outWidth: Int,
        outHeight: Int,
        density: Float,
        paints: Paints,
    ): Bitmap? {
        if (outWidth < 1 || outHeight < 1 || page.width <= 0) return null
        // ARGB_8888 since arc 49 / P4 (RGB_565 before): the pen writes in sixteen greys and a
        // 5/6-bit card would show them a shade off the page's own. The preview is still erased to
        // white with every draw on top — no alpha in it — so this is purely the tone's depth; a
        // Manta page card (~628 x 837) is ~2.1 MB rather than ~1.05, and the picker renders a
        // whole grid page of them at once.
        val bmp = try {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "preview ${outWidth}x$outHeight allocation failed for ${page.id}")
            return null
        }
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val scale = outWidth / page.width.toFloat()
        canvas.save()
        canvas.scale(scale, scale)
        drawContent(canvas, content, density, paints)
        canvas.restore()
        // The page's own edge, drawn ON the bitmap (eye-check #7): the miniature is fit-centred
        // into a band it rarely fills exactly, so a border on the ImageView gets overpainted by
        // the white paper wherever the two disagree — the outline must travel with the pixels.
        // Unscaled, inset half a stroke so all four 1 px edges land inside the bitmap.
        canvas.drawRect(0.5f, 0.5f, outWidth - 0.5f, outHeight - 0.5f, border)
        return bmp
    }

    /**
     * **The page's content in the paper's own layering** (D8) — the registration order the notebook
     * screen uses, spelled out once:
     *
     *  1. loose headings, loose texts, loose shapes;
     *  2. each link in z-order: its wrapped headings, texts, shapes, strokes, then its wrapped
     *     **sticky icons**;
     *  3. loose sticky icons — the top of the object stack, so a note dropped over anything stays
     *     visible;
     *  4. loose ink last, because content renderers sit
     *     [below the strokes][com.symmetricalpalmtree.gpaper.core.render.ContentLayer.BELOW_STROKES].
     *
     * A sticky's **content** appears nowhere in that list, here or anywhere else on a page. Draws
     * into [canvas] wherever it stands: the preview scales it into a card, and the export bake
     * ([com.symmetricalpalmtree.notesproutsn.export.ExportRender]) takes it at scale 1 over a whole
     * page.
     *
     * It is one function because the order is one decision. A second copy of these lines that
     * drifted would mean a page that exports differently from the way it previews — the
     * sibling-copy trap, in miniature. Chrome is deliberately not here: neither caller draws it.
     */
    fun drawContent(canvas: Canvas, content: PageContent, density: Float, paints: Paints) {
        for (h in content.headings) HeadingRenderer.drawHeading(canvas, h, density, paints.heading)
        for (t in content.texts) TextRenderer.drawText(canvas, t, density, paints.text)
        for (s in content.shapes) ShapeRenderer.drawShape(canvas, s, paints.shape)
        for (l in content.links) {
            for (h in l.headings) HeadingRenderer.drawHeading(canvas, h, density, paints.heading)
            for (t in l.texts) TextRenderer.drawText(canvas, t, density, paints.text)
            for (s in l.shapes) ShapeRenderer.drawShape(canvas, s, paints.shape)
            StrokeRasterizer.draw(canvas, l.strokes)
            val icon = paints.stickyIcon
            if (icon != null) for (s in l.stickies) StickyRenderer.drawSticky(canvas, s, icon)
        }
        val icon = paints.stickyIcon
        if (icon != null) for (s in content.stickies) StickyRenderer.drawSticky(canvas, s, icon)
        StrokeRasterizer.draw(canvas, content.strokes)
    }

    private val border = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private const val TAG = "PagePreview"
}
