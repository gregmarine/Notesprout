package com.symmetricalpalmtree.notesproutsn.markdown

/**
 * The heading type spec — one place, so a heading measured by the store, drawn on the page, and
 * previewed in a dialog can never disagree about its size.
 *
 * Headings render on **one line, END-ellipsized** (`MarkdownDraw.measure(singleLine = true)`): a
 * heading is a title, and a title that wrapped would push the ink below it out of place every time
 * the text grew. [PADDING_DP] is the breathing room around that line inside the object's bounds.
 *
 * Pure Kotlin — no android imports — so it is JVM-testable and callable from any thread.
 */
object HeadingTypography {

    /** Base size in sp, before the per-level scale. Headings are always bold. */
    const val BASE_SP = 24f

    /** Padding around the heading line, in dp. */
    const val PADDING_DP = 8f

    /**
     * Per-level size multiplier. h6 sits at 1.0 — the same size as body text, distinguished by its
     * weight alone, which is what the markdown convention expects.
     *
     * Levels outside 1..6 are clamped rather than rejected: [level] comes from a stored `flags`
     * column, and a file written by something else must still render.
     */
    fun scaleFor(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 2.0f
        2 -> 1.75f
        3 -> 1.5f
        4 -> 1.25f
        5 -> 1.1f
        else -> 1.0f
    }

    /** Text size in px for [level] at [scaledDensity] (`DisplayMetrics.scaledDensity`). */
    fun textSizePx(level: Int, scaledDensity: Float): Float =
        BASE_SP * scaleFor(level) * scaledDensity

    /** [PADDING_DP] in px at [density] (`DisplayMetrics.density`). */
    fun paddingPx(density: Float): Float = PADDING_DP * density
}
