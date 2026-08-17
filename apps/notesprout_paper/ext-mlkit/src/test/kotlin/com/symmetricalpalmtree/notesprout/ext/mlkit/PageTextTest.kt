package com.symmetricalpalmtree.notesprout.ext.mlkit

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTextTest {

    @Test
    fun preContextTailIsAtMost20Chars() {
        assertEquals("", PageText.preContextTail(""))
        assertEquals("short", PageText.preContextTail("short"))
        val long = "abcdefghijklmnopqrstuvwxyz"   // 26
        assertEquals("ghijklmnopqrstuvwxyz", PageText.preContextTail(long))
        assertEquals(20, PageText.preContextTail(long).length)
    }

    @Test
    fun joinLinesAndParagraphs() {
        assertEquals("", PageText.join(emptyList()))
        assertEquals("a", PageText.join(listOf(listOf("a"))))
        assertEquals("a\nb", PageText.join(listOf(listOf("a", "b"))))
        assertEquals("a\nb\n\nc", PageText.join(listOf(listOf("a", "b"), listOf("c"))))
    }

    @Test
    fun emptyParagraphsContributeNothing() {
        assertEquals("", PageText.join(listOf(emptyList(), emptyList())))
        assertEquals("a\n\nc", PageText.join(listOf(listOf("a"), emptyList(), listOf("c"))))
    }
}
