package com.symmetricalpalmtree.sketchcompanion.grid

/**
 * Line weight as a fraction of the frame's width, so the on-screen frame and the exported crop
 * carry the same proportion whatever their pixel size. REGULAR's divisor reproduces the sketch
 * page's 2 px line and 4 px dot on the Nomad's 1404 px width (1404 / 700 ≈ 2.006).
 */
enum class GridWeight(val divisor: Float) {
    THIN(1400f), REGULAR(700f), BOLD(350f);

    fun lineWidth(frameWidth: Int): Float = maxOf(1f, frameWidth / divisor)
    fun dotRadius(frameWidth: Int): Float = 2f * lineWidth(frameWidth)

    companion object {
        val DEFAULT = REGULAR
        fun fromOrdinal(i: Int): GridWeight = entries.getOrNull(i) ?: DEFAULT
    }
}
