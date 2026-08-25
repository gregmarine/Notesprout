package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.StrokeCodec
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * A scratch page's store blob ⇄ (pageWidth, pageHeight, strokes) — pure, JVM-tested (arc 11 / J3).
 *
 * Layout (big-endian `DataOutput`):
 * ```
 *   u8  version (= 1)
 *   f32 pageWidth · f32 pageHeight          (0 × 0 = unknown — the surface size at first layout)
 *   u32 count
 *   per stroke { u16 idLen + UTF-8 id · f32 width · i32 colorArgb · u8 styleNameLen + ASCII name ·
 *                u32 blobLen + StrokeCodec format-B blob (x/y/pressure/tilt) }
 * ```
 * [StrokeCodec] is the `.soil`'s own stroke encoding (shared from `:sn-screen`), so a page blob is
 * that format with a header. The reader tolerates a **truncated tail** (the partial stroke is
 * dropped) and skips a stroke whose geometry blob is malformed; an unknown version / header is an
 * `IllegalArgumentException` — the caller treats the page as **unreadable** and says so, and never
 * saves a blank page over it.
 */
object ScratchPageCodec {

    const val VERSION: Int = 1

    /** `u8 version · f32 w · f32 h · u32 count` — the bytes before the first stroke. */
    const val HEADER_BYTES: Int = 13

    /** The exact number of bytes [stroke] adds to a page blob (deterministic — the full rule's
     *  running total: `HEADER_BYTES + Σ strokeBytes` == `encode(...).size`). Stroke geometry is
     *  zlib-compressed per stroke, so a moved stroke must be **re-measured**, never assumed
     *  to re-encode to the same size. */
    fun strokeBytes(stroke: Stroke): Int = encode(0f, 0f, listOf(stroke)).size - HEADER_BYTES

    class Page(val pageWidth: Float, val pageHeight: Float, val strokes: List<Stroke>)

    fun encode(pageWidth: Float, pageHeight: Float, strokes: List<Stroke>): ByteArray {
        val bytes = ByteArrayOutputStream(64 + strokes.size * 256)
        val out = DataOutputStream(bytes)
        out.writeByte(VERSION)
        out.writeFloat(pageWidth)
        out.writeFloat(pageHeight)
        out.writeInt(strokes.size)
        for (s in strokes) {
            val id = s.id.toByteArray(Charsets.UTF_8)
            require(id.size <= 0xFFFF) { "stroke id too long" }
            out.writeShort(id.size); out.write(id)
            out.writeFloat(s.width)
            out.writeInt(s.color)
            val style = s.style.name.toByteArray(Charsets.US_ASCII)
            out.writeByte(style.size); out.write(style)
            val n = s.points.size
            val x = FloatArray(n); val y = FloatArray(n); val p = FloatArray(n); val t = FloatArray(n)
            for (i in 0 until n) { val pt = s.points[i]; x[i] = pt.x; y[i] = pt.y; p[i] = pt.pressure; t[i] = pt.tilt }
            val blob = StrokeCodec.encode(x, y, p, t)
            out.writeInt(blob.size); out.write(blob)
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decode(blob: ByteArray): Page {
        require(blob.size >= HEADER_BYTES) { "page blob too short (${blob.size})" }
        val inp = DataInputStream(blob.inputStream())
        val version = inp.readUnsignedByte()
        require(version == VERSION) { "unknown page blob version $version" }
        val w = inp.readFloat()
        val h = inp.readFloat()
        val count = inp.readInt()
        require(count >= 0) { "negative stroke count" }
        val strokes = ArrayList<Stroke>(minOf(count, 10_000))
        try {
            for (i in 0 until count) {
                val idLen = inp.readUnsignedShort()
                val id = ByteArray(idLen).also { inp.readFully(it) }.toString(Charsets.UTF_8)
                val width = inp.readFloat()
                val color = inp.readInt()
                val styleLen = inp.readUnsignedByte()
                val style = ByteArray(styleLen).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
                val len = inp.readInt()
                require(len >= 0 && len <= blob.size) { "bad stroke blob length $len" }
                val geo = ByteArray(len).also { inp.readFully(it) }
                val pts = try { StrokeCodec.decode(geo) } catch (_: Exception) { continue }
                if (pts.size == 0) continue
                val points = ArrayList<StrokePoint>(pts.size)
                for (k in 0 until pts.size) {
                    points += StrokePoint(pts.x[k], pts.y[k], pts.pressure?.get(k) ?: 1f, pts.tilt?.get(k) ?: 0f, 0L)
                }
                strokes += Stroke(id = id, points = points, color = color, width = width, style = styleOf(style))
            }
        } catch (_: EOFException) {
            // Truncated tail: keep what decoded whole.
        }
        return Page(w, h, strokes)
    }

    private fun styleOf(name: String): StrokeStyle =
        StrokeStyle.entries.firstOrNull { it.name == name } ?: StrokeStyle.PEN
}
