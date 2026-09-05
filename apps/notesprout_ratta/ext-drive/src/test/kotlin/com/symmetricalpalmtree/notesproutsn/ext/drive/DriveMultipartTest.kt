package com.symmetricalpalmtree.notesproutsn.ext.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multipart body and the threshold that decides whether it is used at all (arc 25 / V2). */
class DriveMultipartTest {

    @Test
    fun theThresholdIsFiveMebibytes() {
        assertEquals(5L * 1024L * 1024L, DriveMultipart.MULTIPART_MAX_BYTES)
    }

    @Test
    fun exactlyTheThreshold_isStillMultipart() {
        assertTrue(DriveMultipart.useMultipart(DriveMultipart.MULTIPART_MAX_BYTES))
    }

    @Test
    fun oneByteOver_isResumable() {
        assertFalse(DriveMultipart.useMultipart(DriveMultipart.MULTIPART_MAX_BYTES + 1))
    }

    @Test
    fun anEmptyFile_isMultipart() {
        assertTrue(DriveMultipart.useMultipart(0L))
    }

    @Test
    fun contentType_namesTheBoundary() {
        assertEquals("multipart/related; boundary=${DriveMultipart.BOUNDARY}", DriveMultipart.contentType())
    }

    @Test
    fun prefix_isTheBoundaryTheJsonPartAndTheBytesPartHeader() {
        val prefix = DriveMultipart.prefix("{\"name\":\"a.soil\"}", "application/octet-stream").toString(Charsets.UTF_8)
        assertEquals(
            "--${DriveMultipart.BOUNDARY}\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                "{\"name\":\"a.soil\"}\r\n" +
                "--${DriveMultipart.BOUNDARY}\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n",
            prefix,
        )
    }

    @Test
    fun suffix_closesTheBoundary() {
        assertEquals("\r\n--${DriveMultipart.BOUNDARY}--\r\n", DriveMultipart.suffix().toString(Charsets.UTF_8))
    }

    @Test
    fun length_isPrefixPlusBytesPlusSuffix() {
        val prefix = DriveMultipart.prefix("{}", "text/plain")
        val suffix = DriveMultipart.suffix()
        assertEquals(prefix.size + 1_000L + suffix.size, DriveMultipart.length(prefix, suffix, 1_000L))
    }
}
