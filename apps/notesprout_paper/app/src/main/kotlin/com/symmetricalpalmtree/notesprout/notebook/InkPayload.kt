package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.extension.InkStroke

/**
 * The one place page ink is reduced to the bare geometry a `HANDWRITING_RECOGNIZER` receives
 * (audit row 14): per stroke, its x/y point arrays in page px — and nothing else. Id, colour, width,
 * style, pressure, tilt and time are dropped here. Strokes with no points are skipped (an
 * `InkStroke` cannot be empty). Pure Kotlin, JVM-tested.
 */
object InkPayload {
    fun fromStrokes(strokes: List<Stroke>): List<InkStroke> {
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
 * What the notebook screen exposes to its debug menu for a recognize call: the current page's
 * strokes (from the paper — g-paper's `getStrokes()` is any-thread) and the page's px size (from
 * the session's current page — the same values handed to `setPageSize`). Nothing else leaves the
 * screen: no ids, no names, no session.
 */
class RecognizeContext(
    val strokes: List<Stroke>,
    val pageWidth: Float,
    val pageHeight: Float,
)
