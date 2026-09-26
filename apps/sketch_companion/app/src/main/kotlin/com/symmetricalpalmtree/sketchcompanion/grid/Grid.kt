package com.symmetricalpalmtree.sketchcompanion.grid

/**
 * The grid's vocabulary — mirrored from NSE · Sketch's guide grid (arc 51) so a phone grid and a
 * Supernote page grid at the same count are the same grid: square cells, one count across the
 * width, centred.
 */
object Grid {
    const val OFF = 0
    const val LINES = 1
    const val DOTS = 2

    /** The counts the sketch face offers, in two rows of five. */
    val COUNTS: List<Int> = listOf(2, 4, 6, 8, 12, 16, 20, 24, 28, 32)
    const val COUNT_ROW_BREAK = 5
    const val DEFAULT_COUNT = 4
    const val DEFAULT_KIND = LINES

    fun countRows(): List<List<Int>> = listOf(COUNTS.take(COUNT_ROW_BREAK), COUNTS.drop(COUNT_ROW_BREAK))

    /** The eight swatches, ARGB. White first: every camera's own composition grid is white, and it
     *  survives most of a scene — a blown sky is one tap away from black. */
    val SWATCHES: List<Int> = listOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF888888.toInt(), 0xFFFF0000.toInt(),
        0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(),
    )
    val DEFAULT_COLOR: Int = SWATCHES[0]

    fun isKind(kind: Int): Boolean = kind == OFF || kind == LINES || kind == DOTS
}
