package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The WebP header, read by hand (arc 45 "Ink" / G2 — `PngHeader`'s successor, arc 43 / K2) — the
 * guard that stands in front of every sketch decode, on both sides of the seam.
 *
 * A page's raster is only meaningful at exactly the page's size: an image of the wrong dimensions
 * composited over the paper is a drawing sliding off its own page, and a *malformed* one is a
 * decoder handed bytes from who knows where. Both are cheap to rule out before any bitmap is
 * allocated — the answer is in the first 30 bytes — and expensive to discover afterwards, which is
 * why the host checks [matches] on a save's last chunk (refusing with
 * [SketchContract.SKETCH_BAD_IMAGE]) and again before it hands stored bytes to a decoder.
 *
 * **Why WebP (decision 3 of `INK_PLAN.md`):** a sketch is two rasters now — graphite and ink — each
 * stored as a page-sized **lossless WebP with alpha** (RGBA, colour-ready for the platforms where
 * the pen will have a colour). A PNG is simply not a WebP and fails this guard; there is no legacy
 * to sniff for (decision 4).
 *
 * **What the container looks like.** `RIFF` · file size · `WEBP`, then the first chunk: a FourCC
 * and a little-endian size. libwebp writes a lossless image as either
 *  - **`VP8L`** — the simple lossless form: one signature byte `0x2F`, then 28 bits of 14-bit
 *    `width − 1` / `height − 1` (little-endian, the width in the low bits); or
 *  - **`VP8X`** — the extended form (used when the encoder adds a feature flag, metadata or, for a
 *    lossy image, a separate alpha chunk): a flags byte, 3 reserved bytes, then 24-bit
 *    `width − 1` / `height − 1`, each little-endian.
 * Either may head a lossless RGBA image depending on the encoder's mood, so both are read; a `VP8 `
 * (lossy) first chunk is refused — nothing on this seam writes one.
 *
 * **No Android classes here on purpose.** `BitmapFactory` could answer the size question with
 * `inJustDecodeBounds`, but that is a platform call that cannot run on the JVM, and this is the one
 * piece of the sketch seam whose every boundary deserves a laptop test: truncation at each field,
 * a wrong FourCC, a first chunk that is neither form. Little-endian by hand, bounds-checked at
 * every read, and a 0-length array answers null like anything else it cannot parse.
 *
 * It parses the header and nothing else: no RIFF size reconciliation against `bytes.size` (a
 * truncated body is the decoder's to report), and no opinion about the values it reads —
 * [SketchContract.MAX_PAGE_PX] is the caller's guard, not the parser's. A parser that refused a
 * legal file would be a worse bug than the one it was guarding against.
 */
object ImageHeader {

    /** `RIFF` · 4 size bytes · `WEBP` — the 12-byte container preamble. */
    private const val PREAMBLE_BYTES = 12

    /** FourCC (4) + little-endian chunk size (4). */
    private const val CHUNK_HEADER_BYTES = 8

    /** The `VP8L` payload we read: the signature byte and the 4 packed dimension bytes. */
    private const val VP8L_HEAD_BYTES = 5

    /** The `VP8L` bitstream signature — the first payload byte. */
    private const val VP8L_SIGNATURE = 0x2F

    /** The `VP8X` payload we read: flags (1), reserved (3), width−1 (3), height−1 (3). */
    private const val VP8X_HEAD_BYTES = 10

    /** The first chunk's FourCC — one of the two lossless-capable forms. */
    const val CHUNK_VP8L: String = "VP8L"
    const val CHUNK_VP8X: String = "VP8X"

    /** What the container's first chunk says about the image. [chunk] is [CHUNK_VP8L] or
     *  [CHUNK_VP8X] — carried for a log line, never decided on. */
    data class Header(val width: Int, val height: Int, val chunk: String)

    /**
     * The header of [bytes], or **null** when this is not a WebP whose size can be trusted: too
     * short, not `RIFF`/`WEBP`, a first chunk that is neither `VP8L` nor `VP8X`, a `VP8L` payload
     * without its signature byte, a chunk whose declared size is shorter than the fields we read,
     * or a version bit set that no reader we ship understands. Never reads past `bytes.size`.
     */
    fun parse(bytes: ByteArray): Header? {
        if (bytes.size < PREAMBLE_BYTES + CHUNK_HEADER_BYTES) return null
        if (!fourcc(bytes, 0, "RIFF") || !fourcc(bytes, 8, "WEBP")) return null
        val payloadAt = PREAMBLE_BYTES + CHUNK_HEADER_BYTES
        val declared = le32(bytes, PREAMBLE_BYTES + 4)
        return when {
            fourcc(bytes, PREAMBLE_BYTES, CHUNK_VP8L) -> {
                if (declared < VP8L_HEAD_BYTES || bytes.size < payloadAt + VP8L_HEAD_BYTES) return null
                if ((bytes[payloadAt].toInt() and 0xFF) != VP8L_SIGNATURE) return null
                val packed = le32(bytes, payloadAt + 1)
                // Bits 0–13 width−1, 14–27 height−1, 28 alpha_is_used, 29–31 version (must be 0).
                if ((packed ushr 29) and 0x7 != 0) return null
                Header(
                    width = (packed and 0x3FFF) + 1,
                    height = ((packed ushr 14) and 0x3FFF) + 1,
                    chunk = CHUNK_VP8L,
                )
            }
            fourcc(bytes, PREAMBLE_BYTES, CHUNK_VP8X) -> {
                if (declared < VP8X_HEAD_BYTES || bytes.size < payloadAt + VP8X_HEAD_BYTES) return null
                Header(
                    width = le24(bytes, payloadAt + 4) + 1,
                    height = le24(bytes, payloadAt + 7) + 1,
                    chunk = CHUNK_VP8X,
                )
            }
            else -> null
        }
    }

    /** [bytes]' image size, or null when [parse] says it cannot read the header. */
    fun size(bytes: ByteArray): Pair<Int, Int>? = parse(bytes)?.let { it.width to it.height }

    /** Whether [bytes] is a WebP of exactly [width] × [height] — the host's save guard and the
     *  screen's load guard, one sentence in one place. */
    fun matches(bytes: ByteArray, width: Int, height: Int): Boolean {
        val header = parse(bytes) ?: return false
        return header.width == width && header.height == height
    }

    /** Whether the four bytes at [at] spell [tag]. The caller has already bounds-checked. */
    private fun fourcc(b: ByteArray, at: Int, tag: String): Boolean {
        for (i in 0 until 4) if (b[at + i] != tag[i].code.toByte()) return false
        return true
    }

    /** Little-endian unsigned 32-bit at [at], as an Int. The caller has already bounds-checked. */
    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Little-endian unsigned 24-bit at [at]. The caller has already bounds-checked. */
    private fun le24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)
}
