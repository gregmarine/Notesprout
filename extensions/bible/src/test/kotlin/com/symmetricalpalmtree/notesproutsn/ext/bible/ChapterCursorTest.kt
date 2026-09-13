package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The canon as one chain of chapters (arc 37 / B2) — the walk the reader's chapter flow takes
 * when a swipe runs off either end of a chapter.
 */
class ChapterCursorTest {

    /** The real shape: every book present, with its real chapter count for the ones under test. */
    private val whole = ChapterCursor(
        Canon.books.associate { it.usfm to (REAL_COUNTS[it.usfm] ?: 1) },
    )

    @Test
    fun `inside a book it is simply the next and the previous chapter`() {
        assertEquals(ChapterRef("GEN", 2), whole.next(ChapterRef("GEN", 1)))
        assertEquals(ChapterRef("GEN", 49), whole.next(ChapterRef("GEN", 48)))
        assertEquals(ChapterRef("GEN", 48), whole.prev(ChapterRef("GEN", 49)))
    }

    @Test
    fun `the last chapter of a book flows into the first of the next`() {
        assertEquals(ChapterRef("EXO", 1), whole.next(ChapterRef("GEN", 50)))
    }

    @Test
    fun `the first chapter of a book flows back into the LAST of the previous`() {
        assertEquals(ChapterRef("GEN", 50), whole.prev(ChapterRef("EXO", 1)))
    }

    @Test
    fun `Genesis 1 has nothing before it`() {
        assertNull(whole.prev(ChapterRef("GEN", 1)))
    }

    @Test
    fun `the last chapter of the last book has nothing after it`() {
        assertNull(whole.next(ChapterRef("REV", 22)))
    }

    @Test
    fun `a book the source does not carry is skipped in both directions`() {
        val withoutExodus = ChapterCursor(
            REAL_COUNTS.filterKeys { it != "EXO" },
        )
        assertEquals(ChapterRef("LEV", 1), withoutExodus.next(ChapterRef("GEN", 50)))
        assertEquals(ChapterRef("GEN", 50), withoutExodus.prev(ChapterRef("LEV", 1)))
    }

    @Test
    fun `a count of zero is no book at all, and an unknown code walks nowhere`() {
        val zeroed = ChapterCursor(REAL_COUNTS + ("EXO" to 0))
        assertEquals(ChapterRef("LEV", 1), zeroed.next(ChapterRef("GEN", 50)))
        assertNull(whole.next(ChapterRef("NOPE", 1)))
        assertNull(whole.prev(ChapterRef("NOPE", 1)))
    }

    private companion object {
        /** Only the books these tests walk across need their real length. */
        val REAL_COUNTS = mapOf(
            "GEN" to 50,
            "EXO" to 40,
            "LEV" to 27,
            "REV" to 22,
        )
    }
}
