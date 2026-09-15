package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Notes panel's arithmetic (arc 42 "Notes" / N1): the scope the reader is standing in, the
 * overlap test that is `SELECT_NOTES`' twin, and the grouping that turns rows into the things
 * the user wrote on.
 */
class NotesModelTest {

    private val nbA = "11111111-1111-4111-8111-111111111111"
    private val nbB = "22222222-2222-4222-8222-222222222222"
    private val p1 = "33333333-3333-4333-8333-333333333333"
    private val p2 = "44444444-4444-4444-8444-444444444444"

    private fun row(
        noteId: String,
        wire: String,
        rangeIx: Int = 0,
        notebookId: String = nbA,
        pageId: String = p1,
        kind: Int = BibleSql.KIND_LINK.toInt(),
        notebookName: String = "Study",
        pageNumber: Int = 4,
        at: Long = 100L,
    ) = NoteRow(noteId, rangeIx, notebookId, pageId, kind, wire, notebookName, pageNumber, at)

    // --- scope --------------------------------------------------------------

    @Test
    fun `a chapter scopes its whole verse band`() {
        val scope = NotesModel.scope(ChapterRef("JHN", 3), null)
        assertEquals(listOf(VerseRange(VerseKey.encode(43, 3, 0), VerseKey.encode(43, 3, VerseKey.MAX_VERSE))), scope)
    }

    @Test
    fun `a passage scopes every range of every passage, and wins over the chapter`() {
        val passages = ReferenceCodec.decode("JHN:3:14-3:18,PRO:3:5-3:6")!!
        val scope = NotesModel.scope(ChapterRef("GEN", 1), passages)
        assertEquals(
            listOf(
                VerseRange(VerseKey.encode(43, 3, 14), VerseKey.encode(43, 3, 18)),
                VerseRange(VerseKey.encode(20, 3, 5), VerseKey.encode(20, 3, 6)),
            ),
            scope,
        )
    }

    @Test
    fun `nothing open is an empty scope`() {
        assertTrue(NotesModel.scope(null, null).isEmpty())
        assertTrue(NotesModel.scope(null, emptyList()).isEmpty())
        assertTrue(NotesModel.scope(ChapterRef("XYZ", 1), null).isEmpty())
    }

    // --- overlaps -----------------------------------------------------------

    @Test
    fun `a whole-chapter row overlaps any verse of that chapter`() {
        val whole = row("n", "GEN:1:0-1:999")
        assertTrue(NotesModel.overlaps(whole, VerseRange.verses(1, 1, 3, 5)))
        assertTrue(NotesModel.overlaps(whole, VerseRange.verse(1, 1, 1)))
        assertFalse(NotesModel.overlaps(whole, VerseRange.verses(1, 2, 1, 4)))
    }

    @Test
    fun `a verse row overlaps only what it touches`() {
        val john = row("n", "JHN:3:14-3:18")
        assertTrue(NotesModel.overlaps(john, VerseRange.verses(43, 3, 18, 20)))   // touching at one end
        assertTrue(NotesModel.overlaps(john, VerseRange.chapters(43, 3, 3)))      // the whole chapter
        assertFalse(NotesModel.overlaps(john, VerseRange.verses(43, 3, 19, 21)))
        assertFalse(NotesModel.overlaps(john, VerseRange.chapters(43, 4, 4)))
    }

    @Test
    fun `the range index picks the row's own range across passages`() {
        val wire = "JHN:3:14-3:18,PRO:3:5-3:6"
        assertTrue(NotesModel.overlaps(row("n", wire, rangeIx = 1), VerseRange.verse(20, 3, 5)))
        assertFalse(NotesModel.overlaps(row("n", wire, rangeIx = 1), VerseRange.verse(43, 3, 16)))
    }

    @Test
    fun `a row this build cannot place overlaps nothing`() {
        assertFalse(NotesModel.overlaps(row("n", "not a wire"), VerseRange.verse(43, 3, 16)))
        assertFalse(NotesModel.overlaps(row("n", "JHN:3:14-3:18", rangeIx = 7), VerseRange.verse(43, 3, 16)))
        assertFalse(NotesModel.overlaps(row("n", "JHN:3:14-3:18", rangeIx = -1), VerseRange.verse(43, 3, 16)))
    }

    // --- group --------------------------------------------------------------

    @Test
    fun `the same row read by two ranges is one note`() {
        val wire = "JHN:3:14-3:18"
        val groups = NotesModel.group(listOf(row("a", wire), row("a", wire)))
        assertEquals(1, groups.size)
        assertEquals(listOf("John 3:14–18"), groups[0].labels)
    }

