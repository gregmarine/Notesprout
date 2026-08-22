package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class InkStrokeTest {

    @Test
    fun acceptsEqualNonEmptyArrays() {
        val s = InkStroke(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        assertEquals(2, s.size)
        assertEquals(2f, s.x[1], 0f)
        assertEquals(4f, s.y[1], 0f)
    }

    @Test
    fun rejectsMismatchedLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            InkStroke(floatArrayOf(1f, 2f), floatArrayOf(3f))
        }
    }

    @Test
    fun rejectsEmpty() {
        assertThrows(IllegalArgumentException::class.java) {
            InkStroke(FloatArray(0), FloatArray(0))
        }
    }
}
