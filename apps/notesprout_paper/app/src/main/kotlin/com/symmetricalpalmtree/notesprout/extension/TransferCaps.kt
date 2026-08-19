package com.symmetricalpalmtree.notesprout.extension

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import java.util.UUID

/**
 * The scratch-pad ink-transfer caps and the two stroke mappings (arc 6 / S0 — pure, JVM-tested).
 *
 * Outward (notebook → pad): [withinLimits] is checked **before any bind** (a selection / page above
 * [ExtensionContract.MAX_TRANSFER_STROKES] / [ExtensionContract.MAX_TRANSFER_POINTS] is refused with
 * an honest dialog); [toPaperStrokes] is the one reduction site from the paper's [Stroke] (id and time
 * never leave; point-less strokes are skipped); [chunk] splits greedily at
 * [ExtensionContract.TRANSFER_CHUNK_STROKES] / [ExtensionContract.TRANSFER_CHUNK_POINTS] per Binder
 * call — a single stroke over the point chunk cap is its own chunk (never split).
 *
 * Inward (pad → notebook): every bundle is already through `InkBundle.requireValid` at unmarshal;
 * [sanitize] then forces what v0 can draw — unknown style → PEN, `width` clamped to
 * [MIN_WIDTH]..[MAX_WIDTH] px, colour forced opaque black — and [toStrokes] mints **fresh ids** on
 * this side (`timeMillis 0`). Nothing else is trusted; no id ever crosses.
 */
object TransferCaps {

    const val MIN_WIDTH = 0.5f
    const val MAX_WIDTH = 50f

    fun withinLimits(strokeCount: Int, pointCount: Int): Boolean =
        strokeCount <= ExtensionContract.MAX_TRANSFER_STROKES && pointCount <= ExtensionContract.MAX_TRANSFER_POINTS

    fun pointCount(strokes: List<Stroke>): Int = strokes.sumOf { it.points.size }

    /** Greedy chunking at the per-call caps; every chunk satisfies `InkBundle.requireValid`. */
    fun chunk(strokes: List<PaperStroke>): List<List<PaperStroke>> {
        val out = ArrayList<List<PaperStroke>>()
        var cur = ArrayList<PaperStroke>()
        var pts = 0
        for (s in strokes) {
            val fits = cur.size < ExtensionContract.TRANSFER_CHUNK_STROKES &&
                pts + s.size <= ExtensionContract.TRANSFER_CHUNK_POINTS
            if (!fits && cur.isNotEmpty()) {
                out += cur; cur = ArrayList(); pts = 0
            }
            cur += s; pts += s.size
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** The paper's strokes as wire strokes: geometry + width + colour + style name; id and time dropped; empty strokes skipped. */
    fun toPaperStrokes(strokes: List<Stroke>): List<PaperStroke> {
        val out = ArrayList<PaperStroke>(strokes.size)
        for (s in strokes) {
            val n = s.points.size
            if (n == 0) continue
            val x = FloatArray(n); val y = FloatArray(n); val p = FloatArray(n); val t = FloatArray(n)
            for (i in 0 until n) {
                val pt = s.points[i]
                x[i] = pt.x; y[i] = pt.y; p[i] = pt.pressure; t[i] = pt.tilt
            }
            out += PaperStroke(x, y, p, t, s.width, s.color, s.style.name)
        }
        return out
    }

    /** Force what v0 draws: known style or PEN, width in [MIN_WIDTH]..[MAX_WIDTH], opaque black. */
    fun sanitize(bundle: InkBundle): InkBundle =
        InkBundle(bundle.strokes.map { sanitize(it) }, bundle.pageWidth, bundle.pageHeight)

    fun sanitize(s: PaperStroke): PaperStroke {
        val style = if (StrokeStyle.entries.any { it.name == s.style }) s.style else StrokeStyle.PEN.name
        val width = if (s.width.isNaN()) Stroke.DEFAULT_WIDTH else s.width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val same = style == s.style && width == s.width && s.colorArgb == Stroke.BLACK
        return if (same) s else PaperStroke(s.x, s.y, s.pressure, s.tilt, width, Stroke.BLACK, style)
    }

    /** Wire strokes → paper strokes with **fresh ids** (minted here, never taken from the wire) and `timeMillis 0`. */
    fun toStrokes(strokes: List<PaperStroke>, newId: () -> String = { UUID.randomUUID().toString() }): List<Stroke> {
        val out = ArrayList<Stroke>(strokes.size)
        for (s in strokes) {
            val n = s.size
            val points = ArrayList<StrokePoint>(n)
            for (i in 0 until n) points += StrokePoint(s.x[i], s.y[i], s.pressure[i], s.tilt[i], 0L)
            out += Stroke(id = newId(), points = points, color = s.colorArgb, width = s.width, style = styleOf(s.style))
        }
        return out
    }

    private fun styleOf(name: String): StrokeStyle =
        StrokeStyle.entries.firstOrNull { it.name == name } ?: StrokeStyle.PEN
}
