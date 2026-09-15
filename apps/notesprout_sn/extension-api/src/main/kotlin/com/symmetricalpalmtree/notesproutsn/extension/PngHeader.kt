package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The PNG header, read by hand (arc 43 / K2) — the guard that stands in front of every sketch
 * decode, on both sides of the seam.
 *
 * A page's sketch is only meaningful at exactly the page's size: a PNG of the wrong dimensions
 * composited over the paper is a drawing sliding off its own page, and a *malformed* one is a
 * decoder handed bytes from who knows where. Both are cheap to rule out before any bitmap is
 * allocated — the answer is in the first 33 bytes — and expensive to discover afterwards, which is
 * why the host checks [matches] on a save's last chunk (refusing with
 * [SketchContract.SKETCH_BAD_PNG]) and again before it hands stored bytes to a decoder.
 *
 * **No Android classes here on purpose.** `BitmapFactory` could answer the size question with
 * `inJustDecodeBounds`, but that is a platform call that cannot run on the JVM, and this is the one
 * piece of the sketch seam whose every boundary deserves a laptop test: truncation at each field,
 * a wrong signature, a first chunk that is not `IHDR`. Big-endian by hand, bounds-checked at every
 * read, and a 0-length array answers null like anything else it cannot parse.
 *
 * It parses the header and nothing else: no CRC check (a corrupt body is the decoder's to report,
 * and re-hashing megabytes to learn what a decode would tell us is a cost with no buyer), and no
 * opinion about the values it reads — [MAX_PAGE_PX][SketchContract.MAX_PAGE_PX] is the caller's
 * guard, not the parser's. A parser that refused a legal file would be a worse bug than the one it
 * was guarding against.
 */
object PngHeader {

    /** The 8-byte PNG signature: `\x89PNG\r\n\x1a\n`. */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** The `IHDR` chunk's declared data length — always 13 in a well-formed PNG. */
    private const val IHDR_LENGTH = 13

    /** Signature (8) + chunk length (4) + chunk type (4) + the 13 data bytes. */
    private const val HEADER_BYTES = 8 + 4 + 4 + IHDR_LENGTH

    /** What the first chunk of a PNG says about the image. Compression, filter and interlace are
     *  read past but not carried: nothing on this seam has a question they answer. */
    data class Ihdr(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int)

    /**
     * The `IHDR` of [bytes], or **null** when this is not a PNG whose header can be trusted: too
     * short, the wrong signature, a first chunk that is not `IHDR`, an `IHDR` of the wrong declared
     * length, or non-positive dimensions. Never reads past `bytes.size`.
     */
    fun parse(bytes: ByteArray): Ihdr? {
        if (bytes.size < HEADER_BYTES) return null
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
        if (int32(bytes, 8) != IHDR_LENGTH) return null
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = int32(bytes, 16)
        val height = int32(bytes, 20)
        // A PNG's dimensions are unsigned 31-bit and never zero; a negative here is a width past
        // 2^31 read into an Int, which is the same answer either way — this is not a PNG we read.
        if (width <= 0 || height <= 0) return null
        return Ihdr(width, height, bitDepth = bytes[24].toInt() and 0xFF, colorType = bytes[25].toInt() and 0xFF)
    }

    /** [bytes]' image size, or null when [parse] says it cannot read the header. */
    fun size(bytes: ByteArray): Pair<Int, Int>? = parse(bytes)?.let { it.width to it.height }

    /** Whether [bytes] is a PNG of exactly [width] × [height] — the host's save guard and the
     *  screen's load guard, one sentence in one place. */
    fun matches(bytes: ByteArray, width: Int, height: Int): Boolean {
        val ihdr = parse(bytes) ?: return false
        return ihdr.width == width && ihdr.height == height
    }

    /** Big-endian unsigned 32-bit at [at], as an Int. The caller has already bounds-checked. */
    private fun int32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
}
