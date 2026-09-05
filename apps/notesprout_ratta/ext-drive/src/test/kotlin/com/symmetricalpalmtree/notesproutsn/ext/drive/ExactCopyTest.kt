package com.symmetricalpalmtree.notesproutsn.ext.drive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The exact-byte rule (arc 25 / V2): an upload commits only what the host said it wrote. */
class ExactCopyTest {

    @Test
    fun exactlyTheExpectedBytes_copyThrough() {
        val source = ByteArrayInputStream(ByteArray(2_000) { (it % 251).toByte() })
        val sink = ByteArrayOutputStream()
        assertEquals(2_000L, ExactCopy.copy(source, sink, 2_000L))
        assertArrayEquals(ByteArray(2_000) { (it % 251).toByte() }, sink.toByteArray())
    }

    @Test
    fun zeroBytes_isFine() {
        assertEquals(0L, ExactCopy.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 0L))
    }

    @Test
    fun aSourceThatEndsEarly_isRefused() {
        try {
            ExactCopy.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 20L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(ExactCopy.SHORT_READ, e.message)
        }
    }

    @Test
    fun aSourceWithMoreToGive_isRefused() {
        try {
            ExactCopy.copy(ByteArrayInputStream(ByteArray(30)), ByteArrayOutputStream(), 20L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(ExactCopy.LONG_READ, e.message)
        }
    }

    @Test
    fun aNegativeExpectation_isTheCallersMistake() {
        try {
            ExactCopy.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), -1L)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message?.contains("negative"))
        }
    }

    @Test
    fun aLargeCopy_crossesTheBufferBoundaryCleanly() {
        val bytes = ByteArray(100_000) { (it % 97).toByte() }
        val sink = ByteArrayOutputStream()
        assertEquals(100_000L, ExactCopy.copy(ByteArrayInputStream(bytes), sink, 100_000L))
        assertArrayEquals(bytes, sink.toByteArray())
    }
}
