package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The remembered-tools parcelable's constructor `require`s (arc 44 / T2) — unmarshal is the
 * validation. No real `Parcel` round trip is available here (the [SketchPageStateTest] note); what
 * is pinned is every rule the constructor enforces and the value semantics the host's store and the
 * face's "did it change?" check both lean on.
 */
class SketchToolSettingsTest {

    private fun assertRefused(build: () -> SketchToolSettings) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aPlainSettingHolds() {
        val s = SketchToolSettings(SketchContract.TOOL_PEN, shade = 5, size = 2)
        assertEquals(SketchContract.TOOL_PEN, s.tool)
        assertEquals(5, s.shade)
        assertEquals(2, s.size)
    }

    @Test
    fun `the bound is a sanity bound, not the palette's`() {
        // A stored index past the end of a (possibly shortened) palette is a LEGAL parcel — the
        // face reads it as its default. Only a number that cannot be an index at all is refused.
        val max = SketchContract.MAX_TOOL_SETTING_INDEX
        SketchToolSettings(max, max, max)
        SketchToolSettings(0, 0, 0)
        SketchToolSettings(SketchContract.TOOL_PENCIL, shade = 14, size = 4)
    }

    @Test
    fun negativesAndOversizeAreRefusedFieldByField() {
        val over = SketchContract.MAX_TOOL_SETTING_INDEX + 1
        assertRefused { SketchToolSettings(-1, 0, 0) }
        assertRefused { SketchToolSettings(0, -1, 0) }
        assertRefused { SketchToolSettings(0, 0, -1) }
        assertRefused { SketchToolSettings(over, 0, 0) }
        assertRefused { SketchToolSettings(0, over, 0) }
        assertRefused { SketchToolSettings(0, 0, over) }
        assertRefused { SketchToolSettings(Int.MIN_VALUE, Int.MAX_VALUE, 0) }
    }

    @Test
    fun itIsAValue() {
        val a = SketchToolSettings(0, 5, 0)
        assertEquals(a, SketchToolSettings(0, 5, 0))
        assertEquals(a.hashCode(), SketchToolSettings(0, 5, 0).hashCode())
        assertNotEquals(a, SketchToolSettings(1, 5, 0))
        assertNotEquals(a, SketchToolSettings(0, 6, 0))
        assertNotEquals(a, SketchToolSettings(0, 5, 1))
        assertEquals("SketchToolSettings(tool=0, shade=5, size=0)", a.toString())
    }
}
