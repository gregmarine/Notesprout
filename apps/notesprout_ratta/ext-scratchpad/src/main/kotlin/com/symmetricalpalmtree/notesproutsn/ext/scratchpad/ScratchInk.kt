package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import java.util.UUID

/**
 * The extension's two wire ⇄ paper mappings (arc 11 / J3 — pure, JVM-tested). The host has its own
 * in `TransferCaps`, and `:sn-screen` cannot hold a shared one because it never sees
 * `:extension-api` — the twin is the seam, not a duplication accident.
 *
 * Inward ([toStrokes]): **fresh ids minted here**, `timeMillis 0`, an unknown style name → PEN, the
 * width clamped to [MIN_WIDTH]..[MAX_WIDTH] — nothing from the wire is trusted beyond its geometry.
 * Outward ([toWireStrokes]): geometry + width + colour + style name; the id and time never leave; a
 * point-less stroke is skipped.
 */
object ScratchInk {

    const val MIN_WIDTH = 0.5f
    const val MAX_WIDTH = 50f

    fun toStrokes(strokes: List<WireStroke>, newId: () -> String = { UUID.randomUUID().toString() }): List<Stroke> {
        val out = ArrayList<Stroke>(strokes.size)
        for (s in strokes) {
            val n = s.size
            val points = ArrayList<StrokePoint>(n)
            for (i in 0 until n) points += StrokePoint(s.x[i], s.y[i], s.pressure[i], s.tilt[i], 0L)
            val width = s.width.coerceIn(MIN_WIDTH, MAX_WIDTH)   // `> 0` already — WireStroke.requireValid
            out += Stroke(id = newId(), points = points, color = s.colorArgb, width = width, style = styleOf(s.style))
        }
        return out
    }

    fun toWireStrokes(strokes: List<Stroke>): List<WireStroke> {
        val out = ArrayList<WireStroke>(strokes.size)
        for (s in strokes) {
            val n = s.points.size
            if (n == 0) continue
            val x = FloatArray(n); val y = FloatArray(n); val p = FloatArray(n); val t = FloatArray(n)
            for (i in 0 until n) {
                val pt = s.points[i]
                x[i] = pt.x; y[i] = pt.y; p[i] = pt.pressure; t[i] = pt.tilt
            }
            out += WireStroke(x, y, p, t, s.width, s.color, s.style.name)
        }
        return out
    }

    private fun styleOf(name: String): StrokeStyle = StrokeStyle.entries.firstOrNull { it.name == name } ?: StrokeStyle.PEN
}
