package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The chunk caps (`requireValid`) — a Parcel round trip needs a device. */
class InkBundleTest {

    private fun stroke(n: Int) = PaperStroke(FloatArray(n) { 1f }, FloatArray(n) { 2f }, FloatArray(n) { 1f }, FloatArray(n), 3f, 0, "PEN")

    @Test
    fun emptyBundleIsLegalAndCountsZero() {
        val b = InkBundle(emptyList(), 0f, 0f)
        assertEquals(0, b.pointCount)
        assertEquals(0, b.strokes.size)
    }

    @Test
    fun countsPointsAndCarriesPageSize() {
        val b = InkBundle(listOf(stroke(3), stroke(4)), 1404f, 1872f)
        assertEquals(7, b.pointCount)
        assertEquals(1404f, b.pageWidth, 0f)
    }

    @Test
    fun rejectsTooManyStrokes() {
        val ok = List(ExtensionContract.TRANSFER_CHUNK_STROKES) { stroke(1) }
        InkBundle(ok, 0f, 0f)
        assertThrows(IllegalArgumentException::class.java) { InkBundle(ok + stroke(1), 0f, 0f) }
    }

    @Test
    fun rejectsTooManyPointsUnlessOneStroke() {
        val two = listOf(stroke(ExtensionContract.TRANSFER_CHUNK_POINTS), stroke(1))
        assertThrows(IllegalArgumentException::class.java) { InkBundle(two, 0f, 0f) }
        // A lone stroke over the chunk cap is its own chunk — up to the whole-transfer cap.
        InkBundle(listOf(stroke(ExtensionContract.TRANSFER_CHUNK_POINTS + 1)), 0f, 0f)
        assertThrows(IllegalArgumentException::class.java) {
            InkBundle(listOf(stroke(ExtensionContract.MAX_TRANSFER_POINTS + 1)), 0f, 0f)
        }
    }

    @Test
    fun rejectsBadPageSize() {
        assertThrows(IllegalArgumentException::class.java) { InkBundle(emptyList(), -1f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { InkBundle(emptyList(), 0f, Float.NaN) }
    }

    @Test
    fun largeValueBounds() {
        LargeValue.requireValid(1, 1)
        LargeValue.requireValid(ExtensionContract.STORE_MAX_VALUE_BYTES, ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertThrows(IllegalArgumentException::class.java) { LargeValue.requireValid(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { LargeValue.requireValid(11, 10) }
        assertThrows(IllegalArgumentException::class.java) {
            LargeValue.requireValid(ExtensionContract.STORE_MAX_VALUE_BYTES + 1, ExtensionContract.STORE_MAX_VALUE_BYTES + 1)
        }
    }
}
