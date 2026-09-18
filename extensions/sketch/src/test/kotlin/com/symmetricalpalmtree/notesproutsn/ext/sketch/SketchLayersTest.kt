package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pin between the two names for a raster (arc 45 "Ink" / G3): the wire's `Int` and the engine's
 * [RasterLayer].
 *
 * It is a small object and the tests are small, but the thing they guard is not: a silent swap of
 * the two numbers would write the pencil's pixels into the ink row and the pen's into the graphite
 * one, on a surface whose drawing has no other copy, and every symptom of it would first appear on
 * a device after a save and a reload. Asserting the mapping **against the contract's own
 * constants** is what makes a renumbering on the seam fail here rather than there.
 */
class SketchLayersTest {

    @Test
    fun `each layer maps to the contract's own number for it`() {
        // Against the constants, never against 0 and 1 spelled again — a seam renumbering has to
        // fail a test, not quietly agree with a literal.
        assertEquals(SketchContract.LAYER_GRAPHITE, SketchLayers.wireOf(RasterLayer.GRAPHITE))
        assertEquals(SketchContract.LAYER_INK, SketchLayers.wireOf(RasterLayer.INK))
    }

    @Test
    fun `every layer round trips both ways`() {
        for (layer in RasterLayer.entries) {
            assertEquals(layer, SketchLayers.of(SketchLayers.wireOf(layer)))
        }
        for (wire in SketchContract.LAYERS) {
            assertEquals(wire, SketchLayers.wireOf(SketchLayers.of(wire)))
        }
    }

    @Test
    fun `a number that names no raster is refused`() {
        // A layer arrives as an unmarshalled integer and there is no third raster for it to mean —
        // the host's own first check on either chunk call, made here for the same reason.
        for (wire in intArrayOf(2, -1, Int.MAX_VALUE, Int.MIN_VALUE)) {
            try {
                SketchLayers.of(wire)
                fail("layer $wire should not name a raster")
            } catch (expected: IllegalArgumentException) {
                // as it should be
            }
        }
    }

    @Test
    fun `the loop order is graphite first, then ink, and it is every layer there is`() {
        // Every walk over the rasters takes this list: the saver's, the park's drain, the page
        // load's, the delete's park clear. Graphite first is the host's announce-and-flatten order
        // and the engine's, so a log line naming two layers always names them the same way round.
        assertEquals(listOf(RasterLayer.GRAPHITE, RasterLayer.INK), SketchLayers.all)
        assertEquals("a raster the face never loops over is a raster that never saves", RasterLayer.entries.size, SketchLayers.all.size)
        assertEquals(SketchContract.LAYERS, SketchLayers.all.map { SketchLayers.wireOf(it) })
    }
}
