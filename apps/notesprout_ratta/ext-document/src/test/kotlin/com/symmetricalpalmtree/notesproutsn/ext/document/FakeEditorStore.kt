package com.symmetricalpalmtree.notesproutsn.ext.document

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
 * The document editor's test double for the host's store (arc 22 / X4). There is no SQLite on the
 * JVM, so this is what the arc's recipe calls for: a **statement recorder** (every `exec` decoded
 * and kept as its own batch) and a **canned-row responder** over a tiny in-memory picture of the
 * three tables. Every statement is run through [StoreSql] on the way in, so a builder the host would
 * refuse fails here first.
 *
 * Like the tag manager's fake, and unlike the scratch pad's, **the writes are applied** — a
 * read-after-write test is then real rather than a restatement of the fake. The application is kept
 * minimal and literal: `OR REPLACE` on a pref and a caret, `OR IGNORE` on a word, the two deletes,
 * and the LRU trim's `ORDER BY updatedAt DESC LIMIT ?`. Real SQL is still proved on the Nomad.
 */
class FakeEditorStore : IExtensionStore {

    // ── The picture of the three tables ──────

    /** `prefs` rows: key → value. */
    val prefs = LinkedHashMap<String, String>()

    /** `word` rows: word → addedAt. Insertion order here is irrelevant — [selectWords] sorts. */
    val words = LinkedHashMap<String, Long>()

    /** `caret` rows: pageKey → (offset, updatedAt). */
    val carets = LinkedHashMap<String, Caret>()

    class Caret(val offset: Int, val updatedAt: Long)

    fun caret(pageKey: String, offset: Int, updatedAt: Long) {
        carets[pageKey] = Caret(offset, updatedAt)
    }

    // ── What was asked ──────

    var schema: StoreSchema? = null
    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()

    /** Call kinds in order — `applySchema`, `exec(n)`, `query(<name>)` — for ordering assertions. */
    val calls = ArrayList<String>()

    /** Set to make every call fail — the "the store is gone" half of the facade's one rule. */
    var failWith: (() -> Throwable)? = null

    /** Every recorded statement, batches flattened. */
    val statements: List<Statement> get() = execs.flatten()

    fun sql(): List<String> = statements.map { it.sql }

    // ── IExtensionStore ──────

    override fun schemaVersion(): Int = schema?.version ?: 0

    override fun applySchema(schema: StoreSchema?) {
        failWith?.let { throw it() }
        this.schema = schema
        calls += "applySchema"
    }

    override fun exec(batch: StorePayload?): LongArray {
        failWith?.let { throw it() }
        val list = StoreCodec.decodeStatements(batch!!.readAndClose())
        for (s in list) StoreSql.checkExec(s.sql)
        calls += "exec(${list.size})"
        execs += list
        return LongArray(list.size) { apply(list[it]) }
    }

    override fun query(statement: StorePayload?): StoreResult {
        failWith?.let { throw it() }
        val s = StoreCodec.decodeStatements(statement!!.readAndClose()).single()
        StoreSql.checkQuery(s.sql)
        queries += s
        calls += "query(${name(s)})"
        val (columns, rows) = rowsFor(s)
        return StoreResult(StorePayload(StoreCodec.encodeRows(columns, rows), null), StoreResult.NO_HANDLE, false)
    }

    override fun next(handle: Int): StoreResult = throw IllegalStateException("no parked results")

    override fun close(handle: Int) = Unit

    override fun asBinder(): IBinder? = null

    // ── The three reads ──────

    /** A short name for a read, so a test can assert on call ORDER without matching whole SQL. */
    fun name(s: Statement): String = when {
        s.sql.startsWith("SELECT value FROM prefs") -> "pref"
        s.sql.startsWith("SELECT word FROM word") -> "words"
        s.sql.startsWith("SELECT offset FROM caret") -> "caret"
        else -> error("unexpected read: ${s.sql}")
    }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (name(s)) {
        "pref" -> {
            val value = prefs[text(s, 0)]
            listOf("value") to if (value == null) emptyList() else listOf(listOf(Cell.Text(value)))
        }
        "words" -> listOf("word") to
            words.entries.sortedWith(compareBy({ it.value }, { it.key }))
                .map { listOf<Cell>(Cell.Text(it.key)) }
        "caret" -> {
            val row = carets[text(s, 0)]
            listOf("offset") to
                if (row == null) emptyList() else listOf(listOf(Cell.Integer(row.offset.toLong())))
        }
        else -> error("unreachable")
    }

    // ── The five writes, applied literally ──────

    /** Answers `changes()` — the rows the statement touched — and moves the picture. */
    private fun apply(s: Statement): Long = when {
        s.sql.startsWith("INSERT OR REPLACE INTO prefs") -> {
            prefs[text(s, 0)] = text(s, 1)
            1L
        }
        s.sql.startsWith("INSERT OR IGNORE INTO word") -> {
            val word = text(s, 0)
            // OR IGNORE: a word already there keeps its ORIGINAL addedAt and reports no change.
            if (word in words) 0L else { words[word] = int(s, 1); 1L }
        }
        s.sql.startsWith("DELETE FROM word") -> if (words.remove(text(s, 0)) != null) 1L else 0L
        s.sql.startsWith("INSERT OR REPLACE INTO caret") -> {
            carets[text(s, 0)] = Caret(int(s, 1).toInt(), int(s, 2))
            1L
        }
        s.sql.startsWith("DELETE FROM caret WHERE pageKey NOT IN") -> {
            val limit = int(s, 0).toInt()
            val keep = carets.entries.sortedByDescending { it.value.updatedAt }
                .take(limit).map { it.key }.toSet()
            val going = carets.keys.filter { it !in keep }
            for (key in going) carets.remove(key)
            going.size.toLong()
        }
        else -> error("unexpected write: ${s.sql}")
    }

    private fun text(s: Statement, index: Int): String = (s.args[index] as Cell.Text).value
    private fun int(s: Statement, index: Int): Long = (s.args[index] as Cell.Integer).value
}
