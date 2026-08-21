package com.symmetricalpalmtree.notesprout.ext.markdown

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The pure padding + cap arithmetic of [MarkdownBitmap.Sizing] (the Android draw path needs a device). */
class MarkdownBitmapSizingTest {

    private val edge = ExtensionContract.MAX_IMAGE_EDGE_PX

    @Test
    fun `content width is the caller's width minus both paddings, never below one`() {
        assertEquals(84, MarkdownBitmap.Sizing.contentWidth(100, 8))
        assertEquals(100, MarkdownBitmap.Sizing.contentWidth(100, 0))
        assertEquals(1, MarkdownBitmap.Sizing.contentWidth(10, 8))
    }

    @Test
    fun `image size is the natural width plus padding, capped at the content width`() {
        assertEquals(60 to 46, MarkdownBitmap.Sizing.imageSize(naturalWidth = 44, layoutHeight = 30, maxWidthPx = 500, paddingPx = 8))
        // Wider than allowed: capped at contentWidth (500 − 16 = 484) + 16.
        assertEquals(500 to 46, MarkdownBitmap.Sizing.imageSize(naturalWidth = 900, layoutHeight = 30, maxWidthPx = 500, paddingPx = 8))
        // No padding, exact.
        assertEquals(44 to 30, MarkdownBitmap.Sizing.imageSize(44, 30, 500, 0))
        // Degenerate layout still yields a 1 px content box.
        assertEquals(17 to 17, MarkdownBitmap.Sizing.imageSize(0, 0, 500, 8))
    }

    @Test
    fun `image over the edge cap is rejected`() {
        // Height is the only way over: the width is capped at maxWidthPx, itself ≤ the edge (checkArgs).
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.imageSize(10, edge, 100, 1) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.imageSize(10, edge + 1, 100, 0) }
        // Width at the cap: natural width beyond it is cut to exactly maxWidthPx.
        assertEquals(edge to 12, MarkdownBitmap.Sizing.imageSize(edge + 500, 12, edge, 0))
        assertEquals(edge to 14, MarkdownBitmap.Sizing.imageSize(edge + 500, 12, edge, 1))
        // Exactly at the cap is fine.
        assertEquals(edge to edge, MarkdownBitmap.Sizing.imageSize(edge, edge, edge, 0))
    }

    @Test
    fun `checkArgs enforces the contract caps`() {
        MarkdownBitmap.Sizing.checkArgs(10, 100, 300f, 0, 8)
        MarkdownBitmap.Sizing.checkArgs(ExtensionContract.MAX_MARKDOWN_CHARS, edge, 1f, 1, ExtensionContract.RENDER_PADDING_MAX_PX)
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(ExtensionContract.MAX_MARKDOWN_CHARS + 1, 100, 300f, 0, 8) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, 0, 300f, 0, 8) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, edge + 1, 300f, 0, 8) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, 100, 0f, 0, 8) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, 100, Float.NaN, 0, 8) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, 100, 300f, -1, 8) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, 100, 300f, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { MarkdownBitmap.Sizing.checkArgs(10, 100, 300f, 0, ExtensionContract.RENDER_PADDING_MAX_PX + 1) }
    }
}
