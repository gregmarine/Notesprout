package com.symmetricalpalmtree.notesproutsn.ext.bible

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's SQL, pinned as exact text (arc 37 / B0) — the tag manager's `TagSqlTest` style.
 * `StoreSchema` validates DDL at construction, so constructing [BibleSchema.V1] is itself a check
 * that the host's validator would accept it.
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

    /** Constructing [BibleSchema.V1] IS the DDL validator run; this pins what it declares. */
    @Test
    fun theSchemaIsOneVersionOfOneStatement() {
        assertEquals(1, BibleSchema.V1.version)
        val step = BibleSchema.V1.steps.single()
        assertEquals(1, step.size)
        assertTrue(step[0].startsWith("CREATE TABLE state"))
        assertTrue("the primary key", "key TEXT PRIMARY KEY" in step[0])
        assertTrue("the value column", "value TEXT NOT NULL" in step[0])
    }
}
