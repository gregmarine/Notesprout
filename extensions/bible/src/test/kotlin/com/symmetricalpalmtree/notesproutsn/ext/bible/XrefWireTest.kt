package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Test

/** A cross-reference's stored key range becomes the passage view's wire (arc 41). */
class XrefWireTest {

    @Test
    fun `a verse range is one book range on the wire`() {
        // 1 Peter 3's line, as the fixed builder stores it: Song of Solomon 1:1–17.
        assertEquals("SNG:1:1-1:17", XrefWire.of(22001001, 22001017))
    }

    @Test
    fun `a single verse`() {
        assertEquals("MAT:19:4-19:4", XrefWire.of(VerseKey.encode(40, 19, 4), VerseKey.encode(40, 19, 4)))
    }

    @Test
    fun `a whole-chapter target keeps the codec's own sentinels`() {
        val wire = XrefWire.of(VerseKey.encode(19, 38, 0), VerseKey.encode(19, 38, VerseKey.MAX_VERSE))
        assertEquals("PSA:38:0-38:999", wire)
        assertEquals("Psalms 38", ReferenceCodec.label(ReferenceCodec.decode(wire)!!))
    }

    @Test
    fun `a cross-chapter target decodes to the same range`() {
        val wire = XrefWire.of(VerseKey.encode(13, 15, 29), VerseKey.encode(13, 16, 3))
        assertEquals("1CH:15:29-16:3", wire)
        val decoded = ReferenceCodec.decode(wire)!!
        assertEquals(VerseKey.encode(13, 15, 29), decoded.single().ranges.single().startKey)
        assertEquals(VerseKey.encode(13, 16, 3), decoded.single().ranges.single().endKey)
    }
}
