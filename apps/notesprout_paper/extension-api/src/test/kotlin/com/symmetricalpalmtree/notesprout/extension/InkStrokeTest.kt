package com.symmetricalpalmtree.notesprout.extension

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

    @Test
    fun contractConstants() {
        assertEquals(2_000, ExtensionContract.MAX_INK_STROKES)
        assertEquals(60_000, ExtensionContract.MAX_INK_POINTS)
        assertEquals(20, ExtensionContract.MAX_PRECONTEXT_CHARS)
        assertEquals(20_000, ExtensionContract.MAX_RECOGNIZED_CHARS)
        assertEquals(0, RecognizerStatus.READY)
        assertEquals(1, RecognizerStatus.NEEDS_DOWNLOAD)
        assertEquals(2, RecognizerStatus.DOWNLOADING)
        assertEquals(3, RecognizerStatus.UNAVAILABLE)
        assertEquals(
            "com.symmetricalpalmtree.notesprout.extension.HANDWRITING_RECOGNIZER",
            ExtensionContract.ACTION_HANDWRITING_RECOGNIZER,
        )
    }
}
