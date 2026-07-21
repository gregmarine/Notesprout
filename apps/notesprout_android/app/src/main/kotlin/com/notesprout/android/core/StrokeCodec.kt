package com.notesprout.android.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Binary codec for stroke point **geometry** — the format that replaces the JSON point arrays that
 * used to dominate `.soil` files (measured ~99% of payload; `{"x":257.9762,"y":390.0}` spent ~25
 * bytes to carry 8 bytes of float). A stroke's colour and width live in their own row columns now, so
 * a stroke's `blob` column is *only* its points.
 *
 * **Format "B" — float32 + zlib, lossless.** On the real 44 MB "Notesprout" notebook this took the
 * baseline 43.3 MB → 8.6 MB (5.0× smaller) with every one of 11,003 strokes round-tripping exactly.
 * Chosen over lossy int16 quantization: for a handwriting-first app we never silently alter the user's
 * ink, and binary+zlib still parses far faster than the old JSON (which was the load bottleneck).
 *
 * Wire layout (per stroke, independently decodable so partial/lazy loads work):
 * ```
 *   version : u8   (= 1, VERSION_FLOAT32)         -- plaintext, so the format can evolve
 *   payload : zlib{ flags:u8 | (x:f32, y:f32) * N }   little-endian
 * ```
 * `flags` is reserved for optional per-point channels ([FLAG_PRESSURE]/[FLAG_TILT]) so pressure/tilt
 * can be added later without a version bump — v1 writes `flags = 0` (xy-only) and the decoder derives
 * the per-point stride from the flags it reads.
 *
 * Android-free (operates on interleaved [FloatArray]) so it round-trips in plain JVM unit tests;
 * `LiveStroke` provides the `PointF` adapters.
 */
object StrokeCodec {

    private const val VERSION_FLOAT32: Byte = 1
    private const val FLAG_PRESSURE = 0x01
    private const val FLAG_TILT = 0x02

    /**
     * Encode interleaved [xy] (`x0,y0,x1,y1,…`) to a versioned, zlib-compressed blob.
     * An empty stroke encodes to a valid (tiny) blob that decodes back to an empty array.
     */
    fun encode(xy: FloatArray): ByteArray {
        require(xy.size % 2 == 0) { "xy must be interleaved x,y pairs (got ${xy.size})" }
        val payload = ByteBuffer.allocate(1 + xy.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(0)  // flags: xy-only
        for (v in xy) payload.putFloat(v)
        val compressed = deflate(payload.array())
        return ByteArray(1 + compressed.size).also {
            it[0] = VERSION_FLOAT32
            System.arraycopy(compressed, 0, it, 1, compressed.size)
        }
    }

    /** Decode a blob produced by [encode] back to interleaved `x0,y0,x1,y1,…`. */
    fun decode(blob: ByteArray): FloatArray {
        require(blob.isNotEmpty()) { "empty stroke blob" }
        return when (blob[0]) {
            VERSION_FLOAT32 -> decodeFloat32(blob)
            else -> error("unknown stroke blob version ${blob[0]}")
        }
    }

    private fun decodeFloat32(blob: ByteArray): FloatArray {
        val payload = inflate(blob, offset = 1)
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.get().toInt()
        val perPointExtra = (if (flags and FLAG_PRESSURE != 0) 4 else 0) +
                            (if (flags and FLAG_TILT != 0) 4 else 0)
        val stride = 8 + perPointExtra
        val n = (payload.size - 1) / stride
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            out[i * 2] = buf.float
            out[i * 2 + 1] = buf.float
            repeat(perPointExtra / 4) { buf.float }   // skip reserved channels (v1 unused)
        }
        return out
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(maxOf(16, data.size / 2))
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, offset: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(data, offset, data.size - offset)
        val out = ByteArrayOutputStream(maxOf(16, (data.size - offset) * 3))
        val buf = ByteArray(4096)
        // Bail on ANY zero-progress round — a corrupt header (e.g. FDICT set) makes inflate()
        // return 0 forever with needsInput() false, spinning this loop into an ANR.
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }
}
