package com.notesprout.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the binary stroke format contract (format "B" — float32 + zlib, lossless).
 *
 * Pure JVM: [StrokeCodec] operates on [FloatArray] with no `android.graphics` dependency, so the
 * byte-level round-trip is exercised here without Robolectric.
 */
class StrokeCodecTest {

    @Test
    fun roundTrips_exact_floats() {
        val xy = floatArrayOf(257.9762f, 390.0f, 257.85718f, 389.88095f, 0f, 0f, 1860f, 2480f)
        assertArrayEquals(xy, StrokeCodec.decode(StrokeCodec.encode(xy)), 0f)
    }

    @Test
    fun roundTrips_empty_stroke() {
        val blob = StrokeCodec.encode(FloatArray(0))
        assertEquals(0, StrokeCodec.decode(blob).size)
    }

    @Test
    fun roundTrips_single_point() {
        val xy = floatArrayOf(12.5f, 99.25f)
        assertArrayEquals(xy, StrokeCodec.decode(StrokeCodec.encode(xy)), 0f)
    }

    @Test
    fun roundTrips_dense_stroke_bitExact() {
        // A realistic 500-point stroke of small dense hops, like captured handwriting.
        val xy = FloatArray(1000)
        var x = 300f; var y = 400f
        for (i in 0 until 500) {
            x += (i % 7) * 0.11905f - 0.3f
            y += (i % 5) * 0.09521f - 0.2f
            xy[i * 2] = x; xy[i * 2 + 1] = y
        }
        assertArrayEquals(xy, StrokeCodec.decode(StrokeCodec.encode(xy)), 0f)
    }

    @Test
    fun compresses_wellBelow_rawFloat32() {
        // Dense, smooth strokes must beat raw 8 bytes/point after zlib (the whole point of format B).
        val n = 500
        val xy = FloatArray(n * 2)
        var x = 300f; var y = 400f
        for (i in 0 until n) { x += 0.5f; y += 0.25f; xy[i * 2] = x; xy[i * 2 + 1] = y }
        val encoded = StrokeCodec.encode(xy)
        assertTrue("encoded ${encoded.size}B should be well under raw ${n * 8}B", encoded.size < n * 8)
    }

    @Test
    fun rejects_oddLengthInput() {
        try {
            StrokeCodec.encode(floatArrayOf(1f, 2f, 3f))
            throw AssertionError("expected IllegalArgumentException for odd-length input")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
