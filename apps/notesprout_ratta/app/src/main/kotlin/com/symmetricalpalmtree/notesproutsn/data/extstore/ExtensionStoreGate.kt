package com.symmetricalpalmtree.notesproutsn.data.extstore

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.StoreChunker
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql

/**
 * The checks and caps behind [ExtensionStoreBinder] (arc 22 / X1), with **no Android types
 * precisely so they run on the JVM** — the binder itself is an `android.os.Binder` and cannot be
 * constructed in a unit test, so everything worth testing lives here, over an injected
 * [StoreExecutor].
 *
 * Every method first requires the caller's uid to be [extUid] and the gate not to be [revoked] —
 * else `SecurityException`. Then, in order:
 *  - [schemaVersion] reads `host_schema`.
 *  - [applySchema] runs the steps `applied + 1 .. schema.version`, **each its own transaction with
 *    the version bump** (crash-resumable), refuses a downgrade with `STORE_SCHEMA_NEWER`, and marks
 *    this binder as declared. A [StoreSchema] arrives pre-validated (its constructor is the DDL
 *    validator, run again at unmarshal).
 *  - [exec] / [query] refuse with `STORE_SCHEMA_UNAPPLIED` until [applySchema] has run on this
 *    binder — structural: a query cannot precede the declaration of what it queries. The payload is
 *    decoded ([StoreCodec] — unreadable is `IllegalArgumentException`), every statement is validated
 *    ([StoreSql] — refused is `IllegalArgumentException`), then run. [exec] is one transaction,
 *    all-or-nothing, `@Synchronized` (one writer per store at a time; reads run under WAL
 *    concurrently). [query] reads to completion through a [StoreChunker]; a result that needs more
 *    than one chunk is **parked as bytes** behind a handle (at most `STORE_MAX_OPEN_RESULTS`, else
 *    `STORE_RESULTS_OPEN`) for [next] / [close], and every parked chunk is dropped on [revoke].
 *
 * An executor failure (SQLite full / locked / I/O / **a constraint violation**) is rethrown as
 * `IllegalStateException` — the extension reads the message, the host never parses it. Those
 * three — `SecurityException`, `IllegalArgumentException`, `IllegalStateException` — are the whole
 * set Binder carries across the boundary intact. Anything else kills the transaction **silently**
 * and the extension reads the empty reply as success.
 */
class ExtensionStoreGate(
    private val executor: StoreExecutor,
    private val extUid: Int,
    private val callingUid: () -> Int,
) {
    /** One chunk of a query result as the gate hands it to the binder. */
    class Chunk(val bytes: ByteArray, val handle: Int, val more: Boolean)

    @Volatile
    var revoked: Boolean = false
        private set

    @Volatile
    private var declared: Boolean = false

    private val parked = HashMap<Int, ArrayDeque<ByteArray>>()
    private var nextHandle = 0

    /** After this every method throws `SecurityException`, and every parked result is gone. */
    fun revoke() {
        revoked = true
        synchronized(parked) { parked.clear() }
    }

    /** Handles with an unfinished result parked behind them (tests). */
    val openResults: Int get() = synchronized(parked) { parked.size }

    fun schemaVersion(): Int {
        check()
        return io { readVersion() }
    }

    @Synchronized
    fun applySchema(schema: StoreSchema?) {
        check()
        requireNotNull(schema) { "schema is null" }
        val applied = io { readVersion() }
        if (applied > schema.version) throw IllegalStateException(ExtensionContract.STORE_SCHEMA_NEWER)
        for (v in applied + 1..schema.version) {
            io {
                executor.transaction {
                    for (sql in schema.steps[v - 1]) executor.ddl(sql)
                    executor.exec(WRITE_VERSION, listOf(Cell.Integer(v.toLong())))
                }
            }
        }
        declared = true
    }

    @Synchronized
    fun exec(batch: ByteArray?): LongArray {
        check()
        requireNotNull(batch) { "batch is null" }
        if (!declared) throw IllegalStateException(ExtensionContract.STORE_SCHEMA_UNAPPLIED)
        val statements = StoreCodec.decodeStatements(batch)
        for (s in statements) StoreSql.checkExec(s.sql)
        return io {
            executor.transaction {
                LongArray(statements.size) { i -> executor.exec(statements[i].sql, statements[i].args) }
            }
        }
    }

    fun query(statement: ByteArray?): Chunk {
        check()
        requireNotNull(statement) { "statement is null" }
        if (!declared) throw IllegalStateException(ExtensionContract.STORE_SCHEMA_UNAPPLIED)
        val statements = StoreCodec.decodeStatements(statement)
        require(statements.size == 1) { "query takes exactly one statement (${statements.size})" }
        val s = statements[0]
        StoreSql.checkQuery(s.sql)
        val chunks = io {
            var chunker: StoreChunker? = null
            executor.query(s.sql, s.args, object : StoreExecutor.RowSink {
                override fun columns(names: List<String>) {
                    chunker = StoreChunker(names)
                }
                override fun row(cells: List<Cell>): Boolean {
                    chunker!!.add(cells)   // throws the typed caps — the executor stops reading there
                    return true
                }
            })
            (chunker ?: throw IllegalStateException("query produced no columns")).finish()
        }
        if (chunks.size == 1) return Chunk(chunks[0], NO_HANDLE, false)
        val handle = synchronized(parked) {
            if (parked.size >= ExtensionContract.STORE_MAX_OPEN_RESULTS) {
                throw IllegalStateException(ExtensionContract.STORE_RESULTS_OPEN)
            }
            val h = nextHandle++
            parked[h] = ArrayDeque(chunks.subList(1, chunks.size))
            h
        }
        return Chunk(chunks[0], handle, true)
    }

    fun next(handle: Int): Chunk {
        check()
        synchronized(parked) {
            val queue = parked[handle] ?: throw IllegalStateException("no open result $handle")
            val bytes = queue.removeFirst()
            if (queue.isEmpty()) {
                parked.remove(handle)
                return Chunk(bytes, NO_HANDLE, false)
            }
            return Chunk(bytes, handle, true)
        }
    }

    fun close(handle: Int) {
        check()
        synchronized(parked) { parked.remove(handle) }
    }

    private fun readVersion(): Int {
        var version = 0
        executor.query(READ_VERSION, emptyList(), object : StoreExecutor.RowSink {
            override fun columns(names: List<String>) = Unit
            override fun row(cells: List<Cell>): Boolean {
                version = (cells[0] as Cell.Integer).value.toInt()
                return false
            }
        })
        return version
    }

    /** Runs an executor call; a failure that Binder could not carry becomes `IllegalStateException`. */
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("store I/O failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }

    private fun check() {
        if (revoked) throw SecurityException("store binder revoked")
        if (callingUid() != extUid) throw SecurityException("store binder belongs to another uid")
    }

    companion object {
        const val NO_HANDLE = -1
        const val READ_VERSION = "SELECT version FROM ${StoreFormat.HOST_SCHEMA_TABLE} WHERE id = 0"
        const val WRITE_VERSION = "UPDATE ${StoreFormat.HOST_SCHEMA_TABLE} SET version = ? WHERE id = 0"
    }
}
