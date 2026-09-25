package com.symmetricalpalmtree.notesproutsn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFitTest {

    @Test
    fun `a wide picture takes the full width and sits centred vertically`() {
        val r = ImageFit.plan(2000, 1000, 1404, 1872)!!
        assertEquals(0f, r.left, 0f)
        assertEquals(1404f, r.width, 0.001f)
        assertEquals(702f, r.height, 0.001f)
        assertEquals((1872f - 702f) / 2f, r.top, 0.001f)
    }

    @Test
    fun `a tall picture takes the full height and sits centred horizontally`() {
        val r = ImageFit.plan(1000, 4000, 1404, 1872)!!
        assertEquals(0f, r.top, 0f)
        assertEquals(1872f, r.height, 0.001f)
        assertEquals(468f, r.width, 0.001f)
        assertEquals((1404f - 468f) / 2f, r.left, 0.001f)
    }

    @Test
    fun `aspect is kept, never stretched`() {
        val r = ImageFit.plan(300, 200, 1404, 1872)!!
        assertEquals(1.5f, r.width / r.height, 0.0001f)
    }

    @Test
    fun `a page-sized picture fills the page exactly`() {
        val r = ImageFit.plan(1404, 1872, 1404, 1872)!!
        assertEquals(ImageFit.Rect(0f, 0f, 1404f, 1872f), r)
    }

    @Test
    fun `a degenerate size is nothing to draw`() {
        assertNull(ImageFit.plan(0, 10, 100, 100))
        assertNull(ImageFit.plan(10, 10, 100, 0))
    }
}
