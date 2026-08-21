package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RenderCapsTest {

    @Test
    fun markdownTruncatedToCap() {
        val long = "x".repeat(ExtensionContract.MAX_MARKDOWN_CHARS + 5)
        assertEquals(ExtensionContract.MAX_MARKDOWN_CHARS, RenderCaps.markdown(long).length)
        assertEquals("# a", RenderCaps.markdown("# a"))
    }

    @Test
    fun argsAccepted() {
        RenderCaps.checkArgs(1, 160f, 0, 0)
        RenderCaps.checkArgs(ExtensionContract.MAX_IMAGE_EDGE_PX, 300f, 1, ExtensionContract.RENDER_PADDING_MAX_PX)
    }

    @Test
    fun argsRejected() {
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(0, 160f, 0, 0) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(ExtensionContract.MAX_IMAGE_EDGE_PX + 1, 160f, 0, 0) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(100, 0f, 0, 0) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(100, Float.NaN, 0, 0) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(100, Float.POSITIVE_INFINITY, 0, 0) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(100, 160f, -1, 0) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(100, 160f, 0, -1) }
        assertThrows(RenderArgsException::class.java) { RenderCaps.checkArgs(100, 160f, 0, ExtensionContract.RENDER_PADDING_MAX_PX + 1) }
    }

    @Test
    fun imageProblems() {
        assertNull(RenderCaps.imageProblem(120, 40, 120 to 40))
        assertNotNull(RenderCaps.imageProblem(0, 40, 0 to 40))
        assertNotNull(RenderCaps.imageProblem(ExtensionContract.MAX_IMAGE_EDGE_PX + 1, 40, (ExtensionContract.MAX_IMAGE_EDGE_PX + 1) to 40))
        assertNotNull(RenderCaps.imageProblem(120, 40, null))          // undecodable
        assertNotNull(RenderCaps.imageProblem(120, 40, 121 to 40))     // header disagrees
    }

    @Test
    fun bytesProblems() {
        assertNull(RenderCaps.bytesProblem(ExtensionContract.MIME_WEBP, 10, 10))
        assertNull(RenderCaps.bytesProblem(ExtensionContract.MIME_WEBP, 10, 16))
        assertNotNull(RenderCaps.bytesProblem("image/png", 10, 10))
        assertNotNull(RenderCaps.bytesProblem(null, 10, 10))
        assertNotNull(RenderCaps.bytesProblem(ExtensionContract.MIME_WEBP, 0, 10))
        assertNotNull(RenderCaps.bytesProblem(ExtensionContract.MIME_WEBP, 11, 10))       // beyond the region
        assertNotNull(RenderCaps.bytesProblem(ExtensionContract.MIME_WEBP, ExtensionContract.MAX_RENDER_BYTES + 1, Int.MAX_VALUE))
    }
}
