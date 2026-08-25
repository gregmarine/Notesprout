package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chunking rule both sides share — every chunk it produces must satisfy [InkBundle.requireValid]. */
class InkChunksTest {

    private fun stroke(n: Int) = WireStroke(
        FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), 3f, -0x1000000, "PEN",
    )

    private fun assertEveryChunkValid(chunks: List<List<WireStroke>>) {
        for (c in chunks) InkBundle(c, 0f, 0f)   // throws if the chunk breaks a cap
    }

    @Test
    fun emptyInEmptyOut() {
        assertEquals(0, InkChunks.chunk(emptyList()).size)
    }

    @Test
    fun oneChunkWhenItFits() {
        val chunks = InkChunks.chunk(List(10) { stroke(5) })
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size)
        assertEveryChunkValid(chunks)
    }

    @Test
    fun splitsOnTheStrokeCap() {
        val n = ExtensionContract.TRANSFER_CHUNK_STROKES + 1
        val chunks = InkChunks.chunk(List(n) { stroke(1) })
        assertEquals(2, chunks.size)
        assertEquals(ExtensionContract.TRANSFER_CHUNK_STROKES, chunks[0].size)
        assertEquals(1, chunks[1].size)
        assertEveryChunkValid(chunks)
    }

    @Test
    fun splitsOnThePointCap() {
        val per = ExtensionContract.TRANSFER_CHUNK_POINTS / 3
        val chunks = InkChunks.chunk(List(4) { stroke(per) })
        assertEquals(2, chunks.size)
        assertEquals(3, chunks[0].size)
        assertEveryChunkValid(chunks)
    }

    @Test
    fun neverSplitsASingleStroke() {
        // An oversize stroke becomes its own chunk, whole; nothing is dropped and nothing is cut.
        val strokes = listOf(stroke(2), stroke(ExtensionContract.TRANSFER_CHUNK_POINTS + 10), stroke(2))
        val chunks = InkChunks.chunk(strokes)
        assertTrue(chunks.any { it.size == 1 && it[0].size == ExtensionContract.TRANSFER_CHUNK_POINTS + 10 })
        assertEquals(strokes.size, chunks.sumOf { it.size })
        assertEveryChunkValid(chunks)
    }

    @Test
    fun theChunkBudgetCoversAStrokeCappedTransfer() {
        // The many-small-strokes shape: MAX_TRANSFER_STROKES strokes of one point each.
        val chunks = InkChunks.chunk(List(ExtensionContract.MAX_TRANSFER_STROKES) { stroke(1) })
        assertTrue(
            "chunk() produced ${chunks.size}, budget is ${ExtensionContract.TRANSFER_MAX_CHUNKS}",
            chunks.size <= ExtensionContract.TRANSFER_MAX_CHUNKS,
        )
        assertEveryChunkValid(chunks)
    }

    @Test
    fun theChunkBudgetCoversAPointCappedTransfer() {
        // The shape the stroke-only derivation missed (arc 11 / J6): a chunk also closes when the
        // NEXT stroke would cross the point cap, so strokes just over half of it go one per chunk.
        // 39 strokes of 10 001 points is inside both whole-transfer caps and chunks into 39 —
        // more than the old budget of 34, which made the host's drain call a legal transfer
        // truncated. The bound has to count point-driven closes too.
        val per = ExtensionContract.TRANSFER_CHUNK_POINTS / 2 + 1
        val count = ExtensionContract.MAX_TRANSFER_POINTS / per
        val chunks = InkChunks.chunk(List(count) { stroke(per) })
        assertEquals("one stroke per chunk is the point of this case", count, chunks.size)
        assertTrue(
            "chunk() produced ${chunks.size}, budget is ${ExtensionContract.TRANSFER_MAX_CHUNKS}",
            chunks.size <= ExtensionContract.TRANSFER_MAX_CHUNKS,
        )
        assertEveryChunkValid(chunks)
    }

    @Test
    fun theChunkBudgetCoversTheWorstMixedTransfer() {
        // Both split reasons in one transfer, at both caps: runs of 300 one-point strokes (a
        // stroke-driven close) alternating with one huge stroke (a point-driven close).
        val huge = ExtensionContract.TRANSFER_CHUNK_POINTS / 2 + 1
        val strokes = ArrayList<WireStroke>()
        var points = 0
        while (strokes.size + ExtensionContract.TRANSFER_CHUNK_STROKES + 1 <= ExtensionContract.MAX_TRANSFER_STROKES &&
            points + ExtensionContract.TRANSFER_CHUNK_STROKES + huge <= ExtensionContract.MAX_TRANSFER_POINTS
        ) {
            repeat(ExtensionContract.TRANSFER_CHUNK_STROKES) { strokes += stroke(1) }
            strokes += stroke(huge)
            points += ExtensionContract.TRANSFER_CHUNK_STROKES + huge
        }
        val chunks = InkChunks.chunk(strokes)
        assertTrue(
            "chunk() produced ${chunks.size}, budget is ${ExtensionContract.TRANSFER_MAX_CHUNKS}",
            chunks.size <= ExtensionContract.TRANSFER_MAX_CHUNKS,
        )
        assertEveryChunkValid(chunks)
    }
}
