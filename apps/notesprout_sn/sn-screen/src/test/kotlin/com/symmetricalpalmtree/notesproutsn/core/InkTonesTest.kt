package com.symmetricalpalmtree.notesproutsn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sixteen greys (arc 49 / P4 — moved here from `:ext-sketch`'s `SketchPaletteTest`, where the
 * same pins guard the sketch face's reading of them): Atelier's list verbatim, the levels this
 * build offers, the rows they wrap into, and the out-of-range fold that makes a remembered level
 * safe across a palette that has changed.
 */
class InkTonesTest {

    @Test fun `all sixteen of Atelier's tones are offered, black to white`() {
        assertEquals((0..15).toList(), InkTones.LEVELS)
        // The user's list of 2026-09-19, verbatim, darkest first.
        val atelier = listOf(
            "000000", "505050", "606060", "686868", "707070", "808080", "888888", "909090",
            "a0a0a0", "aaaaaa", "b6b6b6", "c0c0c0", "c8c8c8", "d0d0d0", "dddddd", "ffffff",
        )
        assertEquals(atelier, InkTones.TONES.map { "%06x".format(it and 0xFFFFFF) })
        assertEquals(0xFF000000.toInt(), InkTones.tone(0))
        assertEquals(0xFF505050.toInt(), InkTones.tone(1))
        assertEquals(0xFF808080.toInt(), InkTones.tone(5))
        assertEquals(0xFFFFFFFF.toInt(), InkTones.tone(15))
        assertEquals(0, InkTones.BLACK)
        assertEquals(15, InkTones.WHITE)
        assertTrue(InkTones.isLevel(InkTones.BLACK))
        assertTrue(InkTones.isLevel(InkTones.WHITE))
    }

    @Test fun `every level is its tone - opaque, a neutral grey, strictly lighter up the list`() {
        InkTones.LEVELS.forEach { level ->
            val tone = InkTones.tone(level)
            assertEquals(0xFF, tone ushr 24)
            val r = tone ushr 16 and 0xFF
            assertEquals(r, tone ushr 8 and 0xFF)
            assertEquals(r, tone and 0xFF)
            if (level > 0) assertTrue(r > (InkTones.tone(level - 1) and 0xFF))
        }
        assertEquals(16, InkTones.LEVELS.distinct().size)
        assertEquals(InkTones.LEVELS.sorted(), InkTones.LEVELS)
    }

    @Test fun `off the ladder is not a level`() {
        assertFalse(InkTones.isLevel(-1))
        assertFalse(InkTones.isLevel(16))
        assertFalse(InkTones.isLevel(255))
    }

    @Test fun `a level this build does not offer folds onto the fallback, and the fallback onto black`() {
        assertEquals(InkTones.tone(InkTones.BLACK), InkTones.tone(16))
        assertEquals(InkTones.tone(1), InkTones.tone(-1, fallback = 1))
        assertEquals(InkTones.tone(1), InkTones.tone(255, fallback = 1))
        // An offered level ignores the fallback entirely.
        assertEquals(0xFFAAAAAA.toInt(), InkTones.tone(9, fallback = 1))
        // A fallback off the ladder is black — the answer can never be off the ladder.
        assertEquals(0xFF000000.toInt(), InkTones.tone(99, fallback = 99))
        assertEquals(InkTones.BLACK, InkTones.levelOrElse(99, fallback = 99))
        assertEquals(1, InkTones.levelOrElse(16, fallback = 1))
        assertEquals(9, InkTones.levelOrElse(9, fallback = 1))
        assertEquals(InkTones.BLACK, InkTones.levelOrElse(-1))
    }

    @Test fun `the rows are four by four, white first, and cover every level once`() {
        assertEquals(4, InkTones.ROW_BREAK)
        val rows = InkTones.rows()
        assertEquals(listOf(listOf(15, 14, 13, 12), listOf(11, 10, 9, 8), listOf(7, 6, 5, 4), listOf(3, 2, 1, 0)), rows)
        assertEquals(InkTones.LEVELS.toSet(), rows.flatten().toSet())
        assertEquals(16, rows.flatten().size)
    }

    @Test fun `every tone survives the stroke row's colour token unchanged`() {
        // The pen's grey travels in the stroke's `color` column through InkColorCodec: a tone that
        // came back different would be a page that re-renders lighter or darker than it was drawn.
        InkTones.LEVELS.forEach { level ->
            val tone = InkTones.tone(level)
            assertEquals(tone, InkColorCodec.decode(InkColorCodec.encode(tone)))
        }
    }
}
