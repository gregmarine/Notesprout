package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The statement validator (arc 22 / X1): what the seam carries and, more importantly, what it refuses. */
class StoreSqlTest {

    private fun refusedQuery(sql: String) = assertThrows(IllegalArgumentException::class.java) { StoreSql.checkQuery(sql) }
    private fun refusedExec(sql: String) = assertThrows(IllegalArgumentException::class.java) { StoreSql.checkExec(sql) }
    private fun refusedDdl(sql: String) = assertThrows(IllegalArgumentException::class.java) { StoreSql.checkDdl(sql) }

    @Test
    fun queries_thatPass() {
        StoreSql.checkQuery("SELECT * FROM page")
        StoreSql.checkQuery("select id, \"order\" from stroke where pageId = ? order by \"order\" limit 100")
        StoreSql.checkQuery("WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM c WHERE n < 5) SELECT n FROM c")
        StoreSql.checkQuery("SELECT count(*) FROM tag t JOIN assignment a ON a.tagId = t.id GROUP BY pageId = ''")
        StoreSql.checkQuery("SELECT replace(display, 'a', 'b') FROM tag")   // the function, not the statement
        StoreSql.checkQuery("SELECT * FROM t WHERE x = ?1 AND y = ?2")
        StoreSql.checkQuery("SELECT 1;")                                       // one trailing ; is tolerated
        StoreSql.checkQuery("SELECT 1 -- a comment\n")
        StoreSql.checkQuery("SELECT /* pragma in a comment */ 1")
    }

    @Test
    fun execs_thatPass() {
        StoreSql.checkExec("INSERT INTO page (id, position) VALUES (?, ?)")
        StoreSql.checkExec("INSERT OR IGNORE INTO tag (id, display, identityKey, createdAt) VALUES (?, ?, ?, ?)")
        StoreSql.checkExec("REPLACE INTO state (key, value) VALUES ('current', ?)")
        StoreSql.checkExec("UPDATE page SET position = position + 1 WHERE position >= ?")
        StoreSql.checkExec("DELETE FROM caret WHERE pageKey NOT IN (SELECT pageKey FROM caret ORDER BY updatedAt DESC LIMIT 100)")
        StoreSql.checkExec("WITH doomed AS (SELECT id FROM tag WHERE display = ?) DELETE FROM tag WHERE id IN doomed")
    }

