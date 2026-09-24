package com.symmetricalpalmtree.notesproutsn.core

/**
 * Where a picture lands on a page when it is **fit** — the whole picture, centred, aspect kept,
 * scaled to the page's width or its height, whichever the picture reaches first (arc 51 "Guides":
 * the sketch face's reference image, the user's word — "centered and take up the full width or
 * height proportionally… not stretched either way"). The host's `TemplateFit.FIT` arithmetic,
 * written once more here because `:app` is off-limits to an extension and a shared library must
 * not depend on the host; the two are pinned to the same answers by test.
 *
 * **Pure arithmetic, no `android.graphics`**: one destination rect in whole-pixel floats; a blit
 * of the whole source into it is the render. Null when either size is degenerate — nothing to draw.
 */
object ImageFit {

    /** `(left, top, right, bottom)` in page pixels. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun plan(srcWidth: Int, srcHeight: Int, pageWidth: Int, pageHeight: Int): Rect? {
        if (srcWidth <= 0 || srcHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) return null
        val sw = srcWidth.toFloat()
        val sh = srcHeight.toFloat()
        val pw = pageWidth.toFloat()
        val ph = pageHeight.toFloat()
        val scale = minOf(pw / sw, ph / sh)
        val w = sw * scale
        val h = sh * scale
        val left = (pw - w) / 2f
        val top = (ph - h) / 2f
        return Rect(left, top, left + w, top + h)
    }
}
