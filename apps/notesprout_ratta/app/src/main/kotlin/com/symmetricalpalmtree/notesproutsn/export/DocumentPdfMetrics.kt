package com.symmetricalpalmtree.notesproutsn.export

/**
 * The page geometry and type size a Document PDF is laid out at (arc 19 / M9) — pure, so the one
 * thing that decides whether the exported page *looks like* what the writer saw is provable off a
 * device.
 *
 * **These constants are the editor Preview's, mirrored.** The Preview surface is a plain `TextView`
 * in `:ext-document`'s `activity_document_editor.xml` — 16dp of padding left, right and top, 32dp
 * at the foot, `lineSpacingMultiplier="1.15"` — rendered through `PreviewRender` with an 8dp block
 * gap at the editor's saved text size plus `EditorPrefs.PREVIEW_BUMP`. The export cannot ask the
 * extension for any of that (nothing but document text crosses that seam), so it restates it here
 * and the two are kept honest by eye at the arc's walk. The **pinned** source of the store key
 * layout is `:ext-document`'s `EditorPrefs` and its `EditorPrefsLayoutTest`: [TEXT_SIZE_KEY],
 * [DEFAULT_TEXT_SIZE_SP], the 14..25 range and [PREVIEW_BUMP_SP] all name values that file owns —
 * change one there and this is the other half that has to move.
 *
 * The bottom margin is deliberately not the top one. It is the Preview's scroll tail: room to read
 * the last line clear of the chrome. On a printed page it reads as the wider foot margin a
 * document usually has, which is why it survived the port rather than being tidied away.
 */
object DocumentPdfMetrics {

    /** Left, right and top margin (dp) — the Preview's `paddingStart` / `End` / `Top`. */
    const val MARGIN_DP: Float = 16f

    /** Foot margin (dp) — the Preview's `paddingBottom`, see the class doc. */
    const val MARGIN_BOTTOM_DP: Float = 32f

    /** The gap the renderer puts between two markdown blocks (dp) — `PreviewRender`'s. */
    const val BLOCK_GAP_DP: Float = 8f

    /** `lineSpacingMultiplier` on the Preview `TextView`. */
    const val LINE_SPACING_MULTIPLIER: Float = 1.15f

    /** The extension store key the editor's text size lives under — `EditorPrefs.KEY_TEXT_SIZE`. */
    const val TEXT_SIZE_KEY: String = "size"

    /** What the editor opens at before anything has been chosen — `EditorPrefs.DEFAULT_TEXT_SIZE`. */
    const val DEFAULT_TEXT_SIZE_SP: Float = 16f

    /** The smallest and largest offered sizes — `EditorPrefs.SIZES`' ends. A value from a future
     *  build with a wider range must not lay the export out at a size this one cannot draw. */
    const val MIN_TEXT_SIZE_SP: Float = 14f
    const val MAX_TEXT_SIZE_SP: Float = 25f

    /** Preview reads a little larger than the source — `EditorPrefs.PREVIEW_BUMP`. */
    const val PREVIEW_BUMP_SP: Float = 2f

    /** dp → px, the way every other measure in this family rounds (truncate, not round-half). */
    fun px(dp: Float, density: Float): Int = (dp * density).toInt()

    /**
     * The stored size as the layout should use it. [raw] is the store's value decoded as UTF-8, or
     * null when there is no editor, no store, or no key — **every** failure lands on
     * [DEFAULT_TEXT_SIZE_SP], because a text size is comfort: an export must never refuse over one.
     * A parseable value outside the offered range is coerced rather than discarded.
     */
    fun textSizeSp(raw: String?): Float {
        val parsed = raw?.trim()?.toFloatOrNull() ?: return DEFAULT_TEXT_SIZE_SP
        if (parsed.isNaN()) return DEFAULT_TEXT_SIZE_SP
        return parsed.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
    }

    /** The paint size the layout is built at: the editor's size, the Preview's bump, then sp → px. */
    fun textSizePx(sizeSp: Float, scaledDensity: Float): Float =
        (sizeSp + PREVIEW_BUMP_SP) * scaledDensity

    /** The content box on one page: where the text starts and how much of the page it may fill. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The content box for a page of [pageWidthPx] × [pageHeightPx] at [density], or **null** when
     * the margins leave nothing to write on — a page too small for its own margins is a data
     * problem (a damaged or foreign-written row), and the caller refuses it as one rather than
     * asking a layout engine for a zero-width paragraph.
     */
    fun box(pageWidthPx: Int, pageHeightPx: Int, density: Float): Box? {
        if (pageWidthPx < 1 || pageHeightPx < 1) return null
        val margin = px(MARGIN_DP, density)
        val bottom = px(MARGIN_BOTTOM_DP, density)
        val width = pageWidthPx - margin - margin
        val height = pageHeightPx - margin - bottom
        if (width < 1 || height < 1) return null
        return Box(left = margin, top = margin, width = width, height = height)
    }
}
