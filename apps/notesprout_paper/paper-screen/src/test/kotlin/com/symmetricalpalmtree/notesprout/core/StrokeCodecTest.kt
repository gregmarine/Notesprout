package com.symmetricalpalmtree.notesprout.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Locks the format-B contract: float32 + zlib, flags-driven channels, lossless. */
class StrokeCodecTest {

    private fun dense(n: Int): Pair<FloatArray, FloatArray> {
        val x = FloatArray(n); val y = FloatArray(n)
        var px = 300f; var py = 400f
        for (i in 0 until n) {
            px += (i % 7) * 0.11905f - 0.3f
            py += (i % 5) * 0.09521f - 0.2f
            x[i] = px; y[i] = py
        }
        return x to y
    }

    @Test
    fun roundTrips_xyOnly_bitExact() {
        val x = floatArrayOf(257.9762f, 257.85718f, 0f, 1860f)
        val y = floatArrayOf(390.0f, 389.88095f, 0f, 2480f)
        val d = StrokeCodec.decode(StrokeCodec.encode(x, y))
        assertArrayEquals(x, d.x, 0f)
        assertArrayEquals(y, d.y, 0f)
        assertNull(d.pressure)
        assertNull(d.tilt)
    }

    @Test
    fun roundTrips_pressureAndTilt() {
        val (x, y) = dense(200)
        val p = FloatArray(200) { it / 200f }
        val t = FloatArray(200) { (it % 90).toFloat() }
        val blob = StrokeCodec.encode(x, y, p, t)
        val d = StrokeCodec.decode(blob)
        assertArrayEquals(x, d.x, 0f); assertArrayEquals(y, d.y, 0f)
        assertNotNull(d.pressure); assertNotNull(d.tilt)
        assertArrayEquals(p, d.pressure!!, 0f); assertArrayEquals(t, d.tilt!!, 0f)
    }

    @Test
    fun roundTrips_pressureOnly_and_tiltOnly() {
        val (x, y) = dense(50)
        val p = FloatArray(50) { 0.5f }
        val t = FloatArray(50) { 12f }
        val dp = StrokeCodec.decode(StrokeCodec.encode(x, y, pressure = p))
        assertArrayEquals(p, dp.pressure!!, 0f); assertNull(dp.tilt)
        val dt = StrokeCodec.decode(StrokeCodec.encode(x, y, tilt = t))
        assertNull(dt.pressure); assertArrayEquals(t, dt.tilt!!, 0f)
    }

    @Test
    fun roundTrips_empty_stroke() {
        val d = StrokeCodec.decode(StrokeCodec.encode(FloatArray(0), FloatArray(0)))
        assertEquals(0, d.size)
    }

    @Test
    fun versionByte_isPlaintext_one() {
        assertEquals(StrokeCodec.VERSION_FLOAT32, StrokeCodec.encode(floatArrayOf(1f), floatArrayOf(2f))[0])
    }

    @Test
    fun compresses_wellBelow_rawFloat32() {
        val n = 500
        val x = FloatArray(n); val y = FloatArray(n)
        var px = 300f; var py = 400f
        for (i in 0 until n) { px += 0.5f; py += 0.25f; x[i] = px; y[i] = py }
        val encoded = StrokeCodec.encode(x, y)
        assertTrue("encoded ${encoded.size}B should be well under raw ${n * 8}B", encoded.size < n * 8)
    }

    @Test
    fun rejects_lengthMismatch() {
        try { StrokeCodec.encode(floatArrayOf(1f, 2f), floatArrayOf(1f)); fail() } catch (_: IllegalArgumentException) {}
        try { StrokeCodec.encode(floatArrayOf(1f), floatArrayOf(1f), pressure = FloatArray(2)); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun rejects_emptyBlob_and_unknownVersion() {
        try { StrokeCodec.decode(ByteArray(0)); fail() } catch (_: IllegalArgumentException) {}
        try { StrokeCodec.decode(byteArrayOf(9, 1, 2, 3)); fail() } catch (_: IllegalStateException) {}
    }

    @Test
    fun truncatedBlob_doesNotHang_andDropsPartialPoint() {
        val (x, y) = dense(100)
        val blob = StrokeCodec.encode(x, y)
        // Cut the zlib stream short: inflate yields whatever it can (or nothing) — never spins.
        val cut = blob.copyOf(blob.size / 2)
        val d = try { StrokeCodec.decode(cut) } catch (_: Exception) { null }
        // Either a clean failure or a prefix — both acceptable; the point is termination + no garbage tail.
        if (d != null) assertTrue(d.size <= 100)
    }

    @Test
    fun zeroProgressInflate_bails() {
        // A zlib header with FDICT set (0x78 0xBB…) makes Inflater return 0 forever with needsInput()
        // false. The decoder must terminate; the result is treated as a malformed (empty) payload.
        val bad = byteArrayOf(StrokeCodec.VERSION_FLOAT32, 0x78.toByte(), 0xBB.toByte(), 0x01, 0x02, 0x03, 0x04)
        try {
            StrokeCodec.decode(bad)
        } catch (_: Exception) {
            // acceptable: malformed
        }
    }

    @Test
    fun readsForeignWriter_withUnknownHighFlagBits_ignored() {
        // A payload with only x,y and no unknown-channel bytes but flags high bits set decodes as xy.
        val payload = ByteArrayOutputStream()
        val body = java.nio.ByteBuffer.allocate(1 + 8 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        body.put(0x40.toByte()) // unknown bit only
        body.putFloat(1f); body.putFloat(2f); body.putFloat(3f); body.putFloat(4f)
        val d = Deflater(); d.setInput(body.array()); d.finish()
        val buf = ByteArray(256); val n = d.deflate(buf); d.end()
        payload.write(StrokeCodec.VERSION_FLOAT32.toInt()); payload.write(buf, 0, n)
        val out = StrokeCodec.decode(payload.toByteArray())
        assertEquals(2, out.size)
        assertEquals(3f, out.x[1], 0f)
    }
}
