package com.symmetricalpalmtree.notesproutsn.data.extstore

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ExtensionStoreBinder` is an `IExtensionStore.Stub` (an `android.os.Binder`) and cannot be
 * constructed on the JVM; every check and cap it applies lives in [ExtensionStoreGate], which is
 * what these drive — over a fake [StoreExecutor] with an injectable calling uid. Real SQL, the
 * ashmem copy and the deferred region close are on-device only: the debug library's
 * "Extension store self-test" is their check.
 */
class ExtensionStoreGateTest {

    /**
     * A recording executor: statements land in [committed] only when their transaction succeeds
     * (a throw inside `transaction` discards the buffer — the rollback). Reads answer `host_schema`'s
     * version from [version] and anything else from [canned]; [failOn] makes the next matching
     * statement throw the way SQLite would.
     */
    private class FakeExecutor : StoreExecutor {
        var version = 0
        val committed = ArrayList<Pair<String, List<Cell>>>()
        val canned = HashMap<String, Pair<List<String>, List<List<Cell>>>>()
        var failOn: String? = null
        private var buffer: ArrayList<Pair<String, List<Cell>>>? = null
        private var bufferedVersion: Int? = null
        var depth = 0

        override fun <T> transaction(block: () -> T): T {
            check(depth == 0) { "nested transaction" }
            depth++
            buffer = ArrayList()
            bufferedVersion = version
            try {
                val r = block()
                committed += buffer!!
                version = bufferedVersion!!
                return r
            } finally {
                buffer = null
                depth--
            }
        }

        override fun ddl(sql: String) {
            maybeFail(sql)
            record(sql, emptyList())
        }

        override fun exec(sql: String, args: List<Cell>): Long {
            maybeFail(sql)
            if (sql == ExtensionStoreGate.WRITE_VERSION) {
                val v = (args[0] as Cell.Integer).value.toInt()
                if (buffer != null) bufferedVersion = v else version = v
            }
            record(sql, args)
            return 1
        }

        override fun query(sql: String, args: List<Cell>, sink: StoreExecutor.RowSink) {
            maybeFail(sql)
            if (sql == ExtensionStoreGate.READ_VERSION) {
                sink.columns(listOf("version"))
                sink.row(listOf(Cell.Integer(version.toLong())))
                return
            }
            val (columns, rows) = canned[sql] ?: error("no canned answer for: $sql")
            sink.columns(columns)
            for (r in rows) if (!sink.row(r)) return
        }

        private fun record(sql: String, args: List<Cell>) {
            (buffer ?: committed) += sql to args
        }

        private fun maybeFail(sql: String) {
            val f = failOn ?: return
            if (sql.contains(f)) {
                failOn = null
                throw RuntimeException("SQLiteConstraintException: $sql")
            }
        }
    }

    private val ext = 10_123
    private var caller = ext
    private val executor = FakeExecutor()
    private val gate = ExtensionStoreGate(executor, ext) { caller }

    private val v1 = StoreSchema(1, listOf(listOf("CREATE TABLE t (id TEXT PRIMARY KEY, n INTEGER)")))
    private val v2 = StoreSchema(2, v1.steps + listOf(listOf("ALTER TABLE t ADD COLUMN w REAL")))

    private fun batch(vararg statements: Statement) = StoreCodec.encodeStatements(statements.toList())
    private fun one(sql: String, vararg args: Any?) = StoreCodec.encodeStatements(listOf(Statement(sql, *args)))
    private fun rows(columns: List<String>, vararg rows: List<Cell>) = columns to rows.toList()

    // ── Trust ──────

    @Test
    fun uidMismatch_isSecurityException_onEveryMethod() {
        caller = ext + 1
        assertThrows(SecurityException::class.java) { gate.schemaVersion() }
        assertThrows(SecurityException::class.java) { gate.applySchema(v1) }
        assertThrows(SecurityException::class.java) { gate.exec(one("DELETE FROM t")) }
        assertThrows(SecurityException::class.java) { gate.query(one("SELECT 1")) }
        assertThrows(SecurityException::class.java) { gate.next(0) }
        assertThrows(SecurityException::class.java) { gate.close(0) }
        assertTrue(executor.committed.isEmpty())
    }

    @Test
    fun revoked_isSecurityException_onEveryMethod_andDropsParkedResults() {
        gate.applySchema(v1)
        executor.canned["SELECT * FROM t"] = bigResult(3)
        val first = gate.query(one("SELECT * FROM t"))
        assertTrue(first.more)
        assertEquals(1, gate.openResults)
        gate.revoke()
        assertTrue(gate.revoked)
        assertEquals(0, gate.openResults)
        assertThrows(SecurityException::class.java) { gate.schemaVersion() }
        assertThrows(SecurityException::class.java) { gate.applySchema(v1) }
        assertThrows(SecurityException::class.java) { gate.exec(one("DELETE FROM t")) }
        assertThrows(SecurityException::class.java) { gate.query(one("SELECT 1")) }
        assertThrows(SecurityException::class.java) { gate.next(first.handle) }
        assertThrows(SecurityException::class.java) { gate.close(first.handle) }
    }

    // ── Schema ──────

    @Test
    fun applySchema_runsMissingSteps_eachInItsOwnTransaction_andIsIdempotent() {
        assertEquals(0, gate.schemaVersion())
        gate.applySchema(v2)
        assertEquals(2, gate.schemaVersion())
        assertEquals(
            listOf(
                v1.steps[0][0], ExtensionStoreGate.WRITE_VERSION,
                v2.steps[1][0], ExtensionStoreGate.WRITE_VERSION,
            ),
            executor.committed.map { it.first },
        )
        assertEquals(listOf(1L, 2L), executor.committed.filter { it.first == ExtensionStoreGate.WRITE_VERSION }.map { (it.second[0] as Cell.Integer).value })
        val n = executor.committed.size
        gate.applySchema(v2)                // a no-op when the versions match
        assertEquals(n, executor.committed.size)
        assertEquals(2, gate.schemaVersion())
    }

    @Test
    fun applySchema_resumesFromTheAppliedVersion() {
        executor.version = 1               // step 1 landed in an earlier life
        gate.applySchema(v2)
        assertEquals(listOf(v2.steps[1][0], ExtensionStoreGate.WRITE_VERSION), executor.committed.map { it.first })
        assertEquals(2, executor.version)
    }

    @Test
    fun applySchema_refusesADowngrade_typed() {
        gate.applySchema(v2)
        val e = assertThrows(IllegalStateException::class.java) { gate.applySchema(v1) }
        assertEquals(ExtensionContract.STORE_SCHEMA_NEWER, e.message)
        assertEquals(2, gate.schemaVersion())
        assertThrows(IllegalArgumentException::class.java) { gate.applySchema(null) }
    }

    @Test
    fun applySchema_aFailingStep_rollsBackThatStep_andKeepsTheVersion() {
        executor.failOn = "ADD COLUMN"
        gate.applySchema(v1)
        assertThrows(IllegalStateException::class.java) { gate.applySchema(v2) }
        assertEquals(1, gate.schemaVersion())
        assertEquals(listOf(v1.steps[0][0], ExtensionStoreGate.WRITE_VERSION), executor.committed.map { it.first })
        // After it is declared once, this binder stays declared — the failed step is the next call's problem.
        gate.exec(one("DELETE FROM t"))
    }

    @Test
    fun execAndQuery_beforeApplySchema_areRefused_typed() {
        val e1 = assertThrows(IllegalStateException::class.java) { gate.exec(one("DELETE FROM t")) }
        assertEquals(ExtensionContract.STORE_SCHEMA_UNAPPLIED, e1.message)
        val e2 = assertThrows(IllegalStateException::class.java) { gate.query(one("SELECT 1")) }
        assertEquals(ExtensionContract.STORE_SCHEMA_UNAPPLIED, e2.message)
        assertTrue(executor.committed.isEmpty())
        // schemaVersion itself needs no declaration.
        assertEquals(0, gate.schemaVersion())
    }

    // ── exec ──────

    @Test
    fun exec_runsTheBatchInOneTransaction_answeringChangesPerStatement() {
        gate.applySchema(v1)
        val before = executor.committed.size
        val changes = gate.exec(batch(
            Statement("INSERT INTO t (id, n) VALUES (?, ?)", "a", 1),
            Statement("UPDATE t SET n = ? WHERE id = ?", 2, "a"),
            Statement("DELETE FROM t WHERE id = ?", "zzz"),
        ))
        assertArrayEquals(longArrayOf(1, 1, 1), changes)
        val ran = executor.committed.drop(before)
        assertEquals(3, ran.size)
        assertEquals("INSERT INTO t (id, n) VALUES (?, ?)", ran[0].first)
        assertEquals(listOf(Cell.Text("a"), Cell.Integer(1)), ran[0].second)
        assertEquals(listOf(Cell.Integer(2), Cell.Text("a")), ran[1].second)
    }

    @Test
    fun exec_aFailureMidBatch_rollsTheWholeBatchBack_asIllegalState() {
        gate.applySchema(v1)
        val before = executor.committed.size
        executor.failOn = "second"
        assertThrows(IllegalStateException::class.java) {
            gate.exec(batch(
                Statement("INSERT INTO t (id) VALUES ('first')"),
                Statement("INSERT INTO t (id) VALUES ('second')"),
                Statement("INSERT INTO t (id) VALUES ('third')"),
            ))
        }
        assertEquals(before, executor.committed.size)   // nothing landed, not even the first
        assertEquals(0, executor.depth)
    }

    @Test
    fun exec_validatesEveryStatement_beforeRunningAny() {
        gate.applySchema(v1)
        val before = executor.committed.size
        assertThrows(IllegalArgumentException::class.java) {
            gate.exec(batch(Statement("DELETE FROM t"), Statement("PRAGMA user_version = 9")))
        }
        assertThrows(IllegalArgumentException::class.java) { gate.exec(one("SELECT * FROM t")) }
        assertThrows(IllegalArgumentException::class.java) { gate.exec(one("DELETE FROM host_schema")) }
        assertThrows(IllegalArgumentException::class.java) { gate.exec(one("DELETE FROM t; DROP TABLE t")) }
        assertThrows(IllegalArgumentException::class.java) { gate.exec(byteArrayOf(1, 2, 3)) }   // unreadable
        assertThrows(IllegalArgumentException::class.java) { gate.exec(null) }
        assertEquals(before, executor.committed.size)
    }

    // ── query + handles ──────

    private fun bigResult(chunksWorth: Int): Pair<List<String>, List<List<Cell>>> {
        // Rows of ~1 MiB blobs: four per 4 MiB chunk at most, three per chunk comfortably.
        val columns = listOf("id", "blob")
        val rows = List(3 * chunksWorth) { i -> listOf(Cell.Integer(i.toLong()), Cell.Blob(ByteArray(1_300_000))) }
        return columns to rows
    }

    @Test
    fun query_smallResult_isOneChunkWithNoHandle() {
        gate.applySchema(v1)
        executor.canned["SELECT id, n FROM t WHERE n > ?"] = rows(listOf("id", "n"), listOf(Cell.Text("a"), Cell.Integer(1)))
        val chunk = gate.query(one("SELECT id, n FROM t WHERE n > ?", 0))
        assertFalse(chunk.more)
        assertEquals(ExtensionStoreGate.NO_HANDLE, chunk.handle)
        val decoded = StoreCodec.decodeRows(chunk.bytes)
        assertEquals(listOf("id", "n"), decoded.columns)
        assertEquals("a", decoded[0].text("id"))
        assertEquals(0, gate.openResults)
    }

    @Test
    fun query_emptyResult_isOneChunkOfZeroRows() {
        gate.applySchema(v1)
        executor.canned["SELECT id FROM t"] = rows(listOf("id"))
        val chunk = gate.query(one("SELECT id FROM t"))
        assertFalse(chunk.more)
        assertTrue(StoreCodec.decodeRows(chunk.bytes).isEmpty())
    }

    @Test
    fun query_largeResult_parksTheRemainder_andNextDrainsIt() {
        gate.applySchema(v1)
        executor.canned["SELECT * FROM t"] = bigResult(3)
        val first = gate.query(one("SELECT * FROM t"))
        assertTrue(first.more)
        assertTrue(first.handle >= 0)
        assertEquals(1, gate.openResults)
        var ids = StoreCodec.decodeRows(first.bytes).rows.map { it.long("id") }
        var chunk = first
        var chunks = 1
        while (chunk.more) {
            chunk = gate.next(chunk.handle)
            chunks++
            ids = ids + StoreCodec.decodeRows(chunk.bytes).rows.map { it.long("id") }
        }
        assertEquals(ExtensionStoreGate.NO_HANDLE, chunk.handle)
        assertEquals((0L until 9L).toList(), ids)
        assertTrue("$chunks chunks", chunks >= 3)
        assertEquals(0, gate.openResults)
        // The handle is gone once drained.
        assertThrows(IllegalStateException::class.java) { gate.next(first.handle) }
    }

    @Test
    fun close_dropsAnUnfinishedResult_andIsANoOpForUnknownHandles() {
        gate.applySchema(v1)
        executor.canned["SELECT * FROM t"] = bigResult(2)
        val first = gate.query(one("SELECT * FROM t"))
        assertEquals(1, gate.openResults)
        gate.close(first.handle)
        assertEquals(0, gate.openResults)
        assertThrows(IllegalStateException::class.java) { gate.next(first.handle) }
        gate.close(first.handle)
        gate.close(12345)
        assertThrows(IllegalStateException::class.java) { gate.next(12345) }
    }

    @Test
    fun aFifthOpenResult_isRefused_typed_andSmallQueriesStillRun() {
        gate.applySchema(v1)
        executor.canned["SELECT * FROM t"] = bigResult(2)
        executor.canned["SELECT id FROM t"] = rows(listOf("id"), listOf(Cell.Text("x")))
        val handles = (0 until ExtensionContract.STORE_MAX_OPEN_RESULTS).map { gate.query(one("SELECT * FROM t")).handle }
        assertEquals(ExtensionContract.STORE_MAX_OPEN_RESULTS, gate.openResults)
        assertEquals(handles.size, handles.toSet().size)
        val e = assertThrows(IllegalStateException::class.java) { gate.query(one("SELECT * FROM t")) }
        assertEquals(ExtensionContract.STORE_RESULTS_OPEN, e.message)
        // A result that fits one chunk needs no handle and is not refused.
        assertFalse(gate.query(one("SELECT id FROM t")).more)
        gate.close(handles[0])
        assertTrue(gate.query(one("SELECT * FROM t")).more)
    }

    @Test
    fun query_typedCaps_stopTheRead_andParkNothing() {
        gate.applySchema(v1)
        val columns = listOf("blob")
        executor.canned["SELECT blob FROM t"] = columns to listOf(listOf(Cell.Blob(ByteArray(ExtensionContract.STORE_MAX_ROW_BYTES))))
        val e = assertThrows(IllegalStateException::class.java) { gate.query(one("SELECT blob FROM t")) }
        assertEquals(ExtensionContract.STORE_ROW_LARGE, e.message)
        assertEquals(0, gate.openResults)
    }

    @Test
    fun query_validates_andTakesExactlyOneStatement() {
        gate.applySchema(v1)
        assertThrows(IllegalArgumentException::class.java) { gate.query(one("DELETE FROM t")) }
        assertThrows(IllegalArgumentException::class.java) { gate.query(one("SELECT * FROM host_schema")) }
        assertThrows(IllegalArgumentException::class.java) { gate.query(one("SELECT 1; SELECT 2")) }
        assertThrows(IllegalArgumentException::class.java) { gate.query(batch(Statement("SELECT 1"), Statement("SELECT 2"))) }
        assertThrows(IllegalArgumentException::class.java) { gate.query(null) }
        assertThrows(IllegalArgumentException::class.java) { gate.query(byteArrayOf()) }
        assertEquals(0, gate.openResults)
    }

    @Test
    fun executorFailure_becomesIllegalState_everywhere() {
        gate.applySchema(v1)
        executor.canned["SELECT id FROM t"] = rows(listOf("id"))
        executor.failOn = "SELECT id FROM t"
        assertThrows(IllegalStateException::class.java) { gate.query(one("SELECT id FROM t")) }
        executor.failOn = ExtensionStoreGate.READ_VERSION
        assertThrows(IllegalStateException::class.java) { gate.schemaVersion() }
        executor.failOn = "DELETE"
        assertThrows(IllegalStateException::class.java) { gate.exec(one("DELETE FROM t")) }
    }
}
