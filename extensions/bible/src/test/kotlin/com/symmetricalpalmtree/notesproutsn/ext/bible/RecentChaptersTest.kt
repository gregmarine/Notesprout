package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Recents panel's arithmetic (arc 37 / B7, grown by arc 38 / R2) — the notebook
 * `RecentRowsTest` in the extension's subject: the two histories merged by when they happened,
 * the chapter or passage being read dropped, duplicates collapsed, nothing invented, the row
 * label in the running head's / the reference's own form, and the paging maths.
 */
class RecentChaptersTest {

    private fun r(usfm: String, chapter: Int, at: Long) = RecentRef(usfm, chapter, at)

    private fun ref(wire: String, at: Long) =
        RecentEntry.Reference(wire, ReferenceCodec.decode(wire)!!, at)

    private fun select(
        chapters: List<RecentRef> = emptyList(),
        references: List<RecentEntry.Reference> = emptyList(),
        currentChapter: ChapterRef? = null,
        currentReference: String? = null,
    ) = RecentChapters.select(chapters, references, currentChapter, currentReference)

    private fun names(rows: List<RecentEntry>) = rows.map { RecentChapters.label(it) }

    @Test
    fun `the canon never reorders a history`() {
        val stored = listOf(r("REV", 22, 9L), r("GEN", 1, 5L), r("PSA", 23, 1L))
        assertEquals(listOf("REV:22", "GEN:1", "PSA:23"), keys(select(chapters = stored)))
    }

    @Test
    fun `the two histories merge by when they happened`() {
        val chapters = listOf(r("REV", 22, 9L), r("GEN", 1, 4L))
        val references = listOf(ref("JHN:3:16-3:16", 7L), ref("PRO:3:5-3:6", 2L))
        assertEquals(
            listOf("Revelation 22", "John 3:16", "Genesis 1", "Proverbs 3:5\u20136"),
            names(select(chapters, references)),
        )
    }

    @Test
    fun `a tie keeps each table's stored order`() {
        val chapters = listOf(r("GEN", 1, 5L))
        val references = listOf(ref("JHN:3:16-3:16", 5L))
        assertEquals(listOf("Genesis 1", "John 3:16"), names(select(chapters, references)))
    }

    @Test
    fun `the chapter being read is never offered`() {
        val stored = listOf(r("PSA", 23, 3L), r("GEN", 1, 2L), r("psa", 23, 1L))
        assertEquals(listOf("GEN:1"), keys(select(stored, currentChapter = ChapterRef("PSA", 23))))
    }

    @Test
    fun `the passage being read is never offered`() {
        val references = listOf(ref("JHN:3:16-3:16", 9L), ref("PRO:3:5-3:6", 8L))
        assertEquals(
            listOf("Proverbs 3:5\u20136"),
            names(select(references = references, currentReference = "JHN:3:16-3:16")),
        )
    }

    @Test
    fun `the passage being read does not hide the chapter it sits in`() {
        val chapters = listOf(r("JHN", 3, 9L))
        val references = listOf(ref("JHN:3:16-3:16", 8L))
        assertEquals(
            listOf("John 3"),
            names(select(chapters, references, currentReference = "JHN:3:16-3:16")),
        )
    }

    @Test
    fun `a different chapter of the same book stays`() {
        val stored = listOf(r("PSA", 23, 3L), r("PSA", 24, 2L))
        assertEquals(listOf("PSA:24"), keys(select(stored, currentChapter = ChapterRef("PSA", 23))))
    }

    @Test
    fun `duplicates collapse to the newest, in each history`() {
        val chapters = listOf(r("GEN", 1, 9L), r("EXO", 2, 8L), r("GEN", 1, 1L))
        val references = listOf(ref("JHN:3:16-3:16", 7L), ref("JHN:3:16-3:16", 3L))
        assertEquals(listOf(9L, 8L, 7L), select(chapters, references).map { it.at })
    }

    @Test
    fun `the merged list is bounded by KEEP`() {
        val chapters = (1..20).map { r("PSA", it, it.toLong()) }
        val references = (1..20).map { ref("JHN:$it:1-$it:2", 100L + it) }
        assertEquals(RecentChapters.KEEP, select(chapters, references).size)
    }

    @Test
    fun `nothing is invented`() {
        assertTrue(select(currentChapter = ChapterRef("GEN", 1)).isEmpty())
    }

    @Test
    fun `a row names its chapter as the running head does`() {
        assertEquals("Psalm 23", RecentChapters.label(ChapterRef("PSA", 23)))
        assertEquals("Genesis 1", RecentChapters.label(ChapterRef("GEN", 1)))
        assertEquals("Song of Solomon 2", RecentChapters.label(ChapterRef("SNG", 2)))
        assertEquals("Psalm 23", RecentChapters.label(RecentEntry.Chapter(ChapterRef("PSA", 23), 0L)))
    }

    @Test
    fun `a reference row names itself canonically`() {
        assertEquals(
            "John 3:14\u201318; Proverbs 3:5\u20136",
            RecentChapters.label(ref("JHN:3:14-3:18,PRO:3:5-3:6", 0L)),
        )
        assertEquals("Genesis 1", RecentChapters.label(ref("GEN:1:0-1:999", 0L)))
    }

    @Test
    fun `a stored row this build cannot read is dropped, not thrown`() {
        assertNull(RecentRef.of("XYZ", 1, 0L))
        assertNull(RecentRef.of("GEN", 0, 0L))
        assertEquals(RecentRef("GEN", 3, 7L), RecentRef.of("gen", 3, 7L))
    }

    @Test
    fun `the panel is half the window, the Contents' breakpoint`() {
        assertEquals(702, RecentChapters.sidebarWidthPx(1404))
        assertTrue(RecentChapters.SIDEBAR_WIDTH_FRACTION < ContentsLayout.SIDEBAR_WIDTH_FRACTION)
    }

    @Test
    fun `rows per page are whole rows, at least one, and safe on an unmeasured row`() {
        assertEquals(3, RecentChapters.itemsPerPage(bodyHeightPx = 350, rowHeightPx = 100))
        assertEquals(1, RecentChapters.itemsPerPage(bodyHeightPx = 50, rowHeightPx = 100))
        assertEquals(1, RecentChapters.itemsPerPage(bodyHeightPx = 500, rowHeightPx = 0))
    }

    @Test
    fun `the history is bounded`() {
        assertEquals(30, RecentChapters.KEEP)
    }

    private fun keys(rows: List<RecentEntry>) = rows.map {
        val chapter = it as RecentEntry.Chapter
        "${chapter.ref.usfm}:${chapter.ref.chapter}"
    }
}
