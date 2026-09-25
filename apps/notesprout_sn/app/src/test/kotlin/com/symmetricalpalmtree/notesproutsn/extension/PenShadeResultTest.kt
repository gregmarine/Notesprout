package com.symmetricalpalmtree.notesproutsn.extension

import com.symmetricalpalmtree.notesproutsn.core.InkTones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chrome flag's rule for the pen's shade: present → its level, absent → null, off the ladder → black. */
class PenShadeResultTest {

    @Test
    fun presentIsItsLevel() {
        InkTones.LEVELS.forEach { assertEquals(it, PenShadeResult.decode(present = true, value = it)) }
    }

    @Test
    fun presentOffTheLadderIsBlack() {
        assertEquals(InkTones.BLACK, PenShadeResult.decode(present = true, value = 16))
        assertEquals(InkTones.BLACK, PenShadeResult.decode(present = true, value = -1))
        assertEquals(InkTones.BLACK, PenShadeResult.decode(present = true, value = Int.MAX_VALUE))
    }

    @Test
    fun absentIsNullWhateverTheValueSlotSays() {
        // A killed process (null data) and an extension that predates the extra both land here.
        assertNull(PenShadeResult.decode(present = false, value = InkTones.BLACK))
        assertNull(PenShadeResult.decode(present = false, value = 9))
    }

    @Test
    fun noIntentIsNull() {
        assertNull(PenShadeResult.read(null))
    }
}
