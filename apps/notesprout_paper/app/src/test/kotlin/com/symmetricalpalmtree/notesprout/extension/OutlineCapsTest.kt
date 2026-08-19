package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineCapsTest {

    @Test
    fun chunksByCount() {
        val n = ExtensionContract.MAX_OUTLINE_BATCH * 2 + 5
        val chunks = OutlineCaps.chunk(List(n) { "p$it" })
        assertEquals(3, chunks.size)
        assertEquals(ExtensionContract.MAX_OUTLINE_BATCH, chunks[0].size)
        assertEquals(ExtensionContract.MAX_OUTLINE_BATCH, chunks[1].size)
        assertEquals(5, chunks[2].size)
        assertEquals(List(n) { "p$it" }, chunks.flatten())   // order preserved
    }

    @Test
    fun chunksByChars() {
        val big = "x".repeat(ExtensionContract.MAX_OUTLINE_BATCH_CHARS / 2 + 1)   // two never fit together
        val chunks = OutlineCaps.chunk(listOf(big, big, big))
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun overLongPayloadIsOwnTruncatedChunk() {
        val huge = "y".repeat(ExtensionContract.MAX_OUTLINE_BATCH_CHARS + 10)
        val chunks = OutlineCaps.chunk(listOf("a", huge, "b"))
        assertEquals(3, chunks.size)
        assertEquals(listOf("a"), chunks[0])
        assertEquals(ExtensionContract.MAX_OUTLINE_BATCH_CHARS, chunks[1].single().length)
        assertEquals(listOf("b"), chunks[2])
    }

    @Test
    fun emptyInputNoChunks() {
        assertTrue(OutlineCaps.chunk(emptyList()).isEmpty())
    }

    @Test
    fun wrongLengthReplyIsNull() {
        assertNull(OutlineCaps.sanitize(null, 1))
        assertNull(OutlineCaps.sanitize(emptyList(), 1))                                  // the old-provider shape
        assertNull(OutlineCaps.sanitize(listOf(OutlineEntry("a", 1), OutlineEntry("b", 1)), 1))
        assertEquals(emptyList<OutlineCaps.Entry>(), OutlineCaps.sanitize(emptyList(), 0))
    }

    @Test
    fun blankLabelBecomesLevelZero() {
        val out = OutlineCaps.sanitize(listOf(OutlineEntry("   ", 2), OutlineEntry("", 0), null), 3)!!
        assertEquals(listOf(OutlineCaps.Entry("", 0), OutlineCaps.Entry("", 0), OutlineCaps.Entry("", 0)), out)
    }

    @Test
    fun levelClampAndLabelTrimCut() {
        // OutlineEntry's constructor already rejects level 7 / an over-long label at unmarshal; the
        // normaliser is exercised at its own edges: trim, and a level within range stays.
        val out = OutlineCaps.sanitize(listOf(OutlineEntry("  Meeting notes  ", 3), OutlineEntry("x", 6)), 2)!!
        assertEquals(OutlineCaps.Entry("Meeting notes", 3), out[0])
        assertEquals(OutlineCaps.Entry("x", 6), out[1])
        val long = "a".repeat(ExtensionContract.MAX_OUTLINE_LABEL_CHARS - 2) + "  "   // padded to the cap — trims to cap − 2
        assertEquals(ExtensionContract.MAX_OUTLINE_LABEL_CHARS - 2, OutlineCaps.sanitize(listOf(OutlineEntry(long, 1)), 1)!![0].label.length)
    }

    @Test
    fun capableReply() {
        assertTrue(OutlineCaps.isCapableReply(listOf(OutlineEntry.NONE)))
        assertFalse(OutlineCaps.isCapableReply(emptyList()))
        assertFalse(OutlineCaps.isCapableReply(null))
        assertFalse(OutlineCaps.isCapableReply(listOf(OutlineEntry.NONE, OutlineEntry.NONE)))
    }
}
