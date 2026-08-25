package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** [InkBundle.requireValid] — the per-Binder-call chunk caps and the page geometry, enforced in the
 *  constructor so a bad chunk is refused at unmarshal on the receiving side. */
class InkBundleTest {

    private fun stroke(n: Int) = WireStroke(
        FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), 3f, -0x1000000, "PEN",
    )

    @Test
    fun emptyBundleIsLegal() {
        // It is how `takeOutgoing` says "done".
        val b = InkBundle(emptyList(), 0f, 0f)
        assertEquals(0, b.strokes.size)
        assertEquals(0, b.pointCount)
    }

    @Test
    fun acceptsAChunkAtTheCaps() {
        val strokes = List(ExtensionContract.TRANSFER_CHUNK_STROKES) { stroke(1) }
        assertEquals(ExtensionContract.TRANSFER_CHUNK_STROKES, InkBundle(strokes, 100f, 200f).strokes.size)
    }

    @Test
    fun rejectsTooManyStrokes() {
        val strokes = List(ExtensionContract.TRANSFER_CHUNK_STROKES + 1) { stroke(1) }
        assertThrows(IllegalArgumentException::class.java) { InkBundle(strokes, 0f, 0f) }
    }

    @Test
    fun rejectsTooManyPointsAcrossSeveralStrokes() {
        val strokes = List(2) { stroke(ExtensionContract.TRANSFER_CHUNK_POINTS / 2 + 1) }
        assertThrows(IllegalArgumentException::class.java) { InkBundle(strokes, 0f, 0f) }
    }

    @Test
    fun allowsOneOversizeStrokeAsItsOwnChunk() {
        // The chunker never splits a stroke, so a single stroke over the point chunk cap must be
        // legal as a chunk of one — still bounded by the whole-transfer cap.
        val big = stroke(ExtensionContract.TRANSFER_CHUNK_POINTS + 1)
        assertEquals(1, InkBundle(listOf(big), 0f, 0f).strokes.size)
        val tooBig = stroke(ExtensionContract.MAX_TRANSFER_POINTS + 1)
        assertThrows(IllegalArgumentException::class.java) { InkBundle(listOf(tooBig), 0f, 0f) }
    }

    @Test
    fun rejectsBadPageGeometry() {
        assertThrows(IllegalArgumentException::class.java) { InkBundle(emptyList(), -1f, 10f) }
        assertThrows(IllegalArgumentException::class.java) { InkBundle(emptyList(), Float.NaN, 10f) }
        assertThrows(IllegalArgumentException::class.java) { InkBundle(emptyList(), 10f, Float.POSITIVE_INFINITY) }
    }
}
