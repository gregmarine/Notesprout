package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The contract's chunking rule for a sketch's PNG (arc 43 / K2), written once for both sides — the
 * [TextChunks] recipe applied to **bytes**, and simpler than it: bytes have no surrogate pairs, so
 * every chunk but the last is exactly [SketchContract.SKETCH_CHUNK_BYTES] and [join] is plain
 * concatenation.
 *
 * **An empty image is one empty chunk**, not zero chunks: clearing a page's sketch crosses as a
 * single zero-length chunk with `last = true`, so "save blank" rides the same shape as every other
 * save and the host's accumulator has one path, not two (the ashmem empty-value lesson: an empty
 * value is a value, not an absence — the host answers it by soft-deleting the row).
 *
 * [countFor] is the same arithmetic without the copying, so a [SketchPageState] can pin the
 * relation between its `sketchBytes` and its `sketchChunks` without ever holding the pixels.
 *
 * Pure; both sides pin it by test. Nothing here logs — a byte count is the most it ever knows.
 */
object ByteChunks {

    /** Split [bytes] into 1..[SketchContract.MAX_CHUNKS] chunks. `join` is `concat`. */
    fun chunk(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        val size = SketchContract.SKETCH_CHUNK_BYTES
        val out = ArrayList<ByteArray>(countFor(bytes.size))
        var start = 0
        while (start < bytes.size) {
            val end = minOf(start + size, bytes.size)
            out += bytes.copyOfRange(start, end)
            start = end
        }
        return out
    }

    /** How many chunks [byteCount] bytes make — **1** for an empty image (the empty-chunk rule). */
    fun countFor(byteCount: Int): Int {
        if (byteCount <= 0) return 1
        val size = SketchContract.SKETCH_CHUNK_BYTES
        return (byteCount + size - 1) / size
    }

    /** Reassemble what [chunk] produced. A list of one empty chunk gives an empty array back. */
    fun join(chunks: List<ByteArray>): ByteArray {
        var total = 0
        for (c in chunks) total += c.size
        val out = ByteArray(total)
        var at = 0
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }
}
