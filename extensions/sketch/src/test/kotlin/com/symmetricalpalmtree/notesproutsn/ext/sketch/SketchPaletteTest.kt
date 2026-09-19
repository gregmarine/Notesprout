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
 * purpose: the offered levels are a subset of the sixteen-level ladder (the whole ladder since arc
 * 46), and these tests are what prove the promise that changing the subset is one line and strands
 * nothing.
 */
class SketchPaletteTest {

    @Test fun `all sixteen ladder levels are offered, black to white`() {
        assertEquals((0..15).toList(), SketchPalette.SHADE_LEVELS)
        assertEquals(0xFF000000.toInt(), SketchPalette.shade(0))
        assertEquals(0xFF555555.toInt(), SketchPalette.shade(5))
        assertEquals(0xFF999999.toInt(), SketchPalette.shade(9))
        assertEquals(0xFFFFFFFF.toInt(), SketchPalette.shade(15))
        assertEquals(0, SketchPalette.BLACK_SHADE)
        assertEquals(15, SketchPalette.WHITE_SHADE)
        assertTrue(SketchPalette.isShade(SketchPalette.BLACK_SHADE))
        assertTrue(SketchPalette.isShade(SketchPalette.WHITE_SHADE))
    }

    @Test fun `every offered level is its ladder grey - level times 0x11, opaque`() {
        SketchPalette.SHADE_LEVELS.forEach { level ->
            val v = level * 0x11
            assertEquals((0xFF shl 24) or (v shl 16) or (v shl 8) or v, SketchPalette.shade(level))
        }
        assertEquals(16, SketchPalette.SHADE_LEVELS.distinct().size)
        assertEquals(SketchPalette.SHADE_LEVELS.sorted(), SketchPalette.SHADE_LEVELS)
    }

    @Test fun `the defaults are level 5 for the pencil and black for the pen`() {
        assertEquals(5, SketchPalette.DEFAULT_SHADE)
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
        assertEquals(0xFF999999.toInt(), SketchPalette.shade(9, fallback = 0))
    }

    @Test fun `the rows wrap at ROW_BREAK and cover every level once`() {
        assertEquals(8, SketchPalette.ROW_BREAK)
        val rows = SketchPalette.shadeRows()
        assertEquals(listOf((0..7).toList(), (8..15).toList()), rows)
        rows.forEach { assertTrue(it.size <= SketchPalette.ROW_BREAK) }
        assertEquals(SketchPalette.SHADE_LEVELS, rows.flatten())
    }

    @Test fun `there is one pencil width and one pen width`() {
        assertEquals(4f, SketchPalette.PENCIL_WIDTH_PX, 0f)
        assertEquals(5f, SketchPalette.PEN_WIDTH_PX, 0f)
    }
}
