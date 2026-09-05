package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every statement the Drive provider sends against `account`, pinned as exact text and arguments
 * (arc 25 / V1) — the host's validator is run over each one, so a shape it would refuse fails here
 * rather than on the device.
 */
class DriveSqlTest {

    @Test
    fun selectValue_readsOneKey() {
        val s = DriveSql.selectValue(DriveSql.Keys.REFRESH_TOKEN)
        assertEquals("SELECT value FROM account WHERE key = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(DriveSql.Keys.REFRESH_TOKEN)), s.args)
        StoreSql.checkQuery(s.sql)
    }

    @Test
    fun upsertValue_replacesByKey() {
        val s = DriveSql.upsertValue(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        assertEquals("INSERT OR REPLACE INTO account (key, value) VALUES (?, ?)", s.sql)
        assertEquals(
            listOf<Cell>(Cell.Text(DriveSql.Keys.ACCOUNT_LABEL), Cell.Text("person@example.com")),
            s.args,
        )
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun deleteValue_forgetsOneKey() {
        val s = DriveSql.deleteValue(DriveSql.Keys.ROOT_FOLDER_ID)
        assertEquals("DELETE FROM account WHERE key = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(DriveSql.Keys.ROOT_FOLDER_ID)), s.args)
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun deleteAll_forgetsTheWholeAccount() {
        val s = DriveSql.deleteAll()
        assertEquals("DELETE FROM account", s.sql)
        assertEquals(emptyList<Cell>(), s.args)
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun keys_areDistinct() {
        val keys = setOf(DriveSql.Keys.REFRESH_TOKEN, DriveSql.Keys.ACCOUNT_LABEL, DriveSql.Keys.ROOT_FOLDER_ID)
        assertEquals(3, keys.size)
    }

    // ── The schema ───────────────────────────────────────────────────────────

    /** Constructing [DriveSchema.V1] IS the DDL validator run; this pins what it declares. */
    @Test
    fun theSchemaIsOneVersionOfOneStatement() {
        assertEquals(1, DriveSchema.V1.version)
        val step = DriveSchema.V1.steps.single()
        assertEquals(1, step.size)
        assertTrue(step[0].startsWith("CREATE TABLE account"))
        assertTrue("the key column", "key TEXT PRIMARY KEY" in step[0])
        assertTrue("the value column", "value TEXT NOT NULL" in step[0])
    }
}
