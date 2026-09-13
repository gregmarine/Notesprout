package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 66-book table (arc 37 / B1). It must agree, book for book, with the
 * `book` table `tools/bible/build_bible_db.py` wrote — chapter counts excepted,
 * which live only in the database ([BibleDatabase.books]) and never here.
 */
class CanonTest {

    @Test
    fun `the canon is sixty-six books`() {
        assertEquals(66, Canon.books.size)
    }

    @Test
    fun `ordinals run one to sixty-six in order`() {
        Canon.books.forEachIndexed { index, book -> assertEquals(index + 1, book.ordinal) }
        assertEquals("GEN", Canon.books.first().usfm)
        assertEquals(1, Canon.byUsfm("GEN").ordinal)
        assertEquals("REV", Canon.books.last().usfm)
        assertEquals(66, Canon.byUsfm("REV").ordinal)
    }

    @Test
    fun `the testaments split thirty-nine and twenty-seven`() {
        assertEquals(39, Canon.books.count { it.testament == Testament.OLD })
        assertEquals(27, Canon.books.count { it.testament == Testament.NEW })
        // And the split is a clean cut, not a scatter: Malachi ends the OT.
        assertEquals("MAL", Canon.books.last { it.testament == Testament.OLD }.usfm)
        assertEquals("MAT", Canon.books.first { it.testament == Testament.NEW }.usfm)
    }

    @Test
    fun `usfm codes are unique and three characters`() {
        assertEquals(66, Canon.books.map { it.usfm }.toSet().size)
        assertTrue(Canon.books.all { it.usfm.length == 3 })
    }

    @Test
    fun `lookup is case-insensitive and ordinal-addressed`() {
        assertEquals("Psalms", Canon.byUsfm("psa").name)
        assertEquals("Psalms", Canon.byOrdinal(19).name)
        assertEquals("1 Corinthians", Canon.byUsfm("1CO").name)
        assertNull(Canon.tryUsfm("XXX"))
    }
}
