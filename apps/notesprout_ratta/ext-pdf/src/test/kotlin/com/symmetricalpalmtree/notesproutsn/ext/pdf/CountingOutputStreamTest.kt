package com.symmetricalpalmtree.notesproutsn.ext.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** The count the host is told is the count that went through — every write path, counted once. */
class CountingOutputStreamTest {

    @Test
    fun everyWritePathIsCountedExactlyOnce() {
        val sink = ByteArrayOutputStream()
        val counting = CountingOutputStream(sink)
        counting.write(7)
        counting.write(byteArrayOf(1, 2, 3))
        counting.write(byteArrayOf(4, 5, 6, 7, 8), 1, 3)
        counting.flush()
        assertEquals(7L, counting.count)
        assertArrayEquals(byteArrayOf(7, 1, 2, 3, 5, 6, 7), sink.toByteArray())
    }

    @Test
    fun anUnwrittenStreamCountsZero() {
        assertEquals(0L, CountingOutputStream(ByteArrayOutputStream()).count)
    }

    @Test
    fun closeIsTheDelegatesOwnership() {
        var closed = false
        val counting = CountingOutputStream(object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close() {
                closed = true
            }
        })
        counting.close()
        assertTrue(closed)
    }
}
