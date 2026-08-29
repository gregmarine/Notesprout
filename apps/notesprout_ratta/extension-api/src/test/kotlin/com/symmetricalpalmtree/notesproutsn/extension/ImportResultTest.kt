package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class ImportResultTest {

    @Test
    fun acceptsZeroAndPositive() {
        assertEquals(0L, ImportResult(0L).bytesWritten)
        assertEquals(618_496L, ImportResult(618_496L).bytesWritten)
    }

    @Test
    fun rejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) { ImportResult(-1L) }
    }
}
