package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [WireStroke.requireValid] — which runs in the constructor, so it runs at unmarshal too: a
 * malformed stroke rejects the whole bundle it rides in rather than being quietly dropped. (A Parcel
 * round trip needs a device; `:extension-api` runs no Robolectric.)
 */
class WireStrokeTest {

    private fun stroke(n: Int, width: Float = 3f) = WireStroke(
        FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), width, -0x1000000, "PEN",
    )

    @Test
    fun acceptsFourEqualNonEmptyChannels() {
        val s = stroke(3)
        assertEquals(3, s.size)
        assertEquals("PEN", s.style)
    }

    @Test
    fun rejectsEmpty() {
        assertThrows(IllegalArgumentException::class.java) { stroke(0) }
    }

    @Test
    fun rejectsChannelLengthMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            WireStroke(FloatArray(2), FloatArray(2), FloatArray(1), FloatArray(2), 3f, 0, "PEN")
        }
    }

    @Test
    fun rejectsNonPositiveOrNonFiniteWidth() {
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = 0f) }
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = -1f) }
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = Float.POSITIVE_INFINITY) }
    }
}
