package com.symmetricalpalmtree.notesprout.notebook

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The Contents screen's layout rules (arc 5 / C1 — pure Kotlin, JVM-tested): the width branch the
 * one `dialog_contents.xml` takes (Q3 — full screen below [SIDEBAR_MIN_DP], a 60 % left sidebar at or
 * above), and the rows-per-page math the dialog applies to its measured body height (Q7 rows).
 */
object ContentsLayout {

    /** Below this window width the Contents fills the screen (the original's rule). */
    const val SIDEBAR_MIN_DP = 480

    /** The sidebar's share of the window width. */
    const val SIDEBAR_WIDTH_FRACTION = 0.60f

    /** Row height + separator (dp) — `item_contents_entry.xml`'s minHeight and its 1 dp line. */
    const val ROW_HEIGHT_DP = 68f
    const val ROW_SEPARATOR_DP = 1f

    /** Indent per level above 1 (dp) — the whole row shifts. */
    const val LEVEL_INDENT_DP = 16f

    fun fullScreen(windowWidthDp: Int): Boolean = windowWidthDp < SIDEBAR_MIN_DP

    fun sidebarWidthPx(windowWidthPx: Int): Int = (windowWidthPx * SIDEBAR_WIDTH_FRACTION).roundToInt()

    /** How many rows fit a body of [bodyHeightPx] at [density] — at least 1. */
    fun itemsPerPage(bodyHeightPx: Int, density: Float): Int {
        val rowPx = (ROW_HEIGHT_DP + ROW_SEPARATOR_DP) * density
        if (rowPx <= 0f) return 1
        return maxOf(1, floor(bodyHeightPx / rowPx).toInt())
    }

    fun indentPx(level: Int, density: Float): Int = ((level - 1).coerceAtLeast(0) * LEVEL_INDENT_DP * density).roundToInt()
}
