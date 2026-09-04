package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The keypad's typed string (arc 24 / Z5b): the leading zero, the width cap the range sets, and
 *  the number that comes back out. */
class KeypadModelTest {

    private fun model(range: IntRange = 1..99, current: Int = 3) = KeypadModel(range, current)

    @Test
    fun nothingTypedIsNotANumberYet() {
        val m = model()
        assertEquals("", m.text)
        // The dialog shows `current` while this is null — that is the whole reason it is null.
        assertNull(m.value())
        assertEquals(3, m.current)
    }

    @Test
    fun aLeadingZeroIsReplacedRatherThanBuiltOn() {
        val m = model()
        m.digit(0)
        assertEquals("0", m.text)
        m.digit(5)
        assertEquals("5", m.text)
        assertEquals(5, m.value())
        // And a zero after a real digit is just a digit.
        m.digit(0)
        assertEquals("50", m.text)
    }

    @Test
    fun theRangesWidestNumberIsTheTypingCap() {
        val two = model(1..99)
        two.digit(1); two.digit(2); two.digit(3)
        assertEquals("12", two.text)

        val three = model(1..999)
        three.digit(1); three.digit(2); three.digit(3); three.digit(4)
        assertEquals("123", three.text)
    }

    @Test
    fun backspacePastEmptyIsANoOp() {
        val m = model()
        m.backspace()
        assertEquals("", m.text)
        m.digit(4)
        m.backspace()
        assertEquals("", m.text)
        assertNull(m.value())
    }

    @Test
    fun theValueComesBackInsideTheRange() {
        // The width cap cannot catch a number that is merely too small or too big for the range.
        val m = model(1..99)
        m.digit(0)
        assertEquals(1, m.value())

        val narrow = model(5..50)
        narrow.digit(9); narrow.digit(9)
        assertEquals(50, narrow.value())
    }

    @Test
    fun clearPutsItBackToNothingTyped() {
        val m = model()
        m.digit(4); m.digit(2)
        m.clear()
        assertEquals("", m.text)
        assertNull(m.value())
    }
}
