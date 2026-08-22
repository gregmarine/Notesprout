package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke

/**
 * The one place a page's ink is reduced to the bare geometry a recognizer extension receives: per
 * stroke, its x/y arrays in page px — and nothing else. Id, colour, width, style, pressure, tilt and
 * timestamps all stop here. A stroke with no points is skipped, because an [InkStroke] cannot be
 * empty. Pure Kotlin, JVM-tested.
 *
 * **The order of the list is load-bearing.** ML Kit reads ink as a sequence of strokes in writing
 * order, so the caller must hand this the strokes in commit order — never the iteration order of a
 * set or of anything hashed.
 */
object InkPayload {
    fun fromStrokes(strokes: Collection<Stroke>): List<InkStroke> {
        val out = ArrayList<InkStroke>(strokes.size)
        for (s in strokes) {
            val n = s.points.size
            if (n == 0) continue
            val x = FloatArray(n)
            val y = FloatArray(n)
            for (i in 0 until n) {
                val p = s.points[i]
                x[i] = p.x
                y[i] = p.y
            }
            out += InkStroke(x, y)
        }
        return out
    }
}

/**
 * What the notebook screen exposes for a recognize call: the visible page's strokes **in writing
 * order** and the page's px size — the same values that were handed to `setPageSize`. Nothing else
 * leaves the screen: no ids, no notebook name, no session.
 */
class RecognizeContext(
    val strokes: List<Stroke>,
    val pageWidth: Float,
    val pageHeight: Float,
)
