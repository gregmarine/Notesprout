package com.symmetricalpalmtree.notesproutsn.ext.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caret LRU as a persistence format. Two things are worth pinning here and nothing else is:
 * that the codec survives whatever comes back out of the store, and that eviction throws away the
 * page nobody is working on rather than the one that is open.
 */
class CaretMemoryTest {

    private fun blob(map: Map<String, Int>) = CaretMemory.encode(map)

    @Test
    fun `a map survives a round trip in order`() {
        val map = linkedMapOf("a" to 1, "b" to 22, "c" to 333)
        assertEquals(map, CaretMemory.decode(blob(map)))
        assertEquals(listOf("a", "b", "c"), CaretMemory.decode(blob(map)).keys.toList())
    }

    @Test
    fun `decode of null or empty is an empty map`() {
        assertTrue(CaretMemory.decode(null).isEmpty())
        assertTrue(CaretMemory.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `re-recording a key moves it to the back`() {
        val map = linkedMapOf("a" to 1, "b" to 2, "c" to 3)
        CaretMemory.record(map, "a", 9)
        assertEquals(listOf("b", "c", "a"), map.keys.toList())
        assertEquals(9, map["a"])
    }

    @Test
    fun `the oldest entry falls off past the limit and the rest keep their order`() {
        val map = LinkedHashMap<String, Int>()
        for (i in 0 until CaretMemory.LIMIT) CaretMemory.record(map, "page-$i", i)
        assertEquals(CaretMemory.LIMIT, map.size)

        CaretMemory.record(map, "page-new", 7)
        assertEquals(CaretMemory.LIMIT, map.size)
        assertEquals(null, map["page-0"])
        assertEquals(7, map["page-new"])
        assertEquals("page-1", map.keys.first())
        assertEquals("page-new", map.keys.last())
    }

    @Test
    fun `the page just written is the last one to be evicted`() {
        val map = LinkedHashMap<String, Int>()
        CaretMemory.record(map, "open", 5)
        for (i in 0 until CaretMemory.LIMIT - 1) CaretMemory.record(map, "other-$i", i)
        // Touching it again puts it at the back, so the next hundred writes go before it.
        CaretMemory.record(map, "open", 6)
        CaretMemory.record(map, "one-more", 0)
        assertEquals(6, map["open"])
    }

    @Test
    fun `a negative offset is clamped on both record and decode`() {
        val map = LinkedHashMap<String, Int>()
        CaretMemory.record(map, "a", -12)
        assertEquals(0, map["a"])
        assertEquals(0, CaretMemory.decode("a\t-12\n".toByteArray(Charsets.UTF_8))["a"])
    }

    @Test
    fun `bytes that are not this format decode to an empty map`() {
        assertTrue(CaretMemory.decode(byteArrayOf(0x7B, 0x22, 0x61, 0x22, 0x3A, 0x31, 0x7D)).isEmpty())
        assertTrue(CaretMemory.decode("nothing here at all".toByteArray()).isEmpty())
    }

    @Test
    fun `a malformed line is skipped and the good ones are kept`() {
        val raw = "a\t1\nrubbish\n\tno-key\t3\nb\tnotanumber\nc\t4\nd\t\n"
        val decoded = CaretMemory.decode(raw.toByteArray(Charsets.UTF_8))
        assertEquals(linkedMapOf("a" to 1, "c" to 4), decoded)
    }

    @Test
    fun `a key carrying a separator is dropped on encode rather than corrupting the blob`() {
        val map = linkedMapOf("good" to 1, "bad\tkey" to 2, "bad\nkey" to 3, "" to 4)
        assertEquals("good\t1\n", blob(map).toString(Charsets.UTF_8))
        assertEquals(linkedMapOf("good" to 1), CaretMemory.decode(blob(map)))
    }

    @Test
    fun `the limit is one hundred`() {
        assertEquals(100, CaretMemory.LIMIT)
    }
}
