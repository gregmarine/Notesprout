package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection's preparation for the lookup call (arc 39) — what crosses, and what never does. */
class LookupTextTest {

    @Test
    fun `a plain reference crosses as it is`() {
        assertEquals("John 3:16", LookupText.prepare("John 3:16"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("John 3:16", LookupText.prepare("  John 3:16\n"))
    }

    @Test
    fun `a selection dragged across a line break folds to one space`() {
        assertEquals("John 3:14-18, Proverbs 3:5-6", LookupText.prepare("John 3:14-18,\n   Proverbs\t3:5-6"))
    }

    @Test
    fun `nothing and whitespace are refused`() {
        assertNull(LookupText.prepare(null))
        assertNull(LookupText.prepare(""))
        assertNull(LookupText.prepare(" \n\t "))
    }

    @Test
    fun `the cap is the contract's, measured after folding`() {
        val atCap = "a".repeat(DocumentContract.MAX_REFERENCE_CHARS)
        assertEquals(atCap, LookupText.prepare("  $atCap  "))
        assertNull(LookupText.prepare(atCap + "b"))
    }

    @Test
    fun `the alert's preview is short, folded, and marked as cut`() {
        assertEquals("John 3:16", LookupText.preview("  John\n3:16 "))
        val long = "word ".repeat(40)
        val shown = LookupText.preview(long)
        assertTrue(shown.length <= LookupText.PREVIEW_CHARS + 1)
        assertEquals("…", shown.takeLast(1))
        assertEquals("word", shown.dropLast(1).takeLast(4)) // cut on a word edge, the space trimmed
    }
}
