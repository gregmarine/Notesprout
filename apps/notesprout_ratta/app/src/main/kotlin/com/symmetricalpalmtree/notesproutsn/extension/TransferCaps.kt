package com.symmetricalpalmtree.notesproutsn.extension

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import java.util.UUID

/**
 * The scratch-pad ink-transfer caps and the two stroke mappings (arc 11 / J3 — pure, JVM-tested).
 *
 * Outward (notebook → pad): [withinLimits] is checked **before any bind** (a selection / page above
 * [ExtensionContract.MAX_TRANSFER_STROKES] / [ExtensionContract.MAX_TRANSFER_POINTS] is refused with
 * an honest dialog); [toWireStrokes] is the one reduction site from the paper's [Stroke] (id and
 * time never leave; point-less strokes are skipped); [chunk] splits per Binder call ([InkChunks]).
 *
 * Inward (pad → notebook) is untrusted: every bundle is already through `InkBundle.requireValid` at
 * unmarshal; [Drain] accumulates the `takeOutgoing` chunks under the summed caps; [sanitize] forces
 * what SN can draw — unknown style → PEN, `width` clamped to [MIN_WIDTH]..[MAX_WIDTH] px, colour
 * forced opaque black (SN's ink is fixed black) — and [toStrokes] mints **fresh ids** on this side
 * (`timeMillis 0`). No id ever crosses.
 *
 * This is deliberately a **twin** of the extension's own `ScratchInk`, not a shared class:
 * `:sn-screen` never sees `:extension-api`, which is what keeps that seam honest.
 */
object TransferCaps {

    const val MIN_WIDTH = 0.5f
    const val MAX_WIDTH = 50f

    fun withinLimits(strokeCount: Int, pointCount: Int): Boolean =
        strokeCount <= ExtensionContract.MAX_TRANSFER_STROKES && pointCount <= ExtensionContract.MAX_TRANSFER_POINTS

    fun pointCount(strokes: List<Stroke>): Int = strokes.sumOf { it.points.size }

    /** Greedy chunking at the per-call caps ([InkChunks] — the contract's rule, shared with the extension). */
    fun chunk(strokes: List<WireStroke>): List<List<WireStroke>> = InkChunks.chunk(strokes)

    /**
     * The inward drain's accumulator: `takeOutgoing` chunks are [add]ed until one is empty, the
     * summed caps are reached, or [ExtensionContract.TRANSFER_MAX_CHUNKS] chunks are in — whichever
     * first. A chunk that would cross a cap is cut at the cap and [truncated] is set (the paste says
     * so); a non-empty chunk past the chunk budget is refused whole, also [truncated] (so the caller
     * probes one chunk beyond the budget and learns whether anything was left behind). Every
     * accepted stroke is [sanitize]d.
     */
    class Drain {
        private val out = ArrayList<WireStroke>()
        private var points = 0
        var chunks = 0
            private set
        var truncated = false
            private set
        val strokes: List<WireStroke> get() = out

        /** Add one chunk; false = stop draining (empty chunk, a cap reached, or the chunk budget spent). */
        fun add(chunk: List<WireStroke>): Boolean {
            if (chunk.isEmpty()) return false
            if (chunks >= ExtensionContract.TRANSFER_MAX_CHUNKS) { truncated = true; return false }
            chunks++
            for (s in chunk) {
                if (out.size >= ExtensionContract.MAX_TRANSFER_STROKES || points + s.size > ExtensionContract.MAX_TRANSFER_POINTS) {
                    truncated = true
                    return false
                }
                out += sanitize(s)
                points += s.size
            }
            return true
        }
    }

    /** The paper's strokes as wire strokes: geometry + width + colour + style name; id and time dropped; empty strokes skipped. */
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

    /** Force what SN draws: known style or PEN, width in [MIN_WIDTH]..[MAX_WIDTH], opaque black. */
    fun sanitize(bundle: InkBundle): InkBundle =
        InkBundle(bundle.strokes.map { sanitize(it) }, bundle.pageWidth, bundle.pageHeight)

    fun sanitize(s: WireStroke): WireStroke {
        val style = if (StrokeStyle.entries.any { it.name == s.style }) s.style else StrokeStyle.PEN.name
        // No NaN guard is needed: `WireStroke.requireValid` runs in the constructor — so at unmarshal
        // too — and already rejects a non-finite or non-positive width before it can reach here.
        val width = s.width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val same = style == s.style && width == s.width && s.colorArgb == Stroke.BLACK
        return if (same) s else WireStroke(s.x, s.y, s.pressure, s.tilt, width, Stroke.BLACK, style)
    }

    /** Wire strokes → paper strokes with **fresh ids** (minted here, never taken from the wire) and `timeMillis 0`. */
    fun toStrokes(strokes: List<WireStroke>, newId: () -> String = { UUID.randomUUID().toString() }): List<Stroke> {
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
