package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.BibleNote
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 42 "Notes": the one wire reader of the index — a pushed wire becomes one row per range. */
class NoteRowsTest {

    private val link = "11111111-1111-4111-8111-111111111111"
    private val page = "22222222-2222-4222-8222-222222222222"
    private val nb = "33333333-3333-4333-8333-333333333333"

    @Test
    fun aTwoBookReferenceIsTwoRowsWithRunningRangeIndexes() {
        val note = BibleNote(link, page, 3, "JHN:3:14-3:18,PRO:3:5-3:6", 42L)
        val rows = NoteRows.inserts(note, nb, "Study")
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(BibleSql.INSERT_NOTE, it.sql) }
        val john = rows[0].args
        assertEquals(cells(link, 0L, nb, page, BibleSql.KIND_LINK, "JHN:3:14-3:18,PRO:3:5-3:6",
            VerseKey.encode(43, 3, 14).toLong(), VerseKey.encode(43, 3, 18).toLong(), "Study", 3L, 42L), john)
        val prov = rows[1].args
        assertEquals(Cell.of(1L), prov[1])
        assertEquals(Cell.of(VerseKey.encode(20, 3, 5).toLong()), prov[6])
        assertEquals(Cell.of(VerseKey.encode(20, 3, 6).toLong()), prov[7])
    }

    @Test
    fun aWholeChapterKeepsTheCodecsSentinels() {
        val rows = NoteRows.inserts(BibleNote(link, page, 1, "GEN:1:0-1:999", 0L), nb, "n")
        assertEquals(1, rows.size)
        assertEquals(Cell.of(VerseKey.encode(1, 1, 0).toLong()), rows[0].args[6])
        assertEquals(Cell.of(VerseKey.encode(1, 1, VerseKey.MAX_VERSE).toLong()), rows[0].args[7])
    }

    @Test
    fun anUnreadableWireYieldsNothing() {
        assertTrue(NoteRows.inserts(BibleNote(link, page, 1, "XXX:1:1-1:1", 0L), nb, "n").isEmpty())
    }

    @Test
    fun aDocumentRowIsKeyedByItsDocumentAndReference() {
        assertEquals("doc:$page:JHN:3:16-3:16", NoteRows.documentNoteId(nb, page, "JHN:3:16-3:16"))
        assertEquals("doc:$nb:JHN:3:16-3:16", NoteRows.documentNoteId(nb, "", "JHN:3:16-3:16"))
        val rows = NoteRows.documentInserts("doc:x", nb, "", "JHN:3:16-3:16", "Text doc", 0, 7L)
        assertEquals(1, rows.size)
        assertEquals(Cell.of(BibleSql.KIND_DOCUMENT), rows[0].args[4])
        assertEquals(Cell.of(""), rows[0].args[3])
        assertEquals(Cell.of(0L), rows[0].args[9])
    }

    private fun cells(vararg values: Any?): List<Cell> = values.map { Cell.of(it) }
}
