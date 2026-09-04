package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

/** The seven-latch count row (arc 24 / Z5b): exactly one latch down, whatever the value, and the
 *  More latch owning up to the number it stands for. */
class CountPresetsTest {

    private fun downIndex(value: Int): Int = CountPresets.pressed(value).indexOf(true)

    @Test
    fun eachPresetArmsItsOwnLatch() {
        for (n in CountPresets.PRESETS) {
            val row = CountPresets.pressed(n)
            assertEquals(CountPresets.SIZE, row.size)
            assertEquals(1, row.count { it })
            assertEquals(n - 1, row.indexOf(true))
        }
    }

    @Test
    fun anythingPastThePresetsArmsMore() {
        assertEquals(CountPresets.SIZE - 1, downIndex(7))
        assertEquals(CountPresets.SIZE - 1, downIndex(30))
        assertEquals(CountPresets.SIZE - 1, downIndex(999))
    }

    @Test
    fun aValueBelowTheFloorStillLeavesOneLatchDown() {
        // Nothing down reads as broken on e-ink, so 0 and −3 are the floor, not "none".
        assertEquals(0, downIndex(0))
        assertEquals(0, downIndex(-3))
        assertEquals(1, CountPresets.pressed(0).count { it })
    }

    @Test
    fun moreSaysTheNumberOnlyOnceItIsCarryingOne() {
        assertEquals("More", CountPresets.moreLabel(1, "More"))
        assertEquals("More", CountPresets.moreLabel(6, "More"))
        assertEquals("7", CountPresets.moreLabel(7, "More"))
        assertEquals("30", CountPresets.moreLabel(30, "More"))
    }
}
