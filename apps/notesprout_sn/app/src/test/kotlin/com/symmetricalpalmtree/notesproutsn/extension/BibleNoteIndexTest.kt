package com.symmetricalpalmtree.notesproutsn.extension

import com.symmetricalpalmtree.notesproutsn.data.soil.LinkRow
import com.symmetricalpalmtree.notesproutsn.notebook.LinkPayload
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookSession
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import com.symmetricalpalmtree.notesproutsn.notebook.PageLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 42 "Notes": the host's pure half of the notes index. */
class BibleNoteIndexTest {

    private val page = "22222222-2222-4222-8222-222222222222"
    private val page2 = "44444444-4444-4444-8444-444444444444"
    private val nb = "33333333-3333-4333-8333-333333333333"
    private val bible = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, "JHN:3:16-3:16", null)
    private val verses = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE_TEXT, "PSA:23:1-23:3", null)
    private val pageLink = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, page)

    private fun link(id: String, payload: String, at: Long = 5L) = PageLink(
        id = id, payload = payload, chrome = LinkPayload.CHROME_UNDERLINE,
        x = 0f, y = 0f, width = 10f, height = 10f, order = 0,
        strokes = emptyList(), headings = emptyList(), createdAt = at,
    )

    @Test
    fun onlyTheTwoBibleKindsBecomeNotes() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "55555555-5555-4555-8555-555555555555"
        val c = "66666666-6666-4666-8666-666666666666"
        val notes = BibleNoteIndex.linkNotes(listOf(link(a, bible), link(b, verses), link(c, pageLink)), page, 2)
        assertEquals(listOf(a, b), notes.map { it.noteId })
        assertEquals("JHN:3:16-3:16", notes[0].wire)
        assertEquals("PSA:23:1-23:3", notes[1].wire)
        assertTrue(notes.all { it.pageId == page && it.pageNumber == 2 && it.at == 5L })
    }

    @Test
    fun aLinkWithAnUnusableIdIsSkippedNotThrown() {
        assertTrue(BibleNoteIndex.linkNotes(listOf(link("not-an-id", bible)), page, 1).isEmpty())
    }

    @Test
    fun notebookNotesDropDeadPagesAndNumberLivePages() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "55555555-5555-4555-8555-555555555555"
        val gone = "77777777-7777-4777-8777-777777777777"
        val rows = listOf(
            LinkRow(a, page2, bible, 9L),
            LinkRow(b, page, verses, 8L),
            LinkRow("88888888-8888-4888-8888-888888888888", gone, bible, 7L),
            LinkRow("99999999-9999-4999-8999-999999999999", page, pageLink, 6L),
            LinkRow("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", page, null, 6L),
        )
        val notes = BibleNoteIndex.notebookNotes(rows, listOf(page, page2))
        assertEquals(listOf(a, b), notes.map { it.noteId })
        assertEquals(2, notes[0].pageNumber)
        assertEquals(1, notes[1].pageNumber)
    }

    @Test
    fun theUndoTableNamesWhatTouchesLinks() {
        val l = link("11111111-1111-4111-8111-111111111111", bible)
        assertTrue(BibleNoteIndex.mayTouchLinks(Action.LinkCreated(page, l)))
        assertTrue(BibleNoteIndex.mayTouchLinks(Action.LinkUnlinked(page, l)))
        assertTrue(BibleNoteIndex.mayTouchLinks(Action.PageErased(page, listOf(l.id))))
        assertFalse(BibleNoteIndex.mayTouchLinks(Action.HeadingDeleted(page, emptyList())))
        val structural = Action.Page(NotebookSession.Structural(listOf(page), listOf(page, page2), emptyList(), page, page2))
        assertFalse(BibleNoteIndex.mayTouchLinks(structural))
        assertTrue(BibleNoteIndex.isStructural(structural))
        assertFalse(BibleNoteIndex.isStructural(Action.LinkCreated(page, l)))
    }
}
