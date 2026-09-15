package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [BibleNote]'s and [BibleNoteTarget]'s constructor `require`s (arc 42 "Notes") — unmarshal is the
 * validation, the family rule; a real `Parcel` round trip is not available on the plain JVM, so
 * what is pinned is the gate either side of the wire ([TagRecordTest]'s shape).
 */
class BibleNoteTest {

    private val link = "11111111-1111-4111-8111-111111111111"
    private val page = "22222222-2222-4222-8222-222222222222"
    private val notebook = "33333333-3333-4333-8333-333333333333"

    private fun refused(build: () -> Any) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aNoteCarriesItsFields() {
        val note = BibleNote(link, page, 3, "JHN:3:14-3:18,PRO:3:5-3:6", 1_700_000_000_000L)
        assertEquals(link, note.noteId)
        assertEquals(page, note.pageId)
        assertEquals(3, note.pageNumber)
        assertEquals("JHN:3:14-3:18,PRO:3:5-3:6", note.wire)
        assertEquals(1_700_000_000_000L, note.at)
    }

    @Test
    fun aNoteRefusesWhatCannotBeIndexed() {
        refused { BibleNote("l1", page, 1, "JHN:3:16-3:16", 0L) }
        refused { BibleNote(link, "", 1, "JHN:3:16-3:16", 0L) }
        refused { BibleNote(link, page, 0, "JHN:3:16-3:16", 0L) }
        refused { BibleNote(link, page, 1, "John 3:16", 0L) }
        refused { BibleNote(link, page, 1, "", 0L) }
        refused { BibleNote(link, page, 1, "JHN:3:16-3:16", -1L) }
    }

    @Test
    fun theWireIsNeverInToString() {
        val note = BibleNote(link, page, 3, "JHN:3:14-3:18", 0L)
        assertFalse("JHN" in note.toString())
    }

    @Test
    fun aTargetNamesItsPageOrTheNotebook() {
        val onPage = BibleNoteTarget(notebook, page, ExtensionContract.BIBLE_NOTE_KIND_LINK)
        assertEquals(page, onPage.pageId)
        assertFalse(onPage.isDocument)
        val document = BibleNoteTarget(notebook, "", ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT)
        assertTrue(document.isDocument)
        assertEquals("", document.pageId)
    }

    @Test
    fun aTargetRefusesTheMalformed() {
        refused { BibleNoteTarget("n1", page, ExtensionContract.BIBLE_NOTE_KIND_LINK) }
        refused { BibleNoteTarget(notebook, "p1", ExtensionContract.BIBLE_NOTE_KIND_LINK) }
        refused { BibleNoteTarget(notebook, page, 7) }
        // A link row always names its page.
        refused { BibleNoteTarget(notebook, "", ExtensionContract.BIBLE_NOTE_KIND_LINK) }
    }

    @Test
    fun theContractPinsTheNotesConstants() {
        assertEquals("bibleNotesEnabled", ExtensionContract.EXTRA_BIBLE_NOTES_ENABLED)
        assertEquals(3, ExtensionContract.RESULT_BIBLE_OPEN_NOTE)
        assertEquals(4, ExtensionContract.RESULT_BIBLE_REBUILD_NOTES)
        assertEquals(0, ExtensionContract.BIBLE_NOTE_KIND_LINK)
        assertEquals(1, ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT)
        assertEquals(1_000, ExtensionContract.BIBLE_NOTES_PER_CALL)
        assertEquals(10_000, ExtensionContract.BIBLE_NOTE_IDS_PER_CALL)
        assertEquals(200, ExtensionContract.BIBLE_NOTE_MAX_NAME_CHARS)
    }
}
