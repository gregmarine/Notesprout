package com.symmetricalpalmtree.notesprout.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Binary codec for stroke point geometry — **format B** of the `.soil` family
 * (`docs/soil-file-format.md` Part V): float32 + zlib, lossless.
 *
 * Wire layout (per stroke, independently decodable):
 * ```
 *   version : u8   (= 1)                                        -- plaintext
 *   payload : zlib{ flags:u8 | (x:f32, y:f32[, pressure:f32][, tilt:f32]) * N }   little-endian
 * ```
 * `flags` bit0 = pressure channel present, bit1 = tilt channel present. Paper writes both channels
 * (g-paper always reports them; defaults 1f / 0f), reads any combination, derives the stride from the
 * flags, and skips channels it does not know. The inflate loop bails on a zero-progress round (a
 * corrupt header would otherwise spin forever). Pure Kotlin — JVM-tested.
 *
 * Points are carried as a [Points] struct of parallel arrays so this file has no Android or g-paper
 * dependency; the notebook layer maps to/from g-paper `StrokePoint`s.
 */
object StrokeCodec {

    const val VERSION_FLOAT32: Byte = 1
    const val FLAG_PRESSURE = 0x01
    const val FLAG_TILT = 0x02

    /** Decoded geometry. [pressure] / [tilt] are null when the blob did not carry that channel. */
    class Points(
        val x: FloatArray,
        val y: FloatArray,
        val pressure: FloatArray?,
        val tilt: FloatArray?,
    ) {
        val size: Int get() = x.size
    }

    /**
     * Encode a stroke. [pressure] / [tilt] may be null (channel omitted) or the same length as [x].
     */
    fun encode(x: FloatArray, y: FloatArray, pressure: FloatArray? = null, tilt: FloatArray? = null): ByteArray {
        require(x.size == y.size) { "x/y length mismatch (${x.size}/${y.size})" }
        require(pressure == null || pressure.size == x.size) { "pressure length mismatch" }
        require(tilt == null || tilt.size == x.size) { "tilt length mismatch" }
        var flags = 0
        if (pressure != null) flags = flags or FLAG_PRESSURE
        if (tilt != null) flags = flags or FLAG_TILT
        val stride = 8 + (if (pressure != null) 4 else 0) + (if (tilt != null) 4 else 0)
        val payload = ByteBuffer.allocate(1 + x.size * stride).order(ByteOrder.LITTLE_ENDIAN)
        payload.put(flags.toByte())
        for (i in x.indices) {
            payload.putFloat(x[i])
            payload.putFloat(y[i])
            if (pressure != null) payload.putFloat(pressure[i])
            if (tilt != null) payload.putFloat(tilt[i])
        }
        val compressed = deflate(payload.array())
        return ByteArray(1 + compressed.size).also {
            it[0] = VERSION_FLOAT32
            System.arraycopy(compressed, 0, it, 1, compressed.size)
        }
    }

    /** Decode a blob produced by [encode] (or any format-B writer). Throws on a malformed blob. */
    fun decode(blob: ByteArray): Points {
        require(blob.isNotEmpty()) { "empty stroke blob" }
        return when (blob[0]) {
            VERSION_FLOAT32 -> decodeFloat32(blob)
            else -> error("unknown stroke blob version ${blob[0]}")
        }
    }

    private fun decodeFloat32(blob: ByteArray): Points {
        val payload = inflate(blob, offset = 1)
        require(payload.isNotEmpty()) { "stroke payload missing flags byte" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buf.get().toInt() and 0xFF
        val hasPressure = flags and FLAG_PRESSURE != 0
        val hasTilt = flags and FLAG_TILT != 0
        // Unknown higher bits are tolerated only if they add no bytes we can't account for: the
        // stride is derived from the bits we know, and a payload whose length isn't a multiple of it
        // is truncated to whole points (a partial trailing point is dropped, not misread).
        val stride = 8 + (if (hasPressure) 4 else 0) + (if (hasTilt) 4 else 0)
        val n = (payload.size - 1) / stride
        val x = FloatArray(n)
        val y = FloatArray(n)
        val p = if (hasPressure) FloatArray(n) else null
        val t = if (hasTilt) FloatArray(n) else null
        for (i in 0 until n) {
            x[i] = buf.float
            y[i] = buf.float
            if (p != null) p[i] = buf.float
            if (t != null) t[i] = buf.float
        }
        return Points(x, y, p, t)
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
        try {
            // Bail on ANY zero-progress round — a corrupt header (e.g. FDICT set) makes inflate()
            // return 0 forever with needsInput() false, spinning this loop into an ANR.
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }
}
