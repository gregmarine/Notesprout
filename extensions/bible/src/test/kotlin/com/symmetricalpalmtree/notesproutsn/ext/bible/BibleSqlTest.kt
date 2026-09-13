package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's SQL, pinned as exact text (arc 37 / B0, grown by B7) — the tag manager's
 * `TagSqlTest` style. `StoreSchema` validates DDL at construction, so constructing
 * [BibleSchema.V2] is itself a check that the host's validator would accept it; the statements
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

    /** The host gates every statement by kind; each of ours must pass the gate it is sent through. */
    @Test
    fun everyStatementPassesTheHostGate() {
        StoreSql.checkQuery(BibleSql.SELECT_STATE)
        StoreSql.checkQuery(BibleSql.SELECT_RECENTS)
        StoreSql.checkExec(BibleSql.UPSERT_STATE)
        StoreSql.checkExec(BibleSql.UPSERT_RECENT)
        StoreSql.checkExec(BibleSql.TRIM_RECENTS)
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
        assertEquals(BibleSchema.V2, BibleSchema.CURRENT)
    }
}
