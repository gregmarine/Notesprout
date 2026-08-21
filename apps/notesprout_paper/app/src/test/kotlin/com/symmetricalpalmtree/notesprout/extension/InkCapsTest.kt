package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InkCapsTest {

    private fun stroke(n: Int) = InkStroke(FloatArray(n) { it.toFloat() }, FloatArray(n) { it * 2f })

    private fun expectTooLarge(block: () -> Unit): String {
        try { block() } catch (e: InkTooLargeException) { return e.message ?: "" }
        fail("expected InkTooLargeException"); return ""
    }

    @Test fun withinCaps_passes() {
        InkCaps.check(List(ExtensionContract.MAX_INK_STROKES) { stroke(30) }, 1000f, 1400f)
        InkCaps.check(emptyList(), 1f, 1f)
    }

    @Test fun overStrokes_throws() {
        val msg = expectTooLarge { InkCaps.check(List(ExtensionContract.MAX_INK_STROKES + 1) { stroke(1) }, 10f, 10f) }
        assertTrue(msg, msg.contains("strokes"))
    }

    @Test fun overPoints_throws() {
        // 61 strokes × 1 000 points = 61 000 > 60 000, well under the stroke cap
        val msg = expectTooLarge { InkCaps.check(List(61) { stroke(1_000) }, 10f, 10f) }
        assertTrue(msg, msg.contains("points"))
    }

    @Test fun exactlyAtPointCap_passes() {
        InkCaps.check(List(60) { stroke(1_000) }, 10f, 10f)
    }

    @Test fun nonPositiveSize_throws() {
        expectTooLarge { InkCaps.check(listOf(stroke(3)), 0f, 10f) }
        expectTooLarge { InkCaps.check(listOf(stroke(3)), 10f, -1f) }
        expectTooLarge { InkCaps.check(listOf(stroke(3)), Float.NaN, 10f) }
    }

    @Test fun emptyOrMismatchedStroke_rejectedByInkStrokeItself() {
        // The parcelable's own `require`s are the first line; InkCaps re-states them.
        try { InkStroke(FloatArray(0), FloatArray(0)); fail("empty accepted") } catch (_: IllegalArgumentException) {}
        try { InkStroke(FloatArray(2), FloatArray(3)); fail("mismatch accepted") } catch (_: IllegalArgumentException) {}
    }

    @Test fun preContext_truncatedToTail() {
        val long = "abcdefghijklmnopqrstuvwxyz"   // 26 chars
        assertEquals("ghijklmnopqrstuvwxyz", InkCaps.preContext(long))
        assertEquals(ExtensionContract.MAX_PRECONTEXT_CHARS, InkCaps.preContext(long).length)
        assertEquals("short", InkCaps.preContext("short"))
        assertEquals("", InkCaps.preContext(""))
    }

    @Test fun inwardStatus_outsideRangeIsUnavailable() {
        assertEquals(RecognizerStatus.READY, InkCaps.status(0))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(3))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(4))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(-1))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(Int.MAX_VALUE))
    }

    @Test fun inwardText_nullIsEmpty_andTruncated() {
        assertEquals("", InkCaps.text(null))
        assertEquals("hi", InkCaps.text("hi"))
        val huge = "x".repeat(ExtensionContract.MAX_RECOGNIZED_CHARS + 5)
        assertEquals(ExtensionContract.MAX_RECOGNIZED_CHARS, InkCaps.text(huge).length)
    }
}
