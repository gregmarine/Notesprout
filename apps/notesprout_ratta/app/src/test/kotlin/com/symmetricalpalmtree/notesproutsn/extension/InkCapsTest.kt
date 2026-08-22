package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The outward gate: what may not leave the host never binds; inward values are normalised. */
class InkCapsTest {

    private fun stroke(n: Int) = InkStroke(FloatArray(n) { it.toFloat() }, FloatArray(n) { it.toFloat() })

    @Test
    fun acceptsInkWithinCaps() {
        InkCaps.check(listOf(stroke(3), stroke(2)), 100f, 50f)
    }

    @Test
    fun rejectsTooManyStrokes() {
        val strokes = List(ExtensionContract.MAX_INK_STROKES + 1) { stroke(1) }
        assertThrows(InkTooLargeException::class.java) { InkCaps.check(strokes, 10f, 10f) }
    }

    @Test
    fun rejectsTooManyPoints() {
        // Few strokes, many points — the per-point cap must trip on the sum.
        val strokes = List(7) { stroke(10_000) }
        assertThrows(InkTooLargeException::class.java) { InkCaps.check(strokes, 10f, 10f) }
    }

    @Test
    fun rejectsNonPositiveSizes() {
        assertThrows(InkTooLargeException::class.java) { InkCaps.check(listOf(stroke(1)), 0f, 10f) }
        assertThrows(InkTooLargeException::class.java) { InkCaps.check(listOf(stroke(1)), 10f, -1f) }
        assertThrows(InkTooLargeException::class.java) { InkCaps.check(listOf(stroke(1)), Float.NaN, 10f) }
    }

    @Test
    fun preContextKeepsOnlyTheTail() {
        val long = "x".repeat(50) + "tail"
        assertEquals(ExtensionContract.MAX_PRECONTEXT_CHARS, InkCaps.preContext(long).length)
        assertEquals("tail", InkCaps.preContext(long).takeLast(4))
        assertEquals("short", InkCaps.preContext("short"))
    }

    @Test
    fun inwardStatusIsNormalised() {
        assertEquals(RecognizerStatus.READY, InkCaps.status(0))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(3))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(-1))
        assertEquals(RecognizerStatus.UNAVAILABLE, InkCaps.status(99))
    }

    @Test
    fun inwardTextIsNormalised() {
        assertEquals("", InkCaps.text(null))
        assertEquals("abc", InkCaps.text("abc"))
        assertEquals(ExtensionContract.MAX_RECOGNIZED_CHARS, InkCaps.text("y".repeat(30_000)).length)
    }
}
