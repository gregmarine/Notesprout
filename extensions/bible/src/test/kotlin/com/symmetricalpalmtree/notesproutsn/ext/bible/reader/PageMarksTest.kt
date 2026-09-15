package com.symmetricalpalmtree.notesproutsn.ext.bible.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tap's hit rules (arc 41), JVM-side — what a char offset under a finger resolves to. */
class PageMarksTest {

    private val reference = PageMark.Reference(start = 1, end = 23, targetStartKey = 22001001, targetEndKey = 22001017)
    private val caller = PageMark.Caller(start = 40, end = 41, footnoteId = 7)
    private val marks = listOf(reference, caller)

    @Test
    fun `a reference hits on every char of its span and not on the one after`() {
        assertEquals(reference, PageMarks.at(marks, 1))
        assertEquals(reference, PageMarks.at(marks, 12))
        assertEquals(reference, PageMarks.at(marks, 22))
        assertNull(PageMarks.at(marks, 23))
        assertNull(PageMarks.at(marks, 0))
    }

    @Test
    fun `a one-glyph caller also hits on the boundary right after it`() {
        assertEquals(caller, PageMarks.at(marks, 40))
        assertEquals(caller, PageMarks.at(marks, 41))
        assertNull(PageMarks.at(marks, 42))
        assertNull(PageMarks.at(marks, 39))
    }

    @Test
    fun `no marks means no hit`() {
        assertNull(PageMarks.at(emptyList(), 5))
    }
}
