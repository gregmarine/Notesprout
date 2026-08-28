package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class ExportResultTest {

    @Test
    fun acceptsZeroAndPositive() {
        assertEquals(0L, ExportResult(0L).bytesWritten)
        assertEquals(618_496L, ExportResult(618_496L).bytesWritten)
    }

    @Test
    fun rejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) { ExportResult(-1L) }
    }
}
