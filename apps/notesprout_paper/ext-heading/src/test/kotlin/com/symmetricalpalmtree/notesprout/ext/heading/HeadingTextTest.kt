package com.symmetricalpalmtree.notesprout.ext.heading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTextTest {

    @Test
    fun prefixOneToSix() {
        assertEquals("# ", HeadingText.prefix(1))
        assertEquals("### ", HeadingText.prefix(3))
        assertEquals("###### ", HeadingText.prefix(6))
        assertEquals("# ", HeadingText.prefix(0))       // clamped
        assertEquals("###### ", HeadingText.prefix(9))  // clamped
    }

    @Test
    fun strip() {
        assertEquals("Meeting notes", HeadingText.strip("## Meeting notes"))
        assertEquals("Meeting notes", HeadingText.strip("###### Meeting notes  "))
        assertEquals("Meeting notes", HeadingText.strip("Meeting notes"))
        assertEquals("#tag", HeadingText.strip("#tag"))               // no space → not a prefix
        assertEquals("####### seven", HeadingText.strip("####### seven"))   // seven #s is not a heading prefix (markdown rule) → untouched
    }

    @Test
    fun withLevel() {
        assertEquals("## Meeting notes", HeadingText.withLevel("Meeting notes", 2))
        assertEquals("### Meeting notes", HeadingText.withLevel("## Meeting notes", 3))   // re-prefix
        assertEquals("# a b", HeadingText.withLevel("a\nb", 1))                        // newline folded
    }

    @Test
    fun levelOf() {
        assertEquals(1, HeadingText.levelOf("# x"))
        assertEquals(4, HeadingText.levelOf("#### x"))
        assertEquals(6, HeadingText.levelOf("###### x"))
        assertEquals(1, HeadingText.levelOf("x"))            // malformed → 1
        assertEquals(1, HeadingText.levelOf(""))
        assertEquals(1, HeadingText.levelOf("#nospace"))
        assertEquals(1, HeadingText.levelOf("####### x"))    // seven #s is not a heading → malformed → 1
    }

    @Test
    fun foldNewlinesAndBlank() {
        assertEquals("a b c", HeadingText.fold("a\nb\r\nc"))
        assertEquals("a b", HeadingText.fold("  a    b  "))
        assertEquals("", HeadingText.fold("\n\n  \n"))
    }

    @Test
    fun actionsLeafIds() {
        assertEquals("h3", HeadingActions.leafId(3))
        assertEquals(3, HeadingActions.levelOf("h3"))
        assertNull(HeadingActions.levelOf("h7"))
        assertNull(HeadingActions.levelOf("h0"))
        assertNull(HeadingActions.levelOf("heading"))
        assertNull(HeadingActions.levelOf(null))
        val parent = HeadingActions.describe().single()
        assertEquals("heading", parent.id)
        assertEquals(6, parent.subActions.size)
        assertEquals(listOf("h1", "h2", "h3", "h4", "h5", "h6"), parent.subActions.map { it.id })
    }
}
