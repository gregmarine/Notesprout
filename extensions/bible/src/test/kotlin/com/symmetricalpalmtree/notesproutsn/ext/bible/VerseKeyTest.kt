package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical verse key (arc 37 / B1). These numbers are written by
 * `tools/bible/build_bible_db.py`, so a change here is a change to the database
 * — the round trips below are what pins the two together.
 */
class VerseKeyTest {

    @Test
    fun `encodes the canonical packing`() {
        // John 3:16 — John is ordinal 43.
        assertEquals(43_003_016, VerseKey.encode(43, 3, 16))
        assertEquals(1_001_001, VerseKey.encode(1, 1, 1))
        assertEquals(66_022_021, VerseKey.encode(66, 22, 21))
    }

    @Test
    fun `decodes what it encoded`() {
        val key = VerseKey.encode(19, 119, 176)
        assertEquals(19, VerseKey.ordinalOf(key))
        assertEquals(119, VerseKey.chapterOf(key))
        assertEquals(176, VerseKey.verseOf(key))
    }

    @Test
    fun `keys sort in reading order`() {
        val genesis = VerseKey.encode(1, 50, 26)
        val psalm = VerseKey.encode(19, 1, 1)
        val revelation = VerseKey.encode(66, 1, 1)
        assertTrue(genesis < psalm)
        assertTrue(psalm < revelation)
    }

    @Test
    fun `chapter bounds cover every verse of that chapter and nothing else`() {
        val (lo, hi) = VerseKey.chapterBounds(19, 23)
        assertTrue(VerseKey.encode(19, 23, 1) in lo..hi)
        assertTrue(VerseKey.encode(19, 23, VerseKey.MAX_VERSE) in lo..hi)
        assertFalse(VerseKey.encode(19, 22, 31) in lo..hi)
        assertFalse(VerseKey.encode(19, 24, 1) in lo..hi)
    }

    @Test
    fun `a verse range contains only its own span`() {
        val range = VerseRange.verses(43, 3, 16, 18)
        assertTrue(range.contains(VerseKey.encode(43, 3, 17)))
        assertFalse(range.contains(VerseKey.encode(43, 3, 19)))
    }
}
