package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's SQL, pinned as exact text (arc 37 / B0, grown by B7 and by arc 38 / R2) — the tag
 * manager's `TagSqlTest` style. `StoreSchema` validates DDL at construction, so constructing
 * [BibleSchema.V3] is itself a check that the host's validator would accept it; the statements
 * are run through the host's query/exec gate here so a shape it refuses fails on the JVM, never
 * at the seam.
 */
class BibleSqlTest {

    @Test
    fun selectState_isPinned() {
        assertEquals("SELECT value FROM state WHERE key = ?", BibleSql.SELECT_STATE)
    }

    @Test
    fun upsertState_isPinned() {
        assertEquals("INSERT OR REPLACE INTO state(key, value) VALUES (?, ?)", BibleSql.UPSERT_STATE)
    }

    @Test
    fun keyPosition_isPinned() {
        assertEquals("position", BibleSql.KEY_POSITION)
    }

    @Test
    fun recentStatements_arePinned() {
        assertEquals("SELECT usfm, chapter, at FROM recent ORDER BY at DESC LIMIT ?", BibleSql.SELECT_RECENTS)
        assertEquals("INSERT OR REPLACE INTO recent(usfm, chapter, at) VALUES (?, ?, ?)", BibleSql.UPSERT_RECENT)
        assertEquals(
            "DELETE FROM recent WHERE rowid NOT IN (SELECT rowid FROM recent ORDER BY at DESC LIMIT ?)",
            BibleSql.TRIM_RECENTS,
        )
    }

    @Test
    fun `reference statements are pinned`() {
        assertEquals("SELECT ref, at FROM recent_ref ORDER BY at DESC LIMIT ?", BibleSql.SELECT_RECENT_REFS)
        assertEquals("INSERT OR REPLACE INTO recent_ref(ref, at) VALUES (?, ?)", BibleSql.UPSERT_RECENT_REF)
        assertEquals(
            "DELETE FROM recent_ref WHERE rowid NOT IN " +
                "(SELECT rowid FROM recent_ref ORDER BY at DESC LIMIT ?)",
            BibleSql.TRIM_RECENT_REFS,
        )
    }

    /** The host gates every statement by kind; each of ours must pass the gate it is sent through. */
    @Test
    fun everyStatementPassesTheHostGate() {
        StoreSql.checkQuery(BibleSql.SELECT_STATE)
        StoreSql.checkQuery(BibleSql.SELECT_RECENTS)
        StoreSql.checkQuery(BibleSql.SELECT_RECENT_REFS)
        StoreSql.checkExec(BibleSql.UPSERT_STATE)
        StoreSql.checkExec(BibleSql.UPSERT_RECENT)
        StoreSql.checkExec(BibleSql.TRIM_RECENTS)
        StoreSql.checkExec(BibleSql.UPSERT_RECENT_REF)
        StoreSql.checkExec(BibleSql.TRIM_RECENT_REFS)
        StoreSql.checkQuery(BibleSql.SELECT_NOTES)
        StoreSql.checkQuery(BibleSql.SELECT_NOTE_NOTEBOOKS)
        StoreSql.checkExec(BibleSql.INSERT_NOTE)
        StoreSql.checkExec(BibleSql.DELETE_PAGE_KIND_NOTES)
        StoreSql.checkExec(BibleSql.DELETE_NOTEBOOK_KIND_NOTES)
        StoreSql.checkExec(BibleSql.DELETE_NOTEBOOK_NOTES)
        StoreSql.checkExec(BibleSql.RENAME_NOTES)
        StoreSql.checkExec(BibleSql.RENUMBER_PAGE_NOTES)
        StoreSql.checkExec(BibleSql.UNNUMBER_NOTES)
        StoreSql.checkExec(BibleSql.DELETE_UNNUMBERED_NOTES)
    }

