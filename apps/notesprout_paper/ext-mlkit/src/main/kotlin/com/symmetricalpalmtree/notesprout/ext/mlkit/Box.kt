package com.symmetricalpalmtree.notesprout.ext.mlkit

/**
 * A tiny pure axis-aligned box — the segmenter's stand-in for `android.graphics.RectF` so the
 * geometry stays JVM-testable. Immutable; [union] returns a new box.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f

    fun union(o: Box): Box = Box(
        minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom),
    )

    /** Vertical overlap of two boxes as a fraction of the shorter box's height (0 = disjoint). */
    fun verticalOverlapFrac(o: Box): Float {
        val overlap = minOf(bottom, o.bottom) - maxOf(top, o.top)
        if (overlap <= 0f) return 0f
        val shorter = minOf(height, o.height).coerceAtLeast(1f)
        return overlap / shorter
    }

    companion object {
        /** Bounding box of a point set (x/y parallel arrays, non-empty). */
        fun of(x: FloatArray, y: FloatArray): Box {
            var l = x[0]; var r = x[0]; var t = y[0]; var b = y[0]
            for (i in 1 until x.size) {
                val px = x[i]; val py = y[i]
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > b) b = py
            }
            return Box(l, t, r, b)
        }
    }
}
