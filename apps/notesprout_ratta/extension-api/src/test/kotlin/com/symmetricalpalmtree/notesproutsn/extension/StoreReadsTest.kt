package com.symmetricalpalmtree.notesproutsn.extension

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extension side's chunk loop (arc 22 / X1): stitches, and closes on any failure between chunks. */
class StoreReadsTest {

    /** A host that answers a canned list of chunks and records what it was asked. */
    private class FakeStore(private val chunks: List<ByteArray>) : IExtensionStore {
        val calls = ArrayList<String>()
        var failNextAt: Int = -1        // the `next` call (0-based) that throws
        private var served = 0

        override fun schemaVersion(): Int = 0
        override fun applySchema(schema: StoreSchema?) = Unit
        override fun exec(batch: StorePayload?): LongArray {
            calls += "exec(${StoreCodec.decodeStatements(batch!!.readAndClose()).size})"
            return LongArray(1) { 1 }
        }

        override fun query(statement: StorePayload?): StoreResult {
            val statements = StoreCodec.decodeStatements(statement!!.readAndClose())
            calls += "query(${statements[0].sql})"
            served = 1
            return StoreResult(StorePayload(chunks[0], null), if (chunks.size > 1) 5 else StoreResult.NO_HANDLE, chunks.size > 1)
        }

        override fun next(handle: Int): StoreResult {
            calls += "next($handle)"
            check(handle == 5) { "unknown handle" }
            if (failNextAt == served - 1) throw IllegalStateException("boom")
            val chunk = chunks[served++]
            val more = served < chunks.size
            return StoreResult(StorePayload(chunk, null), if (more) 5 else StoreResult.NO_HANDLE, more)
        }

        override fun close(handle: Int) {
            calls += "close($handle)"
        }

        override fun asBinder(): IBinder? = null
    }

    private val columns = listOf("id", "name")
    private fun chunk(vararg ids: Long) = StoreCodec.encodeRows(columns, ids.map { listOf(Cell.Integer(it), Cell.Text("n$it")) })

    @Test
    fun oneChunk_noNextNoClose() {
        val store = FakeStore(listOf(chunk(1, 2)))
        val rows = StoreReads.all(store, "SELECT * FROM t WHERE x = ?", 7)
        assertEquals(listOf(1L, 2L), rows.rows.map { it.long("id") })
        assertEquals(listOf("query(SELECT * FROM t WHERE x = ?)"), store.calls)
    }

    @Test
    fun manyChunks_stitchedInOrder() {
        val store = FakeStore(listOf(chunk(1, 2), chunk(3), chunk(4, 5)))
        val rows = StoreReads.all(store, Statement("SELECT * FROM t"))
        assertEquals(columns, rows.columns)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.rows.map { it.long("id") })
        assertEquals("n5", rows[4].text("name"))
        assertEquals(listOf("query(SELECT * FROM t)", "next(5)", "next(5)"), store.calls)
    }

    @Test
    fun emptyResult_isEmptyRows() {
        val store = FakeStore(listOf(chunk()))
        val rows = StoreReads.all(store, Statement("SELECT * FROM t"))
        assertTrue(rows.isEmpty())
        assertEquals(columns, rows.columns)
    }

    @Test
    fun failureBetweenChunks_closesTheHandle_andRethrows() {
        val store = FakeStore(listOf(chunk(1), chunk(2), chunk(3)))
        store.failNextAt = 1
        val e = assertThrows(IllegalStateException::class.java) { StoreReads.all(store, Statement("SELECT * FROM t")) }
        assertEquals("boom", e.message)
        assertEquals(listOf("query(SELECT * FROM t)", "next(5)", "next(5)", "close(5)"), store.calls)
    }

    @Test
    fun exec_encodesTheBatch() {
        val store = FakeStore(listOf(chunk()))
        assertEquals(1L, StoreReads.exec(store, "DELETE FROM t WHERE id = ?", 1))
        assertEquals(listOf("exec(1)"), store.calls)
    }
}
