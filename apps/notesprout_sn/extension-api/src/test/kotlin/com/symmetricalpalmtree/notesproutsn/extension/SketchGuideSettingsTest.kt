package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The guide-settings parcelable's constructor `require`s (arc 51 / J1) — unmarshal is the
 * validation. No real `Parcel` round trip is available here (the [SketchPageStateTest] note); what
 * is pinned is every rule the constructor enforces and the value semantics both sides lean on.
 */
class SketchGuideSettingsTest {

    private fun assertRefused(build: () -> SketchGuideSettings) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aPlainSettingHolds() {
        val s = SketchGuideSettings(SketchContract.GRID_LINES, 4, true, 25, false)
        assertEquals(SketchContract.GRID_LINES, s.gridKind)
        assertEquals(4, s.gridCount)
        assertTrue(s.gridVisible)
        assertEquals(25, s.imageOpacity)
        assertFalse(s.imageVisible)
        assertTrue(s.hasGrid)
    }

    @Test
    fun `NONE is no grid and an image that is not there`() {
        assertEquals(SketchContract.GRID_OFF, SketchGuideSettings.NONE.gridKind)
        assertEquals(0, SketchGuideSettings.NONE.gridCount)
        assertFalse(SketchGuideSettings.NONE.hasGrid)
        assertEquals(0, SketchGuideSettings.NONE.imageOpacity)
    }

    @Test
    fun `the bounds are sanity bounds, not the face's ladders`() {
        // A count or a kind past the face's own ladder is a LEGAL parcel — the face reads it as
        // its default. Only a number that cannot be one at all is refused.
        val max = SketchContract.MAX_TOOL_SETTING_INDEX
        SketchGuideSettings(max, max, false, SketchContract.MAX_OPACITY_PERCENT, false)
        SketchGuideSettings(SketchContract.GRID_DOTS, 1, true, 0, true)
    }

    @Test
    fun `a grid with no cells is refused, a grid that is off may carry none`() {
        assertRefused { SketchGuideSettings(SketchContract.GRID_LINES, 0, true, 25, true) }
        SketchGuideSettings(SketchContract.GRID_OFF, 0, true, 25, true)
        SketchGuideSettings(SketchContract.GRID_OFF, 4, true, 25, true) // a hidden count is kept
    }

    @Test
    fun negativesAndOversizeAreRefusedFieldByField() {
        val over = SketchContract.MAX_TOOL_SETTING_INDEX + 1
        assertRefused { SketchGuideSettings(-1, 1, true, 25, true) }
        assertRefused { SketchGuideSettings(over, 1, true, 25, true) }
        assertRefused { SketchGuideSettings(1, -1, true, 25, true) }
        assertRefused { SketchGuideSettings(1, over, true, 25, true) }
        assertRefused { SketchGuideSettings(1, 1, true, -1, true) }
        assertRefused { SketchGuideSettings(1, 1, true, SketchContract.MAX_OPACITY_PERCENT + 1, true) }
    }

    @Test
    fun itIsAValue() {
        val a = SketchGuideSettings(1, 4, true, 25, true)
        val b = SketchGuideSettings(1, 4, true, 25, true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, SketchGuideSettings(1, 4, false, 25, true))
        assertNotEquals(a, SketchGuideSettings(1, 4, true, 50, true))
        assertNotEquals(a, SketchGuideSettings(2, 4, true, 25, true))
    }
}
