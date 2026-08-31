package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card-level flag rule (arc 19 / M8) — the one bit of the library's card path that is pure. */
class LibraryCardsTest {

    @Test
    fun `text document bit is read`() {
        assertTrue(LibraryCards.isTextDocument(NotebookFlags.TEXT_DOCUMENT))
    }

    /** The bit never travels alone: every notebook SN writes is encrypted, and some are excluded
     *  from backup. Masking, not equality. */
    @Test
    fun `other flags do not hide it`() {
        val flags = NotebookFlags.ENCRYPTED or
            NotebookFlags.EXCLUDE_FROM_BACKUP or
            NotebookFlags.TEXT_DOCUMENT
        assertTrue(LibraryCards.isTextDocument(flags))
    }

    @Test
    fun `a handwritten notebook is not one`() {
        assertFalse(LibraryCards.isTextDocument(NotebookFlags.ENCRYPTED))
        assertFalse(LibraryCards.isTextDocument(NotebookFlags.ENCRYPTED or NotebookFlags.EXCLUDE_FROM_BACKUP))
        assertFalse(LibraryCards.isTextDocument(0))
    }

    /** Absent flags read as handwritten — the family's default, and the safe way to be wrong. */
    @Test
    fun `null flags are not a text document`() {
        assertFalse(LibraryCards.isTextDocument(null))
    }
}
