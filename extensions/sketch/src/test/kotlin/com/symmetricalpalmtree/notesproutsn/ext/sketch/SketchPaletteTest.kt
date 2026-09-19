package com.symmetricalpalmtree.notesproutsn.ext.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pencil palette (arc 44 / T3): the ladder's arithmetic, which of its levels this build offers,
 * the rows they wrap into, and the out-of-range fallback that makes a remembered **ladder level**
 * safe across a palette that has changed.
 *
 * Everything here is written against [SketchPalette.SHADE_LEVELS] rather than against a count, on
 * purpose: the offered levels are a subset of the sixteen-level ladder (0, 5, 9 and 15 today — the
 * three rungs the Ratta preview hits exactly, plus white, the lightener), and these tests are what
 * prove the promise that changing the subset is one line and strands nothing.
 */
class SketchPaletteTest {

    @Test fun `the offered shades are the firmware's four tones - black, grey, light grey, white`() {
        assertEquals(listOf(0, 5, 9, 15), SketchPalette.SHADE_LEVELS)
        // The three the firmware previews exactly: BLACK, DARK_GRAY, GRAY.
        assertEquals(0xFF000000.toInt(), SketchPalette.shade(0))
        assertEquals(0xFF555555.toInt(), SketchPalette.shade(5))
        assertEquals(0xFF999999.toInt(), SketchPalette.shade(9))
        // …and white, the lead that lightens graphite rather than laying any.
        assertEquals(0xFFFFFFFF.toInt(), SketchPalette.shade(15))
        assertEquals(15, SketchPalette.WHITE_SHADE)
        assertTrue(SketchPalette.isShade(SketchPalette.WHITE_SHADE))
    }

    @Test fun `every offered level is its ladder grey - level times 0x11, opaque`() {
        SketchPalette.SHADE_LEVELS.forEach { level ->
            val v = level * 0x11
            assertEquals((0xFF shl 24) or (v shl 16) or (v shl 8) or v, SketchPalette.shade(level))
        }
    }

    @Test fun `the default shade is level 5 - the tone the hand approved`() {
        assertEquals(5, SketchPalette.DEFAULT_SHADE)
        assertTrue(SketchPalette.DEFAULT_SHADE in SketchPalette.SHADE_LEVELS)
        assertEquals(0xFF555555.toInt(), SketchPalette.shade(SketchPalette.DEFAULT_SHADE))
    }

    @Test fun `every level this build offers is a shade, and nothing else is`() {
        SketchPalette.SHADE_LEVELS.forEach { assertTrue(SketchPalette.isShade(it)) }
        assertFalse(SketchPalette.isShade(-1))
        assertFalse(SketchPalette.isShade(16))
        assertFalse(SketchPalette.isShade(255))
    }

    @Test fun `a level on the ladder but not offered is not a shade and reads as the default`() {
        // Levels 1, 3, 7 and 11 are perfectly good rungs of the sixteen-level coordinate system —
        // they are simply not ones this build offers (all four were, in the six of 2026-09-17).
        // The stored number is the LEVEL, so this is exactly what a device remembering one of
        // them from that build hands the face.
        listOf(1, 3, 7, 11).forEach { level ->
            assertFalse(SketchPalette.isShade(level))
            assertEquals(SketchPalette.shade(SketchPalette.DEFAULT_SHADE), SketchPalette.shade(level))
        }
    }

    @Test fun `a level this build does not have reads as the default, never as an exception`() {
        val default = SketchPalette.shade(SketchPalette.DEFAULT_SHADE)
        assertEquals(default, SketchPalette.shade(14))
        assertEquals(default, SketchPalette.shade(16))
        assertEquals(default, SketchPalette.shade(-1))
        // The seam's own sanity bound, which the face must survive reading.
        assertEquals(default, SketchPalette.shade(255))
    }

    @Test fun `the rows wrap at ROW_BREAK and account for every offered level exactly once`() {
        val rows = SketchPalette.shadeRows()
        assertEquals(SketchPalette.SHADE_LEVELS, rows.flatten())
        rows.dropLast(1).forEach { assertEquals(SketchPalette.ROW_BREAK, it.size) }
        assertTrue(rows.last().size in 1..SketchPalette.ROW_BREAK)
    }

    @Test fun `at four offered shades the bar is one row of four`() {
        assertEquals(listOf(listOf(0, 5, 9, 15)), SketchPalette.shadeRows())
    }

    @Test fun `the twelve leads are the walked ones, finest first`() {
        assertEquals(
            listOf(1.2f, 2f, 4f, 7f, 12f, 16f, 20f, 24f, 32f, 48f, 64f, 96f),
            SketchPalette.SIZES_PX,
        )
        assertEquals(0, SketchPalette.DEFAULT_SIZE)
        assertEquals(1.2f, SketchPalette.size(SketchPalette.DEFAULT_SIZE), 0f)
        // Finest first, and never twice: the row has to read as a ladder.
        assertEquals(SketchPalette.SIZES_PX.sorted(), SketchPalette.SIZES_PX)
        assertEquals(SketchPalette.SIZES_PX.size, SketchPalette.SIZES_PX.toSet().size)
    }

    @Test fun `the twelve sizes lay out as two rows of six indices`() {
        assertEquals(
            listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 10, 11)),
            SketchPalette.sizeRows(),
        )
    }

    @Test fun `the size rows wrap at SIZE_ROW_BREAK and account for every index exactly once`() {
        val rows = SketchPalette.sizeRows()
        assertEquals(SketchPalette.SIZES_PX.indices.toList(), rows.flatten())
        rows.dropLast(1).forEach { assertEquals(SketchPalette.SIZE_ROW_BREAK, it.size) }
        assertTrue(rows.last().size in 1..SketchPalette.SIZE_ROW_BREAK)
    }

    @Test fun `a size index off the list reads as the default`() {
        SketchPalette.SIZES_PX.indices.forEach { assertTrue(SketchPalette.isSize(it)) }
        assertFalse(SketchPalette.isSize(-1))
        // Twelve sizes means 0..11 — index 12 is one past the end, which is what a device
        // remembering a longer list from another build hands back.
        assertEquals(12, SketchPalette.SIZES_PX.size)
        assertFalse(SketchPalette.isSize(12))
        assertEquals(1.2f, SketchPalette.size(12), 0f)
        assertFalse(SketchPalette.isSize(SketchPalette.SIZES_PX.size))
        assertEquals(1.2f, SketchPalette.size(SketchPalette.SIZES_PX.size), 0f)
        assertEquals(1.2f, SketchPalette.size(-4), 0f)
    }

    @Test fun `the gel pen is black at the width the hand chose`() {
        assertEquals(0xFF000000.toInt(), SketchPalette.PEN_COLOR)
        assertEquals(5f, SketchPalette.PEN_WIDTH_PX, 0f)
    }
}
