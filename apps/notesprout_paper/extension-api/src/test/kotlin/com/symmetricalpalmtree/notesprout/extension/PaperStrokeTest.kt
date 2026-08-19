package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s + constants only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class PaperStrokeTest {

    private fun stroke(n: Int, width: Float = 3f, style: String = "PEN") = PaperStroke(
        FloatArray(n) { it.toFloat() }, FloatArray(n) { it * 2f }, FloatArray(n) { 1f }, FloatArray(n), width, -0x1000000, style,
    )

    @Test
    fun acceptsEqualNonEmptyChannels() {
        val s = stroke(3)
        assertEquals(3, s.size)
        assertEquals(4f, s.y[2], 0f)
        assertEquals("PEN", s.style)
    }

    @Test
    fun unknownStyleIsKeptAsText() {
        // The reader maps an unknown name to PEN; the parcelable itself carries whatever it was given.
        assertEquals("FUTURE_BRUSH", stroke(2, style = "FUTURE_BRUSH").style)
    }

    @Test
    fun rejectsMismatchedLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            PaperStroke(floatArrayOf(1f, 2f), floatArrayOf(3f), floatArrayOf(1f, 1f), floatArrayOf(0f, 0f), 3f, 0, "PEN")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperStroke(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(1f), floatArrayOf(0f, 0f), 3f, 0, "PEN")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaperStroke(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f), floatArrayOf(1f, 1f), floatArrayOf(0f), 3f, 0, "PEN")
        }
    }

    @Test
    fun rejectsEmptyAndBadWidth() {
        assertThrows(IllegalArgumentException::class.java) { stroke(0) }
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = 0f) }
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = -1f) }
        assertThrows(IllegalArgumentException::class.java) { stroke(2, width = Float.NaN) }
    }

    @Test
    fun contractConstants() {
        assertEquals("com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD", ExtensionContract.ACTION_SCRATCH_PAD)
        assertEquals("com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD_SCREEN", ExtensionContract.ACTION_SCRATCH_PAD_SCREEN)
        assertEquals("sendEnabled", ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED)
        assertEquals("openReceived", ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED)
        assertEquals(1, ExtensionContract.RESULT_SCRATCH_SEND)   // Activity.RESULT_FIRST_USER
        assertEquals(0, ExtensionContract.PLACEMENT_NEW_PAGE)
        assertEquals(1, ExtensionContract.PLACEMENT_CURRENT_PAGE)
        assertEquals(5_000, ExtensionContract.MAX_TRANSFER_STROKES)
        assertEquals(200_000, ExtensionContract.MAX_TRANSFER_POINTS)
        assertEquals(300, ExtensionContract.TRANSFER_CHUNK_STROKES)
        assertEquals(20_000, ExtensionContract.TRANSFER_CHUNK_POINTS)
        assertEquals(17, ExtensionContract.TRANSFER_MAX_CHUNKS)
        assertEquals("scratch page full", ExtensionContract.SCRATCH_PAGE_FULL)
        assertEquals(4 * 1024 * 1024, ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertEquals(512 * 1024, ExtensionContract.STORE_MAX_INLINE_BYTES)
        assertEquals("sketching", IconNames.SKETCHING)
        assertEquals(IconNames.SKETCHING, IconNames.ALL.last())
    }
}
