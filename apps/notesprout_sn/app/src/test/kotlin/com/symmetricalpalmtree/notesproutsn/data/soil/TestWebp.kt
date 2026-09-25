package com.symmetricalpalmtree.notesproutsn.data.soil

/**
 * A WebP **header** built by hand (arc 45 / G2 — `TestPng`'s successor, arc 43 / K3) — the fixture
 * behind every sketch suite on this side of the seam, and the reason those suites need no Android
 * at all.
 *
 * Nothing here encodes an image: [webp] is a valid `RIFF`/`WEBP` container with a `VP8L` first
 * chunk declaring a size, followed by as many filler bytes as the caller asked for, which is
 * exactly what the host's rules look at. The guard reads the first 30 bytes
 * ([com.symmetricalpalmtree.notesproutsn.extension.ImageHeader]) and the cap reads `size`; a real
 * lossless bitstream would prove nothing more and would put an Android encoder in the middle of a
 * laptop test. The container's shape is `ImageHeaderTest`'s `vp8l()` builder, copied rather than
 * shared because `:extension-api`'s test source set is not on this module's classpath.
 *
 * [filler] is what lets two "images" of the same size differ in bytes, which is what the
 * `updatedAt`-is-sacred rules are proved against.
 */
object TestWebp {

    /** The smallest container this builder can produce: 12 preamble + 8 chunk header + 5 payload. */
    const val MIN_BYTES = 25

    /** A lossless WebP declaring [width] × [height], padded with filler to at least [totalBytes]. */
    fun webp(width: Int, height: Int, totalBytes: Int = 0, filler: Byte = 0x11): ByteArray {
        // Bits 0–13 width−1, 14–27 height−1, 28 alpha_is_used, 29–31 version (0).
        val packed = ((width - 1) and 0x3FFF) or
            (((height - 1) and 0x3FFF) shl 14) or
            (1 shl 28)
        val payload = byteArrayOf(0x2F) + le32(packed)
        val tail = (totalBytes - (PREAMBLE + CHUNK_HEADER + payload.size)).coerceAtLeast(0)
        return "RIFF".ascii() +
            le32(4 + CHUNK_HEADER + payload.size + tail) +   // the RIFF size — read by nobody here
            "WEBP".ascii() +
            "VP8L".ascii() +
            le32(payload.size) +
            payload +
            ByteArray(tail) { filler }
    }

    /**
     * An **extended** (`VP8X`) WebP declaring [width] × [height] — the header a lossy WebP with
     * alpha carries (arc 51 / J2's guide image), padded with filler to at least [totalBytes].
     * Flags say alpha; the sub-chunks that would follow are filler, since the guard reads the
     * first chunk only.
     */
    fun webpX(width: Int, height: Int, totalBytes: Int = 0, filler: Byte = 0x22): ByteArray {
        val payload = byteArrayOf(0x10, 0, 0, 0) + le24(width - 1) + le24(height - 1)
        val tail = (totalBytes - (PREAMBLE + CHUNK_HEADER + payload.size)).coerceAtLeast(0)
        return "RIFF".ascii() +
            le32(4 + CHUNK_HEADER + payload.size + tail) +
            "WEBP".ascii() +
            "VP8X".ascii() +
            le32(payload.size) +
            payload +
            ByteArray(tail) { filler }
    }

    /** Bytes that are not a WebP at all — the "no header" half of the guard. A PNG is one of these
     *  as far as this app is now concerned (decision 4: no legacy, no sniffing). */
    fun notAWebp(size: Int = 64): ByteArray = ByteArray(size) { 0x7F }

    /** A real PNG signature + `IHDR` of the given size — an arc-43 sketch row as a device that ran
     *  an older build would still hold one. It must fail every guard in this app. */
    fun legacyPng(width: Int, height: Int, totalBytes: Int = 0): ByteArray {
        val head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            be32(13) + "IHDR".ascii() +
            be32(width) + be32(height) +
            byteArrayOf(8, 6, 0, 0, 0)          // 8-bit, RGBA, deflate, adaptive filter, no interlace
        val pad = (totalBytes - head.size).coerceAtLeast(0)
        return head + ByteArray(pad) { 0x11 }
    }

    private const val PREAMBLE = 12
    private const val CHUNK_HEADER = 8

    private fun String.ascii() = toByteArray(Charsets.US_ASCII)

    private fun le32(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}
