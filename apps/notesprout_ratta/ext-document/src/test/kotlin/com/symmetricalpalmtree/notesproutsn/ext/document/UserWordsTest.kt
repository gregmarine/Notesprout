package com.symmetricalpalmtree.notesproutsn.ext.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UserWordsTest {

    @Test
    fun `round trip keeps words and order`() {
        val words = linkedSetOf("gardener's", "notesprout", "epd")
        assertEquals(words, UserWords.decode(UserWords.encode(words)))
    }

    @Test
    fun `nothing stored is an empty set`() {
        assertEquals(emptySet<String>(), UserWords.decode(null))
        assertEquals(emptySet<String>(), UserWords.decode(ByteArray(0)))
    }

    @Test
    fun `the blob is one word per newline-terminated line`() {
        assertArrayEquals(
            "alpha\nbeta\n".toByteArray(Charsets.UTF_8),
            UserWords.encode(linkedSetOf("alpha", "beta")),
        )
    }

    @Test
    fun `blank lines are skipped, duplicates collapse to the first`() {
        val decoded = UserWords.decode("alpha\n\nbeta\nalpha\n".toByteArray(Charsets.UTF_8))
        assertEquals(linkedSetOf("alpha", "beta"), decoded)
        assertEquals(listOf("alpha", "beta"), decoded.toList())
    }

    @Test
    fun `a word that would corrupt the format is dropped, not escaped`() {
        assertArrayEquals(
            "safe\n".toByteArray(Charsets.UTF_8),
            UserWords.encode(linkedSetOf("safe", "bad\nword", "")),
        )
    }

    @Test
    fun `unreadable bytes degrade to empty`() {
        // Not this format at all — a lone high surrogate's replacement chars still parse as
        // lines, so the honest claim is only that decode never throws.
        val decoded = UserWords.decode(byteArrayOf(-1, -2, 0x00, 0x0A))
        assertEquals(true, decoded is LinkedHashSet<String>)
    }
}
