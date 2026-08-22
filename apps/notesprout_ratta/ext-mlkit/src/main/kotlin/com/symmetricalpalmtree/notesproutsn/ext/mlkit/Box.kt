package com.symmetricalpalmtree.notesproutsn.ext.mlkit

/**
 * An immutable axis-aligned rectangle in page px — the segmenter's stand-in for
 * `android.graphics.RectF` so every piece of geometry in this module stays pure Kotlin and
 * JVM-testable. [union] returns a new box; nothing here mutates.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** The smallest box containing both. */
    fun union(other: Box): Box = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    /**
     * How much two boxes overlap vertically, as a fraction of the **shorter** box's height:
     * 0 when they are disjoint (touching counts as disjoint), 1 when one contains the other.
     * Symmetric. The shorter height is floored at 1 px so a zero-height box cannot divide by zero.
     */
    fun verticalOverlapFrac(other: Box): Float {
        val overlap = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (overlap <= 0f) return 0f
        val shorter = minOf(height, other.height).coerceAtLeast(1f)
        return overlap / shorter
    }

    companion object {
        /** Bounding box of a non-empty point set held as parallel x/y arrays. */
        fun of(x: FloatArray, y: FloatArray): Box {
            var l = x[0]
            var r = x[0]
            var t = y[0]
            var b = y[0]
            for (i in 1 until x.size) {
                val px = x[i]
                val py = y[i]
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > b) b = py
            }
            return Box(l, t, r, b)
        }
    }
}
