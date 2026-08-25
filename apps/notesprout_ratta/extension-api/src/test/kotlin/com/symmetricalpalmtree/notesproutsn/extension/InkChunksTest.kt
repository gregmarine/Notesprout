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
    fun theChunkBudgetCoversAFullTransfer() {
        // TRANSFER_MAX_CHUNKS is what the host drains; it must be ceil(MAX_STROKES / CHUNK_STROKES)
        // or a legal maximum transfer would be silently truncated at the far end.
        val needed = (ExtensionContract.MAX_TRANSFER_STROKES + ExtensionContract.TRANSFER_CHUNK_STROKES - 1) /
            ExtensionContract.TRANSFER_CHUNK_STROKES
        assertEquals(needed, ExtensionContract.TRANSFER_MAX_CHUNKS)
    }
}
