package com.symmetricalpalmtree.notesproutsn.ext.bible

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The index panel's layout rules (arc 37 / B6 — pure Kotlin, JVM-tested): the notebook Contents'
 * `ContentsLayout` in the extension's own copy, because that object lives in the host `:app` and
 * an extension never depends on it. **The numbers are the Contents' numbers on purpose** — the
 * two panels should feel like one thing — so a change to one belongs in the other too.
 *
 * Full screen below [SIDEBAR_MIN_DP]; a 60 % left sidebar at or above (both real devices take
 * the sidebar: Nomad 749 dp / Manta 1024 dp at density 1.875). Rows — a book's, and a row of its
 * chapter grid alike — are one uniform height, so rows-per-page is a single division.
 */
object IndexLayout {

    /** Below this window width the panel fills the screen (the Contents' rule). */
    const val SIDEBAR_MIN_DP = 480

    /** The sidebar's share of the window width. */
    const val SIDEBAR_WIDTH_FRACTION = 0.60f

    /** Row height + separator (dp) — `item_index_book.xml`'s minHeight and its 1 dp line; a
     *  chapter row is built to the same total so the list stays uniform. */
    const val ROW_HEIGHT_DP = 68f
    const val ROW_SEPARATOR_DP = 1f

    fun fullScreen(windowWidthDp: Int): Boolean = windowWidthDp < SIDEBAR_MIN_DP

    fun sidebarWidthPx(windowWidthPx: Int): Int = (windowWidthPx * SIDEBAR_WIDTH_FRACTION).roundToInt()

    /** One row's full slot — height plus separator — in pixels. */
    fun rowPx(density: Float): Int = ((ROW_HEIGHT_DP + ROW_SEPARATOR_DP) * density).roundToInt()

    /** How many rows fit a body of [bodyHeightPx] at [density] — at least 1. */
    fun itemsPerPage(bodyHeightPx: Int, density: Float): Int {
        val rowPx = (ROW_HEIGHT_DP + ROW_SEPARATOR_DP) * density
        if (rowPx <= 0f) return 1
        return maxOf(1, floor(bodyHeightPx / rowPx).toInt())
    }
}
