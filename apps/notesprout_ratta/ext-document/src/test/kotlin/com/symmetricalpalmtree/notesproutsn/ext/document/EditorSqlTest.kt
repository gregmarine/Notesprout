package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every statement the document editor sends, pinned as exact text and arguments (arc 22 / X4) — the
 * host's validator is run over each one, so a shape it would refuse fails here rather than on the
 * device.
 */
class EditorSqlTest {

    // ── prefs ────────────────────────────────────────────────────────────────

    @Test
    fun selectPref_readsOneValueByKey() {
        val s = EditorSql.selectPref(DocumentContract.PREF_TEXT_SIZE)
        assertEquals("SELECT value FROM prefs WHERE key = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text("size")), s.args)
        StoreSql.checkQuery(s.sql)
    }

    @Test
    fun upsertPref_replacesInPlace() {
        val s = EditorSql.upsertPref(EditorSql.PREF_PROOFREAD, "0")
        assertEquals("INSERT OR REPLACE INTO prefs (key, value) VALUES (?, ?)", s.sql)
        assertEquals(listOf<Cell>(Cell.Text("proofread"), Cell.Text("0")), s.args)
        StoreSql.checkExec(s.sql)
    }

    /**
     * The `prefs` statements are spelled with [DocumentContract]'s constants, not with literals:
     * this is the ONE extension table the host reads for itself (Document-PDF export's text size),
     * and two spellings of it would be two tables. Renaming the constant must move both sides
     * together, so the test asks the constants, never the words.
     */
    @Test
    fun thePrefsStatementsAreBuiltFromTheContractsPinnedNames() {
        val select = EditorSql.selectPref("k").sql
        val upsert = EditorSql.upsertPref("k", "v").sql
        for (sql in listOf(select, upsert)) {
            assertTrue(sql, DocumentContract.PREFS_TABLE in sql)
            assertTrue(sql, DocumentContract.PREFS_KEY_COLUMN in sql)
            assertTrue(sql, DocumentContract.PREFS_VALUE_COLUMN in sql)
        }
        // The host's own read is `SELECT <value> FROM <prefs> WHERE <key> = ?` — the same three.
        assertEquals(
            "SELECT ${DocumentContract.PREFS_VALUE_COLUMN} FROM ${DocumentContract.PREFS_TABLE} " +
                "WHERE ${DocumentContract.PREFS_KEY_COLUMN} = ?",
            select,
        )
    }

    /** The two keys `prefs` carries. The text size's is the contract's — the host reads that one. */
    @Test
    fun theTwoPrefKeys() {
        assertEquals("size", DocumentContract.PREF_TEXT_SIZE)
        assertEquals("proofread", EditorSql.PREF_PROOFREAD)
    }

    // ── word ─────────────────────────────────────────────────────────────────

    /** Oldest first — the manage list's order — with `word` as the tie-break so it is TOTAL. */
    @Test
    fun selectWords_isOldestFirstAndTotallyOrdered() {
        val s = EditorSql.selectWords()
        assertEquals("SELECT word FROM word ORDER BY addedAt, word", s.sql)
        assertEquals(emptyList<Cell>(), s.args)
        StoreSql.checkQuery(s.sql)
    }

    /** `OR IGNORE`, not `OR REPLACE`: a re-add must not move the word's `addedAt`. */
    @Test
    fun insertWord_ignoresARepeatRatherThanReplacingIt() {
        val s = EditorSql.insertWord("colour", 99L)
        assertEquals("INSERT OR IGNORE INTO word (word, addedAt) VALUES (?, ?)", s.sql)
        assertEquals(listOf<Cell>(Cell.Text("colour"), Cell.Integer(99)), s.args)
        StoreSql.checkExec(s.sql)
    }

    @Test
    fun deleteWord_isAHardDrop() {
        val s = EditorSql.deleteWord("colour")
        assertEquals("DELETE FROM word WHERE word = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text("colour")), s.args)
        StoreSql.checkExec(s.sql)
    }

    // ── caret ────────────────────────────────────────────────────────────────

    @Test
    fun selectCaret_readsOneOffsetByPage() {
        val s = EditorSql.selectCaret("pk-1")
        assertEquals("SELECT offset FROM caret WHERE pageKey = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text("pk-1")), s.args)
        StoreSql.checkQuery(s.sql)
    }

    @Test
    fun upsertCaret_carriesTheOffsetAndTheClock() {
        val s = EditorSql.upsertCaret("pk-1", 42, 99L)
        assertEquals("INSERT OR REPLACE INTO caret (pageKey, offset, updatedAt) VALUES (?, ?, ?)", s.sql)
        assertEquals(
            listOf<Cell>(Cell.Text("pk-1"), Cell.Integer(42), Cell.Integer(99)),
            s.args,
        )
        StoreSql.checkExec(s.sql)
    }

    /** The cap is **bound**, not written into the text — a cap in the SQL is one a test cannot vary. */
    @Test
    fun trimCarets_bindsTheLimit() {
        val s = EditorSql.trimCarets()
        assertEquals(
            "DELETE FROM caret WHERE pageKey NOT IN " +
                "(SELECT pageKey FROM caret ORDER BY updatedAt DESC LIMIT ?)",
            s.sql,
        )
        assertEquals(listOf<Cell>(Cell.Integer(EditorSql.CARET_LIMIT.toLong())), s.args)
        assertTrue("the cap is bound, never inlined", EditorSql.CARET_LIMIT.toString() !in s.sql)
        StoreSql.checkExec(s.sql)
    }

    /** Was `CaretMemory.LIMIT`, and the number did not change with the shape. */
    @Test
    fun theCaretLimitIsAHundred() {
        assertEquals(100, EditorSql.CARET_LIMIT)
    }

    // ── The schema ───────────────────────────────────────────────────────────

    /** Constructing [EditorSchema.V1] IS the DDL validator run; this pins what it declares. */
    @Test
    fun theSchemaIsOneVersionOfThreeTables() {
        assertEquals(1, EditorSchema.V1.version)
        val step = EditorSchema.V1.steps.single()
        assertEquals(3, step.size)
        assertEquals("CREATE TABLE prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL);", step[0])
        assertEquals("CREATE TABLE word (word TEXT PRIMARY KEY, addedAt INTEGER NOT NULL);", step[1])
        assertEquals(
            "CREATE TABLE caret (pageKey TEXT PRIMARY KEY, offset INTEGER NOT NULL, updatedAt INTEGER NOT NULL);",
            step[2],
        )
        // No cascade anywhere: none of the three has children, which is what makes REPLACE safe.
        assertTrue(step.none { "REFERENCES" in it })
    }

    /** The `prefs` DDL is built from the contract's names too — the host creates no table, but it
     *  reads this one, and a rename that moved only the statements would leave it reading nothing. */
    @Test
    fun thePrefsDdlIsBuiltFromTheContractsPinnedNames() {
        assertEquals(
            "CREATE TABLE ${DocumentContract.PREFS_TABLE} (" +
                "${DocumentContract.PREFS_KEY_COLUMN} TEXT PRIMARY KEY, " +
                "${DocumentContract.PREFS_VALUE_COLUMN} TEXT NOT NULL);",
            EditorSchema.V1.steps.single()[0],
        )
    }
}
