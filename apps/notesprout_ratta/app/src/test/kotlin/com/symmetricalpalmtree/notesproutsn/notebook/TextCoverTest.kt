package com.symmetricalpalmtree.notesproutsn.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule in [TextCover] that is provable off-device: how much of a document a cover even
 * considers. (The drawing itself needs a real `Bitmap`, so it is the walk's job and the user's
 * eye-check.)
 */
class TextCoverTest {

    @Test
    fun aShortDocumentIsTakenWhole() {
        val text = "# Title\n\nOne paragraph."
        assertEquals(text, TextCover.opening(text))
        assertEquals("", TextCover.opening(""))
    }

    @Test
    fun aVeryLongDocumentIsCutLongBeforeItIsLaidOut() {
        // Ten megabytes must never reach a StaticLayout to have all but the first page thrown away.
        val huge = "x".repeat(5_000_000)
        assertTrue(TextCover.opening(huge).length < 10_000)
    }

    @Test
    fun aDocumentOfManyShortLinesIsCutAtALineBoundary() {
        val many = (1..500).joinToString("\n") { "line $it" }
        val head = TextCover.opening(many)
        assertTrue(head.length < many.length)
        // Cut on a newline, never mid-line: a thumbnail whose last line is half a word reads as
        // damage rather than as an opening.
        assertTrue(head.endsWith("\n"))
        assertTrue(many.startsWith(head))
    }
}