    /** Arc 42 "Notes": every statement of the index, verbatim. */
    @Test
    fun `note statements are pinned`() {
        assertEquals(0L, BibleSql.KIND_LINK)
        assertEquals(1L, BibleSql.KIND_DOCUMENT)
        assertEquals(
            "SELECT noteId, rangeIx, notebookId, pageId, kind, wire, notebookName, pageNumber, at " +
                "FROM note_ref WHERE startKey <= ? AND endKey >= ? ORDER BY startKey, at DESC LIMIT ?",
            BibleSql.SELECT_NOTES,
        )
        assertEquals(
            "INSERT OR REPLACE INTO note_ref(noteId, rangeIx, notebookId, pageId, kind, wire, " +
                "startKey, endKey, notebookName, pageNumber, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            BibleSql.INSERT_NOTE,
        )
        assertEquals("DELETE FROM note_ref WHERE notebookId = ? AND pageId = ? AND kind = ?", BibleSql.DELETE_PAGE_KIND_NOTES)
        assertEquals("DELETE FROM note_ref WHERE notebookId = ? AND kind = ?", BibleSql.DELETE_NOTEBOOK_KIND_NOTES)
        assertEquals("DELETE FROM note_ref WHERE notebookId = ?", BibleSql.DELETE_NOTEBOOK_NOTES)
        assertEquals("UPDATE note_ref SET notebookName = ? WHERE notebookId = ?", BibleSql.RENAME_NOTES)
        assertEquals("UPDATE note_ref SET pageNumber = ? WHERE notebookId = ? AND pageId = ?", BibleSql.RENUMBER_PAGE_NOTES)
        assertEquals("UPDATE note_ref SET pageNumber = 0 WHERE notebookId = ? AND LENGTH(pageId) > 0", BibleSql.UNNUMBER_NOTES)
        assertEquals(
            "DELETE FROM note_ref WHERE notebookId = ? AND LENGTH(pageId) > 0 AND pageNumber = 0",
            BibleSql.DELETE_UNNUMBERED_NOTES,
        )
        assertEquals("SELECT DISTINCT notebookId FROM note_ref", BibleSql.SELECT_NOTE_NOTEBOOKS)
    }

    /** V1 is exactly what B0 shipped — a landed step is never edited. */
    @Test
    fun v1IsOneVersionOfOneStatement() {
        assertEquals(1, BibleSchema.V1.version)
        val step = BibleSchema.V1.steps.single()
        assertEquals(1, step.size)
        assertTrue(step[0].startsWith("CREATE TABLE state"))
        assertTrue("the primary key", "key TEXT PRIMARY KEY" in step[0])
        assertTrue("the value column", "value TEXT NOT NULL" in step[0])
    }

    /** V2 is V1's step untouched plus the recents step; constructing it IS the DDL validator run. */
    @Test
    fun v2IsV1PlusTheRecentStep() {
        assertEquals(2, BibleSchema.V2.version)
        assertEquals(2, BibleSchema.V2.steps.size)
        assertEquals(BibleSchema.V1.steps.single(), BibleSchema.V2.steps[0])
        val step = BibleSchema.V2.steps[1]
        assertEquals(BibleSchema.RECENT_STEP, step)
        assertEquals(1, step.size)
        assertTrue(step[0].startsWith("CREATE TABLE recent"))
        assertTrue("the chapter is the key", "PRIMARY KEY (usfm, chapter)" in step[0])
        assertTrue("the stamp", "at INTEGER NOT NULL" in step[0])
    }

    /** V3 is V2's two steps untouched plus the reference-recents step — a landed step is never
     *  edited — and it is what every call declares. */
    @Test
    fun v3IsV2PlusTheRecentReferenceStep() {
        assertEquals(3, BibleSchema.V3.version)
        assertEquals(3, BibleSchema.V3.steps.size)
        assertEquals(BibleSchema.V2.steps[0], BibleSchema.V3.steps[0])
        assertEquals(BibleSchema.V2.steps[1], BibleSchema.V3.steps[1])
        val step = BibleSchema.V3.steps[2]
        assertEquals(BibleSchema.RECENT_REF_STEP, step)
        assertEquals(1, step.size)
        assertEquals(
            "CREATE TABLE recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL);",
            step[0],
        )
    }

    /** V4 is V3's three steps untouched plus the notes step — a landed step is never edited —
     *  and it is what every call declares. */
    @Test
    fun v4IsV3PlusTheNoteStep() {
        assertEquals(4, BibleSchema.V4.version)
        assertEquals(4, BibleSchema.V4.steps.size)
        for (i in 0 until 3) assertEquals(BibleSchema.V3.steps[i], BibleSchema.V4.steps[i])
        val step = BibleSchema.V4.steps[3]
        assertEquals(BibleSchema.NOTE_STEP, step)
        assertEquals(3, step.size)
        assertTrue(step[0].startsWith("CREATE TABLE note_ref"))
        assertTrue("the pair is the key", "PRIMARY KEY (noteId, rangeIx)" in step[0])
        assertTrue("the span", "startKey INTEGER NOT NULL, endKey INTEGER NOT NULL" in step[0])
        assertEquals("CREATE INDEX note_ref_span ON note_ref (startKey, endKey);", step[1])
        assertEquals("CREATE INDEX note_ref_target ON note_ref (notebookId, pageId);", step[2])
        assertEquals(BibleSchema.V4, BibleSchema.CURRENT)
    }
}
