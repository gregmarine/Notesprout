package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text chunking rule both sides share (arc 19 / M3) — join is concatenation, always. */
class TextChunksTest {

    @Test
    fun emptyTextIsOneEmptyChunk() {
        assertEquals(listOf(""), TextChunks.chunk(""))
    }

    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("hello"), TextChunks.chunk("hello"))
    }

    @Test
    fun exactCapIsOneChunk() {
        val text = "a".repeat(DocumentContract.TEXT_CHUNK_CHARS)
        assertEquals(listOf(text), TextChunks.chunk(text))
    }

    @Test
    fun oneOverTheCapIsTwoChunks() {
        val text = "a".repeat(DocumentContract.TEXT_CHUNK_CHARS + 1)
        val chunks = TextChunks.chunk(text)
        assertEquals(2, chunks.size)
        assertEquals(DocumentContract.TEXT_CHUNK_CHARS, chunks[0].length)
        assertEquals(1, chunks[1].length)
    }

    @Test
    fun joinIsAlwaysTheIdentity() {
        val texts = listOf(
            "",
            "short",
            "a".repeat(DocumentContract.TEXT_CHUNK_CHARS * 3 + 7),
            "🌱".repeat(DocumentContract.TEXT_CHUNK_CHARS),   // 2 chars per emoji
        )
        for (t in texts) assertEquals(t.length, TextChunks.chunk(t).joinToString("").length)
        for (t in texts) assertEquals(t, TextChunks.chunk(t).joinToString(""))
    }

    @Test
    fun neverSplitsASurrogatePair() {
        // A pair straddling the cap boundary: chars [CAP-1] and [CAP] form one emoji.
        val text = "a".repeat(DocumentContract.TEXT_CHUNK_CHARS - 1) + "🌱" + "b".repeat(10)
        val chunks = TextChunks.chunk(text)
        assertEquals(DocumentContract.TEXT_CHUNK_CHARS - 1, chunks[0].length)   // backed off one
        for (c in chunks) {
            if (c.isNotEmpty()) {
                assertTrue("chunk ends on a lone high surrogate", !c.last().isHighSurrogate())
                assertTrue("chunk starts on a lone low surrogate", !c.first().isLowSurrogate())
            }
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun everyChunkWithinTheCapAndCountWithinTheBound() {
        val text = "🌱".repeat(DocumentContract.MAX_DOCUMENT_CHARS / 2)   // exactly the char cap
        val chunks = TextChunks.chunk(text)
        assertTrue(chunks.size <= DocumentContract.TEXT_MAX_CHUNKS)
        for (c in chunks) assertTrue(c.length <= DocumentContract.TEXT_CHUNK_CHARS)
        assertEquals(text.length, chunks.sumOf { it.length })
    }
}
