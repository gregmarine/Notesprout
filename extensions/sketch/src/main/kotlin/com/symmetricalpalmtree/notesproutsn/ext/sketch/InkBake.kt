package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.InkWire
import java.util.UUID

/**
 * "Bring in ink" (decision 8), the pure half: the page's bare strokes as they arrived over the seam,
 * turned into the strokes the sketch surface composites (arc 43 / K5).
 *
 * **The bake is one-way and it is drawn as written, in black pen.** The notebook's ink is the
 * person's handwriting on that page; bringing it in is tracing it onto the sketch so the drawing can
 * be built around it, not restyling it — so each stroke keeps its width and its style and loses only
 * its colour, which is forced to opaque black. That is the same sanitize rule the pad's outbound
 * transfer takes (`TransferCaps.sanitize`, host-side) and it is here for the same two reasons: an
 * ARGB int from another process is untrusted, and a raster page has exactly one ink — there is no
 * colour on this surface to honour a coloured stroke with.
 *
 * **Which raster it lands in is the engine's answer, not this object's** (arc 45 "Ink" / G3).
 * g-paper routes every composited stroke by its style at one site, `RasterLayer.of`: a `PEN` stroke
 * — which is what [InkWire.styleOf] answers for the notebook's own ink, and what an unknown style
 * name falls back to — bakes into the **ink** raster, where the rubber can never reach it. That is
 * the right home for brought-in handwriting: it is a tracing to build the drawing *around*, and a
 * rub of the graphite over it should not take it away.
 *
 * A notebook stroke written with `StrokeStyle.PENCIL` would bake into the **graphite** raster and
 * be rubbable, and that is also correct rather than a gap — routing by style is the whole rule, and
 * a pencil line brought in from the page is a pencil line. (No SN notebook tool writes `PENCIL`
 * today; [InkWire.styleOf] simply reads whatever style name the wire carries, so nothing here has
 * to be kept in step if one ever does.)
 *
 * Everything else about the wire is [InkWire]'s and is not repeated: fresh ids, `timeMillis` 0, an
 * unknown style name reading as PEN, the width clamped. **Nothing from the wire is trusted beyond
 * its geometry.**
 *
 * A page dense past the transfer caps arrives **cut** (the host says so in its own log) — the prefix
 * that fits, in writing order. A partial bake is the honest outcome there: the alternative is no
 * bake at all, and the strokes that did arrive are the ones drawn first.
 */
object InkBake {

    /** The one colour a baked stroke lands in: opaque black. */
    const val BAKE_COLOR: Int = 0xFF000000.toInt()

    /**
     * [wire] as strokes ready for `paper.addStrokes`, black and otherwise as drawn. An empty list
     * in is an empty list out — the screen words its own "No ink on this page" from the count the
     * host answered with, never from this.
     */
    fun toBakedStrokes(
        wire: List<WireStroke>,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<Stroke> = InkWire.toStrokes(wire, newId).map { it.copy(color = BAKE_COLOR) }
}
