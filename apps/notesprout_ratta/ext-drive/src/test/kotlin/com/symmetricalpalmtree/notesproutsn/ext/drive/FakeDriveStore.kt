package com.symmetricalpalmtree.notesproutsn.ext.drive

import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import com.symmetricalpalmtree.notesproutsn.extension.StorePayload
import com.symmetricalpalmtree.notesproutsn.extension.StoreResult
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql

/**
 * The Drive provider's test double for the host's store (arc 25 / V1) — there is no SQLite on the
 * JVM, so this is `:ext-tags`' `FakeTagStore` recipe: a tiny in-memory picture of `account`, with
 * every statement run through [StoreSql] on the way in (a builder the host would refuse fails here
 * first) and the four statement shapes applied literally. Real SQL is still proved on the Nomad.
 */
class FakeDriveStore : IExtensionStore {

    /** `account` rows, keyed by `key`. */
    val account = LinkedHashMap<String, String>()

    var schema: StoreSchema? = null

    /** Set to make every call fail — "the store is gone". */
    var failWith: (() -> Throwable)? = null

    override fun schemaVersion(): Int = schema?.version ?: 0

    override fun applySchema(schema: StoreSchema?) {
        failWith?.let { throw it() }
        this.schema = schema
    }

    override fun exec(batch: StorePayload?): LongArray {
        failWith?.let { throw it() }
        val list = StoreCodec.decodeStatements(batch!!.readAndClose())
        for (s in list) StoreSql.checkExec(s.sql)
        return LongArray(list.size) { apply(list[it]) }
    }

    override fun query(statement: StorePayload?): StoreResult {
        failWith?.let { throw it() }
        val s = StoreCodec.decodeStatements(statement!!.readAndClose()).single()
        StoreSql.checkQuery(s.sql)
        val (columns, rows) = rowsFor(s)
        return StoreResult(StorePayload(StoreCodec.encodeRows(columns, rows), null), StoreResult.NO_HANDLE, false)
    }

    override fun next(handle: Int): StoreResult = throw IllegalStateException("no parked results")

    override fun close(handle: Int) = Unit

    override fun asBinder(): IBinder? = null

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> {
        require(s.sql.startsWith("SELECT value FROM account WHERE key = ?")) { "unexpected read: ${s.sql}" }
        val key = text(s, 0)
        val value = account[key]
        return listOf("value") to
            if (value == null) emptyList() else listOf(listOf(Cell.Text(value)))
    }

    private fun apply(s: Statement): Long = when {
        s.sql.startsWith("INSERT OR REPLACE INTO account") -> {
            account[text(s, 0)] = text(s, 1)
            1L
        }
        s.sql.startsWith("DELETE FROM account WHERE key = ?") -> {
            if (account.remove(text(s, 0)) != null) 1L else 0L
        }
        s.sql == "DELETE FROM account" -> {
            val n = account.size.toLong()
            account.clear()
            n
        }
        else -> error("unexpected write: ${s.sql}")
    }

    private fun text(s: Statement, index: Int): String = (s.args[index] as Cell.Text).value
}
