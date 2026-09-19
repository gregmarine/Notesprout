package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract

/**
 * The one place the seam's name for a raster and the engine's name for it are the same thing
 * (arc 45 "Ink" / G3).
 *
 * A page's sketch is **two rasters, one picture** — graphite and ink — and the face stands between
 * two vocabularies for them: the wire names them by `Int`
 * ([SketchContract.LAYER_GRAPHITE] / [SketchContract.LAYER_INK], because a Binder call carries
 * numbers), and g-paper names them by [RasterLayer], because an engine that routes a mark by its
 * style wants a type it can exhaust. Both are right where they are; what would be wrong is
 * translating between them in a dozen places, so the translation is written once, here, and every
 * call site reads one of these three members.
 *
 * Pure Kotlin: [RasterLayer] lives in `gpaper-core` and is a plain enum with no Android in it, so
 * this object — and the pin between the two vocabularies — is provable on a laptop. A renumbering
 * of the contract's constants fails a test in `SketchLayersTest` rather than a device walk.
 */
object SketchLayers {

    /**
     * The order **every** loop over the rasters takes: graphite first, then ink.
     *
     * It is the order the host announces and flattens them in (`SketchContract.LAYERS`), the order
     * g-paper announces a load or a clear in, and the order a page is read and written in — so a
     * log line naming two layers always names them the same way round, and "graphite first" is one
     * rule rather than a coincidence repeated at six call sites.
     */
    val all: List<RasterLayer> = listOf(RasterLayer.GRAPHITE, RasterLayer.INK)

    /** The wire's number for the raster [layer] names. Exhaustive by construction. */
    fun wireOf(layer: RasterLayer): Int = when (layer) {
        RasterLayer.GRAPHITE -> SketchContract.LAYER_GRAPHITE
        RasterLayer.INK -> SketchContract.LAYER_INK
    }

    /**
     * The raster [wire] names, or `IllegalArgumentException` for anything else — the same refusal
     * the host makes on either chunk call, made here for the same reason: a layer number is an
     * unmarshalled integer and there is no third raster for it to mean.
     */
    fun of(wire: Int): RasterLayer = when (wire) {
        SketchContract.LAYER_GRAPHITE -> RasterLayer.GRAPHITE
        SketchContract.LAYER_INK -> RasterLayer.INK
        else -> throw IllegalArgumentException("Unknown layer $wire")
    }
}