    @Test
    fun `a multi-range link labels itself once`() {
        val wire = "JHN:3:14-3:18,JHN:3:20-3:21"
        val groups = NotesModel.group(listOf(row("a", wire, rangeIx = 0), row("a", wire, rangeIx = 1)))
        assertEquals(1, groups.size)
        assertEquals(listOf("John 3:14–18, 20–21"), groups[0].labels)
    }

    @Test
    fun `one page's references read as one entry, in reading order`() {
        val groups = NotesModel.group(
            listOf(
                row("b", "JHN:3:16-3:16", at = 50L),
                row("a", "JHN:3:14-3:14", at = 90L),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("John 3:14", "John 3:16"), groups[0].labels)
        assertEquals(90L, groups[0].newestAt)
        assertEquals(VerseKey.encode(43, 3, 14), groups[0].firstStartKey)
        assertEquals("Study", groups[0].notebookName)
        assertEquals(4, groups[0].pageNumber)
    }

    @Test
    fun `a page, another page and a document are three entries`() {
        val groups = NotesModel.group(
            listOf(
                row("a", "JHN:3:16-3:16", pageId = p1),
                row("b", "JHN:3:16-3:16", pageId = p2, pageNumber = 9),
                row("c", "JHN:3:16-3:16", notebookId = nbB, pageId = "", kind = BibleSql.KIND_DOCUMENT.toInt()),
            ),
        )
        assertEquals(3, groups.size)
        assertEquals(setOf(p1, p2, ""), groups.map { it.pageId }.toSet())
    }

    @Test
    fun `groups sort by where they point, then newest first`() {
        val groups = NotesModel.group(
            listOf(
                row("a", "JHN:3:16-3:16", pageId = p1, at = 10L),
                row("b", "JHN:3:16-3:16", pageId = p2, pageNumber = 9, at = 80L),
                row("c", "JHN:3:2-3:2", notebookId = nbB, pageId = p1, pageNumber = 1, at = 20L),
            ),
        )
        assertEquals(listOf(VerseKey.encode(43, 3, 2), VerseKey.encode(43, 3, 16), VerseKey.encode(43, 3, 16)),
            groups.map { it.firstStartKey })
        assertEquals(listOf(20L, 80L, 10L), groups.map { it.newestAt })
    }

    @Test
    fun `a row no range can be derived for is dropped whole`() {
        val groups = NotesModel.group(listOf(row("a", "JHN:3:16-3:16", rangeIx = 4)))
        assertTrue(groups.isEmpty())
    }

    // --- the two lines ------------------------------------------------------

    private fun group(pageId: String, kind: Int) = NotesModel.NoteGroup(
        notebookId = nbA, pageId = pageId, kind = kind, notebookName = "Study", pageNumber = 4,
        labels = listOf("John 3:14–18", "Proverbs 3:5–6"), newestAt = 1L, firstStartKey = 1,
    )

    @Test
    fun `a page names its number and a notebook-level document says so instead`() {
        assertEquals("Study · Page 4", NotesModel.title(group(p1, BibleSql.KIND_LINK.toInt()), "Page", "Document"))
        assertEquals("Study · Document", NotesModel.title(group("", BibleSql.KIND_DOCUMENT.toInt()), "Page", "Document"))
    }

    @Test
    fun `the detail lists the references and only a page's document says which half it is`() {
        assertEquals(
            "John 3:14–18; Proverbs 3:5–6 · 14 Sep 2026",
            NotesModel.detail(group(p1, BibleSql.KIND_LINK.toInt()), "14 Sep 2026", "Document"),
        )
        assertEquals(
            "Document · John 3:14–18; Proverbs 3:5–6 · 14 Sep 2026",
            NotesModel.detail(group(p1, BibleSql.KIND_DOCUMENT.toInt()), "14 Sep 2026", "Document"),
        )
        // The title already said "Document" for a notebook-level row; the detail does not repeat it.
        assertEquals(
            "John 3:14–18; Proverbs 3:5–6 · 14 Sep 2026",
            NotesModel.detail(group("", BibleSql.KIND_DOCUMENT.toInt()), "14 Sep 2026", "Document"),
        )
    }

    // --- the panel's measurements -------------------------------------------

    @Test
    fun `the sidebar is sixty per cent of the window`() {
        assertEquals(449, NotesModel.sidebarWidthPx(749))
        assertEquals(0, NotesModel.sidebarWidthPx(0))
    }
}
