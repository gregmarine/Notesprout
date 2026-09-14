package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored last-read position (arc 37 / B2, decision 7): its wire form, and the rule that
 * **anything unreadable is Genesis 1** rather than an error the user has to answer.
 */
class PositionTest {

    @Test
    fun `a position round-trips through its wire form`() {
        val at = Position("PSA", 119, 176)
        assertEquals("PSA:119:176", at.encode())
        assertEquals(at, Position.decode(at.encode()))
        assertEquals("GEN:1:1", Position.GENESIS_1.encode())
        assertEquals(Position.GENESIS_1, Position.decode("GEN:1:1"))
    }

    @Test
    fun `the first ever open is Genesis 1`() {
        assertEquals("GEN", Position.GENESIS_1.usfm)
        assertEquals(1, Position.GENESIS_1.chapter)
        assertEquals(1, Position.GENESIS_1.verse)
    }

    @Test
    fun `a lowercase book code decodes to the canonical one`() {
        assertEquals(Position("JHN", 3, 16), Position.decode("jhn:3:16"))
    }

    @Test
    fun `every malformed shape decodes to null`() {
        for (raw in listOf(
            null, // nothing stored yet
            "", // an empty row
            "GEN", // no chapter, no verse
            "GEN:1", // too few fields
            "GEN:1:1:1", // too many
            "GEN:one:1", // a chapter that is not a number
            "GEN:1:one", // a verse that is not a number
            "NOPE:1:1", // not a book
            "GEN:0:1", // chapters are 1-based
            "GEN:1:0", // so are verses
            "GEN:-1:1",
            "GEN:1:-1",
            " GEN:1:1", // whitespace is not trimmed away for us
        )) {
            assertNull("decoded <$raw>", Position.decode(raw))
        }
    }
}