    @Test
    fun ddl_thatPasses() {
        StoreSql.checkDdl("CREATE TABLE page (id TEXT PRIMARY KEY, position INTEGER NOT NULL, width REAL NOT NULL)")
        StoreSql.checkDdl("CREATE TABLE IF NOT EXISTS stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE, \"order\" INTEGER NOT NULL, blob BLOB NOT NULL)")
        StoreSql.checkDdl("CREATE TABLE prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
        StoreSql.checkDdl("CREATE INDEX page_position ON page(position)")
        StoreSql.checkDdl("CREATE UNIQUE INDEX IF NOT EXISTS tag_identity ON tag(identityKey)")
        StoreSql.checkDdl("CREATE TABLE assignment (tagId TEXT NOT NULL, notebookId TEXT NOT NULL, pageId TEXT NOT NULL DEFAULT '', PRIMARY KEY (tagId, notebookId, pageId))")
        StoreSql.checkDdl("ALTER TABLE stroke ADD COLUMN width REAL NOT NULL DEFAULT 1")
        StoreSql.checkDdl("ALTER TABLE stroke ADD style TEXT")
        StoreSql.checkDdl("ALTER TABLE stroke RENAME TO ink")
        StoreSql.checkDdl("ALTER TABLE stroke RENAME COLUMN blob TO points")
        StoreSql.checkDdl("ALTER TABLE stroke RENAME blob TO points")
        StoreSql.checkDdl("DROP TABLE IF EXISTS old")
        StoreSql.checkDdl("DROP INDEX page_position")
        StoreSql.checkDdl("CREATE TABLE t (id INTEGER PRIMARY KEY, n INTEGER CHECK (n > 0));")
    }

    @Test
    fun oneStatementRule() {
        refusedQuery("SELECT 1; SELECT 2")
        refusedQuery("SELECT 1; DROP TABLE page")
        refusedExec("DELETE FROM stroke; DROP TABLE stroke")
        refusedExec("DELETE FROM stroke;;")
        refusedDdl("CREATE TABLE a (x); CREATE TABLE b (y)")
        // A ; inside a literal is text, not a statement boundary.
        StoreSql.checkQuery("SELECT * FROM t WHERE x = 'a; b'")
        StoreSql.checkQuery("SELECT * FROM t WHERE x = 'it''s; fine'")
        StoreSql.checkQuery("SELECT \"a;b\" FROM t")
        StoreSql.checkQuery("SELECT * FROM t -- ; not a boundary\n")
    }

    @Test
    fun headKeyword_decidesTheKind() {
        refusedQuery("INSERT INTO t VALUES (1)")
        refusedQuery("DELETE FROM t")
        refusedQuery("UPDATE t SET x = 1")
        refusedQuery("CREATE TABLE t (x)")
        refusedQuery("PRAGMA user_version")
        refusedExec("SELECT 1")
        refusedExec("CREATE TABLE t (x)")
        refusedExec("PRAGMA foreign_keys = OFF")
        refusedDdl("SELECT 1")
        refusedDdl("INSERT INTO t VALUES (1)")
        refusedQuery("")
        refusedQuery("   ")
        refusedQuery("?")
        refusedQuery("(SELECT 1)")
    }

    @Test
    fun denylist_everyWord_inQueryAndExec() {
        for (word in StoreSql.DENY) {
            refusedQuery("SELECT * FROM t WHERE x = $word")
            refusedQuery("SELECT * FROM t WHERE x = ${word.lowercase()}")
            refusedExec("DELETE FROM t WHERE x = $word")
        }
        refusedQuery("SELECT load_extension('x')")
        refusedQuery("WITH x AS (SELECT 1) SELECT * FROM x; ATTACH 'y' AS z")
    }

    @Test
    fun denylist_inDdl() {
        for (word in StoreSql.DDL_DENY) refusedDdl("CREATE TABLE t (x, $word)")
        refusedDdl("CREATE VIEW v AS SELECT 1")
        refusedDdl("CREATE TRIGGER tr AFTER INSERT ON t BEGIN SELECT 1; END")
        refusedDdl("CREATE VIRTUAL TABLE t USING fts5(x)")
        refusedDdl("CREATE TEMP TABLE t (x)")
        refusedDdl("CREATE TEMPORARY TABLE t (x)")
        // A second head word inside a DDL statement is a second statement in disguise.
        refusedDdl("CREATE TABLE t (x) DROP")
        refusedDdl("ALTER TABLE t ADD COLUMN c INTEGER CREATE")
    }

    @Test
    fun quotedLiterals_areNotFalsePositives() {
        StoreSql.checkQuery("SELECT * FROM t WHERE name = 'pragma'")
        StoreSql.checkQuery("SELECT * FROM t WHERE name = 'DROP TABLE t'")
        StoreSql.checkQuery("SELECT * FROM t WHERE name = 'attach; detach'")
        StoreSql.checkExec("INSERT INTO t (x) VALUES ('BEGIN')")
        StoreSql.checkQuery("SELECT \"drop\" FROM t")      // a column called drop, quoted
        StoreSql.checkQuery("SELECT `commit` FROM t")
        StoreSql.checkQuery("SELECT [rollback] FROM t")
        StoreSql.checkQuery("SELECT 'it''s' FROM t")
        StoreSql.checkQuery("SELECT \"say \"\"hi\"\"\" FROM t")
        // But a bare one is refused.
        refusedQuery("SELECT drop FROM t")
    }

    @Test
    fun reservedNames_refused_bareAndQuoted_everyKind() {
        for (name in listOf("host_schema", "HOST_SCHEMA", "sqlite_master", "sqlite_sequence", "room_master_table", "android_metadata", "host_x")) {
            refusedQuery("SELECT * FROM $name")
            refusedQuery("SELECT * FROM \"$name\"")
            refusedQuery("SELECT * FROM `$name`")
            refusedQuery("SELECT * FROM [$name]")
            refusedExec("DELETE FROM $name")
            refusedExec("INSERT INTO t (x) SELECT version FROM $name")
            refusedDdl("CREATE TABLE $name (x)")
            refusedDdl("CREATE TABLE t (x REFERENCES $name(id))")
            refusedDdl("DROP TABLE $name")
            refusedDdl("CREATE INDEX i ON $name(x)")
        }
        refusedQuery("SELECT sqlite_version()")
        // A reserved word inside a string is only a string.
        StoreSql.checkQuery("SELECT * FROM t WHERE x = 'host_schema'")
        // A name that merely contains a reserved prefix is fine.
        StoreSql.checkQuery("SELECT * FROM ghost_host_x")
        StoreSql.checkQuery("SELECT * FROM t WHERE my_android_id = 1")
    }

    @Test
    fun aQueryCannotSmuggleAWrite() {
        refusedQuery("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x")
        refusedQuery("WITH x AS (SELECT 1) DELETE FROM t")
        refusedQuery("WITH x AS (SELECT 1) UPDATE t SET a = 1")
        refusedQuery("WITH x AS (SELECT 1) REPLACE INTO t VALUES (1)")
        // Whereas an exec under WITH is what WITH is for on that side.
        StoreSql.checkExec("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x")
    }

    @Test
    fun namedBinds_refused_positionalPass() {
        refusedQuery("SELECT * FROM t WHERE x = :x")
        refusedQuery("SELECT * FROM t WHERE x = @x")
        refusedQuery("SELECT * FROM t WHERE x = \$x")
        refusedExec("INSERT INTO t VALUES (:a, :b)")
        StoreSql.checkQuery("SELECT * FROM t WHERE x = ? AND y = ?2")
        StoreSql.checkQuery("SELECT * FROM t WHERE x = ?001")
    }

    @Test
    fun ddlShape_ownNamesAreStoreNames() {
        refusedDdl("CREATE TABLE Page (x)")             // uppercase
        refusedDdl("CREATE TABLE \"page\" (x)")          // quoted object name
        refusedDdl("CREATE TABLE 1page (x)")
        refusedDdl("CREATE TABLE _page (x)")
        refusedDdl("CREATE TABLE ${"p".repeat(64)} (x)")
        StoreSql.checkDdl("CREATE TABLE ${"p".repeat(63)} (x)")
        refusedDdl("CREATE INDEX Idx ON page(x)")
        refusedDdl("CREATE INDEX idx ON Page(x)")
        refusedDdl("ALTER TABLE Page ADD COLUMN c")
        refusedDdl("ALTER TABLE page RENAME TO Page")
        refusedDdl("DROP TABLE Page")
        refusedDdl("ALTER TABLE page DROP COLUMN c")    // not carried in v6
        refusedDdl("CREATE TABLE")
        refusedDdl("CREATE INDEX i")
        refusedDdl("DROP page")
        refusedDdl("ALTER page ADD c")
        // Columns are the extension's own business — camelCase and quoted keywords both pass.
        StoreSql.checkDdl("CREATE TABLE stroke (pageId TEXT, \"order\" INTEGER, createdAt INTEGER)")
    }

    @Test
    fun lengthCap() {
        val long = "SELECT '" + "x".repeat(ExtensionContract.STORE_MAX_SQL_CHARS) + "'"
        refusedQuery(long)
        StoreSql.checkQuery("SELECT '" + "x".repeat(ExtensionContract.STORE_MAX_SQL_CHARS - 10) + "'")
    }

    @Test
    fun unterminatedLiteralsAndComments() {
        refusedQuery("SELECT 'abc")
        refusedQuery("SELECT \"abc")
        refusedQuery("SELECT [abc")
        refusedQuery("SELECT /* abc")
    }

    @Test
    fun createsTable() {
        assertTrue(StoreSql.createsTable("CREATE TABLE t (x)"))
        assertTrue(StoreSql.createsTable("create table if not exists t (x)"))
        assertFalse(StoreSql.createsTable("CREATE INDEX i ON t(x)"))
        assertFalse(StoreSql.createsTable("ALTER TABLE t ADD COLUMN c"))
        assertFalse(StoreSql.createsTable("DROP TABLE t"))
    }

    @Test
    fun storeNames() {
        assertTrue(StoreNames.isValid("page"))
        assertTrue(StoreNames.isValid("stroke_page_order"))
        assertTrue(StoreNames.isValid("a1"))
        assertFalse(StoreNames.isValid(""))
        assertFalse(StoreNames.isValid("Page"))
        assertFalse(StoreNames.isValid("1a"))
        assertFalse(StoreNames.isValid("_a"))
        assertFalse(StoreNames.isValid("a-b"))
        assertFalse(StoreNames.isValid("host_schema"))
        assertFalse(StoreNames.isValid("sqlite_master"))
        assertFalse(StoreNames.isValid("room_master_table"))
        assertFalse(StoreNames.isValid("android_metadata"))
        assertTrue(StoreNames.isReserved("HOST_X"))
        assertFalse(StoreNames.isReserved("ghost_"))
        assertEquals(listOf("host_", "sqlite_", "room_", "android_"), StoreNames.RESERVED_PREFIXES)
    }
}
