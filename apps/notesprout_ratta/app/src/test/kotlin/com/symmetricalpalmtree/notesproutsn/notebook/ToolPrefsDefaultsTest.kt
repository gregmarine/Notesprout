package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The *values* of [ToolPrefs]' first-ever defaults — the only part of it that is pure. The accessors
 * need real `SharedPreferences`, which a JVM test cannot give them honestly (the stub returns the
 * type default, so a read test would assert the stub, not the pref).
 *
 * These constants are locked phase decisions, not taste: PEN · 3 px · eraser 15 px is R3's
 * Paper-v0 parity answer, and both pen-gesture recognisers shipping **on** is R5's.
 */
class ToolPrefsDefaultsTest {

    @Test
    fun `both pen-gesture recognisers default on`() {
        assertTrue(ToolPrefs.DEFAULT_SMART_LASSO)
        assertTrue(ToolPrefs.DEFAULT_SCRIBBLE_ERASE)
    }

    @Test
    fun `the pen and eraser defaults are on the ladders the panels offer`() {
        assertEquals(3f, ToolPrefs.DEFAULT_WIDTH, 0f)
        assertEquals(15f, ToolPrefs.DEFAULT_ERASER, 0f)
        assertTrue(ToolPrefs.DEFAULT_WIDTH in ToolPrefs.WIDTHS)
        assertTrue(ToolPrefs.DEFAULT_ERASER in ToolPrefs.ERASER_RADII)
    }
}
