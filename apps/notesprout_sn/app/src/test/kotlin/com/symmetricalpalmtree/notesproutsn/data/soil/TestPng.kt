package com.symmetricalpalmtree.notesproutsn.data.soil

/**
 * A PNG **header** built by hand (arc 43 / K3) — the fixture behind every sketch suite on this
 * side of the seam, and the reason those suites need no Android at all.
 *
 * Nothing here encodes an image: [png] is a valid signature + `IHDR` followed by as many filler
 * bytes as the caller asked for, which is exactly what the host's rules look at. The guard reads
 * the first 33 bytes ([com.symmetricalpalmtree.notesproutsn.extension.PngHeader]) and the cap reads
 * `size`; a real deflate stream would prove nothing more and would put an Android encoder in the
 * middle of a laptop test.
 */
object TestPng {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** A PNG declaring [width] × [height], padded with filler to at least [totalBytes]. */
    fun png(width: Int, height: Int, totalBytes: Int = 0, filler: Byte = 0x11): ByteArray {
        val head = SIGNATURE +
            be32(13) + "IHDR".toByteArray(Charsets.US_ASCII) +
            be32(width) + be32(height) +
            byteArrayOf(8, 6, 0, 0, 0)          // 8-bit, RGBA, deflate, adaptive filter, no interlace
        val pad = (totalBytes - head.size).coerceAtLeast(0)
        return head + ByteArray(pad) { filler }
    }

    /** Bytes that are not a PNG at all — the "no header" half of the guard. */
    fun notAPng(size: Int = 64): ByteArray = ByteArray(size) { 0x7F }

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )
}
