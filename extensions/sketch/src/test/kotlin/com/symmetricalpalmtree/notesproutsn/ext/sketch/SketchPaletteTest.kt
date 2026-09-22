package com.symmetricalpalmtree.notesproutsn.ext.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette (arc 44 / T3, remade by arc 46 "Palette"): the ladder's arithmetic, which of its
 * levels this build offers, the rows they wrap into, the two widths, and the out-of-range fallback
 * that makes a remembered **ladder level** safe across a palette that has changed.
 *
 * Everything here is written against [SketchPalette.SHADE_LEVELS] rather than against a count, on
 * purpose: the offered levels are a subset of Atelier's sixteen tones (all of them since arc 46),
 * and these tests are what prove the promise that changing the subset is one line and strands
 * nothing.
 */
class SketchPaletteTest {

    @Test fun `all sixteen of Atelier's tones are offered, black to white`() {
        assertEquals((0..15).toList(), SketchPalette.SHADE_LEVELS)
        // The user's list of 2026-09-19, verbatim, darkest first.
        val atelier = listOf(
            "000000", "505050", "606060", "686868", "707070", "808080", "888888", "909090",
            "a0a0a0", "aaaaaa", "b6b6b6", "c0c0c0", "c8c8c8", "d0d0d0", "dddddd", "ffffff",
        )
        assertEquals(atelier, SketchPalette.TONES.map { "%06x".format(it and 0xFFFFFF) })
        assertEquals(0xFF000000.toInt(), SketchPalette.shade(0))
        assertEquals(0xFF505050.toInt(), SketchPalette.shade(1))
        assertEquals(0xFF808080.toInt(), SketchPalette.shade(5))
        assertEquals(0xFFFFFFFF.toInt(), SketchPalette.shade(15))
        assertEquals(0, SketchPalette.BLACK_SHADE)
        assertEquals(15, SketchPalette.WHITE_SHADE)
        assertTrue(SketchPalette.isShade(SketchPalette.BLACK_SHADE))
        assertTrue(SketchPalette.isShade(SketchPalette.WHITE_SHADE))
    }

    @Test fun `every offered level is its tone - opaque, a neutral grey, strictly lighter up the list`() {
        SketchPalette.SHADE_LEVELS.forEach { level ->
            val tone = SketchPalette.shade(level)
            assertEquals(0xFF, tone ushr 24)
            val r = tone ushr 16 and 0xFF
            assertEquals(r, tone ushr 8 and 0xFF)
            assertEquals(r, tone and 0xFF)
            if (level > 0) assertTrue(r > (SketchPalette.shade(level - 1) and 0xFF))
        }
        assertEquals(16, SketchPalette.SHADE_LEVELS.distinct().size)
        assertEquals(SketchPalette.SHADE_LEVELS.sorted(), SketchPalette.SHADE_LEVELS)
    }

    @Test fun `the defaults are 505050 for the pencil and black for the pen`() {
        assertEquals(1, SketchPalette.DEFAULT_SHADE)
        assertEquals(0xFF505050.toInt(), SketchPalette.shade(SketchPalette.DEFAULT_SHADE))
        assertEquals(0, SketchPalette.DEFAULT_PEN_SHADE)
        assertTrue(SketchPalette.isShade(SketchPalette.DEFAULT_SHADE))
        assertTrue(SketchPalette.isShade(SketchPalette.DEFAULT_PEN_SHADE))
    }

    @Test fun `off the ladder is not a shade`() {
        assertFalse(SketchPalette.isShade(-1))
        assertFalse(SketchPalette.isShade(16))
        assertFalse(SketchPalette.isShade(255))
    }

    @Test fun `a level this build does not offer reads as the fallback, and the fallback is the kind's`() {
        assertEquals(SketchPalette.shade(SketchPalette.DEFAULT_SHADE), SketchPalette.shade(16))
        assertEquals(SketchPalette.shade(SketchPalette.DEFAULT_SHADE), SketchPalette.shade(-1))
        assertEquals(SketchPalette.shade(SketchPalette.DEFAULT_SHADE), SketchPalette.shade(255))
        assertEquals(0xFF000000.toInt(), SketchPalette.shade(255, fallback = SketchPalette.DEFAULT_PEN_SHADE))
        // An offered level ignores the fallback entirely.
        assertEquals(0xFFAAAAAA.toInt(), SketchPalette.shade(9, fallback = 0))
    }

    @Test fun `the rows are four by four, white first, and cover every level once`() {
        assertEquals(4, SketchPalette.ROW_BREAK)
        val rows = SketchPalette.shadeRows()
        assertEquals(listOf(listOf(15, 14, 13, 12), listOf(11, 10, 9, 8), listOf(7, 6, 5, 4), listOf(3, 2, 1, 0)), rows)
        rows.forEach { assertTrue(it.size <= SketchPalette.ROW_BREAK) }
        assertEquals(SketchPalette.SHADE_LEVELS.toSet(), rows.flatten().toSet())
        assertEquals(16, rows.flatten().size)
    }

    @Test fun `there is one pencil width and one pen width`() {
        assertEquals(2f, SketchPalette.PENCIL_WIDTH_PX, 0f)
        assertEquals(5f, SketchPalette.PEN_WIDTH_PX, 0f)
    }
}
