package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte chunking rule both sides share (arc 43 / K2) — join is concatenation, always. */
class ByteChunksTest {

    private val cap = SketchContract.SKETCH_CHUNK_BYTES

    /** Bytes that are not all the same, so a mis-ordered join cannot pass by luck. */
    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun emptyBytesAreOneEmptyChunk() {
        val chunks = ByteChunks.chunk(ByteArray(0))
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].size)
    }

    @Test
    fun theEmptyCountIsOneNotZero() {
        // An empty value is a value, not an absence — a clear crosses as one empty chunk.
        assertEquals(1, ByteChunks.countFor(0))
        // A negative is nonsense a state could still be handed; it answers the empty shape.
        assertEquals(1, ByteChunks.countFor(-5))
    }

    @Test
    fun shortBytesAreOneChunk() {
        val b = bytes(9)
        val chunks = ByteChunks.chunk(b)
        assertEquals(1, chunks.size)
        assertArrayEquals(b, chunks[0])
        assertEquals(1, ByteChunks.countFor(9))
    }

    @Test
    fun exactCapIsOneChunk() {
        val b = bytes(cap)
        val chunks = ByteChunks.chunk(b)
        assertEquals(1, chunks.size)
        assertEquals(cap, chunks[0].size)
        assertEquals(1, ByteChunks.countFor(cap))
    }

    @Test
    fun oneOverTheCapIsTwoChunks() {
        val b = bytes(cap + 1)
        val chunks = ByteChunks.chunk(b)
        assertEquals(2, chunks.size)
        assertEquals(cap, chunks[0].size)
        assertEquals(1, chunks[1].size)
        assertEquals(2, ByteChunks.countFor(cap + 1))
    }

    @Test
    fun everyChunkButTheLastIsFull() {
        val chunks = ByteChunks.chunk(bytes(cap * 3 + 7))
        assertEquals(4, chunks.size)
        for (i in 0 until chunks.size - 1) assertEquals(cap, chunks[i].size)
        assertEquals(7, chunks.last().size)
    }

    @Test
    fun joinIsAlwaysTheIdentity() {
        val cases = listOf(0, 1, 9, cap - 1, cap, cap + 1, cap * 2, cap * 3 + 7)
        for (n in cases) {
            val b = bytes(n)
            assertArrayEquals("n=$n", b, ByteChunks.join(ByteChunks.chunk(b)))
        }
    }

    @Test
    fun countForAgreesWithTheChunkerEverywhere() {
        // The relation SketchPageState's `require` pins: the count is derivable, so it must be.
        val cases = listOf(0, 1, 9, cap - 1, cap, cap + 1, cap * 2, cap * 3 + 7)
        for (n in cases) assertEquals("n=$n", ByteChunks.chunk(bytes(n)).size, ByteChunks.countFor(n))
    }

    @Test
    fun theWholeCapStaysInsideTheChunkBound() {
        // A PNG at MAX_BYTES must be servable — the state's `sketchChunks` range depends on it.
        val chunks = ByteChunks.chunk(bytes(SketchContract.MAX_BYTES))
        assertTrue(chunks.size <= SketchContract.MAX_CHUNKS)
        for (c in chunks) assertTrue(c.size <= cap)
        assertEquals(SketchContract.MAX_BYTES, chunks.sumOf { it.size })
    }

    @Test
    fun joiningNothingIsAnEmptyArray() {
        assertEquals(0, ByteChunks.join(emptyList()).size)
        assertEquals(0, ByteChunks.join(listOf(ByteArray(0))).size)
    }
}
