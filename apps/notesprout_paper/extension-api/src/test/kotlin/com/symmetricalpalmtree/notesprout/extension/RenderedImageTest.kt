package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The `require`s (via the pure [RenderedImage.requireValid] the constructor calls) + the arc-4
 * constants — a Parcel / SharedMemory round trip needs a device (`:extension-api` runs no Robolectric).
 */
class RenderedImageTest {

    private val edge = ExtensionContract.MAX_IMAGE_EDGE_PX

    @Test
    fun acceptsPositiveSizes() {
        RenderedImage.requireValid(10, 24, 12)
        RenderedImage.requireValid(1, edge, edge)
    }

    @Test
    fun rejectsZeroBytes() {
        assertThrows(IllegalArgumentException::class.java) { RenderedImage.requireValid(0, 24, 12) }
    }

    @Test
    fun rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { RenderedImage.requireValid(10, 0, 12) }
        assertThrows(IllegalArgumentException::class.java) { RenderedImage.requireValid(10, 24, -1) }
    }

    @Test
    fun rejectsOverEdgeCap() {
        assertThrows(IllegalArgumentException::class.java) { RenderedImage.requireValid(10, edge + 1, 12) }
        assertThrows(IllegalArgumentException::class.java) { RenderedImage.requireValid(10, 12, edge + 1) }
    }

    @Test
    fun contractConstants() {
        assertEquals("com.symmetricalpalmtree.notesprout.extension.MARKDOWN_RENDERER", ExtensionContract.ACTION_MARKDOWN_RENDERER)
        assertEquals(20_000, ExtensionContract.MAX_MARKDOWN_CHARS)
        assertEquals(4_096, ExtensionContract.MAX_IMAGE_EDGE_PX)
        assertEquals(64, ExtensionContract.RENDER_PADDING_MAX_PX)
    }
}
