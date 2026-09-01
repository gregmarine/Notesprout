package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import com.symmetricalpalmtree.notesproutsn.extension.StorePayload
import com.symmetricalpalmtree.notesproutsn.extension.StoreResult
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import com.symmetricalpalmtree.notesproutsn.extension.TagRules

/**
 * The tag manager's test double for the host's store (arc 22 / X3). There is no SQLite on the JVM,
 * so this is what the arc's recipe calls for: a **statement recorder** (every `exec` decoded and
 * kept as its own batch) and a **canned-row responder** over a tiny in-memory picture of the two
 * tables. Every statement is run through [StoreSql] on the way in, so a builder the host would
 * refuse fails here first.
 *
 * It differs from the scratch pad's fake in one deliberate way: **the writes are applied**, not only
 * recorded. `assign`'s whole shape rests on a post-write re-read seeing the write — that is how a
 * cap refusal, which `INSERT OR IGNORE` reports as silence, becomes a typed failure — so a fake that
 * only recorded could not exercise it at all. The application is kept minimal and literal: it
 * honours `OR IGNORE`, the identity resolution inside [TagSql.insertAssignment], and both `COUNT`
 * caps, and nothing else. Real SQL is still proved on the Nomad.
 */
class FakeTagStore : IExtensionStore {

    // ── The picture of the tables ──────

    /** `tag` rows, keyed by identity — first writer wins, which is the UNIQUE index. */
    val tags = LinkedHashMap<String, Row>()

    /** `assignment` rows as their primary key: (tagId, notebookId, pageId). */
    val assignments = LinkedHashSet<Triple<String, String, String>>()

    class Row(val id: String, val display: String)

    /** Seed a tag directly (id minted from the identity so tests can name it). */
    fun tag(id: String, display: String): Row {
        val row = Row(id, display)
        tags[TagRules.identityKey(display)] = row
        return row
    }

    fun assign(tagId: String, notebookId: String, pageId: String = "") {
        assignments += Triple(tagId, notebookId, pageId)
    }

    // ── What was asked ──────

    var schema: StoreSchema? = null
    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()

    /** Call kinds in order — `applySchema`, `exec(n)`, `query(<name>)` — for ordering assertions. */
    val calls = ArrayList<String>()

    /** Set to make every call fail — the "the store is gone" half of the adapter's one rule. */
    var failWith: (() -> Throwable)? = null

    /** Run just before an `exec` is applied — the seam a test needs to stage a **concurrent writer**
     *  landing between `assign`'s pre-read and its batch, which is the one case the whole
     *  resolve-by-identity shape exists for. */
    var beforeExec: (() -> Unit)? = null

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
        beforeExec?.invoke()
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

    // ── The five reads ──────

    /** A short name for a read, so a test can assert on call ORDER without matching whole SQL. */
    fun name(s: Statement): String = when {
        s.sql.startsWith("SELECT id, display FROM tag") -> "tags"
        s.sql.startsWith("SELECT t.id, t.display") -> "identity"
        s.sql.startsWith("SELECT SUM(pageId") -> "usage"
        s.sql.startsWith("SELECT tagId, notebookId, pageId FROM assignment WHERE notebookId") -> "ofNotebook"
        s.sql.startsWith("SELECT tagId, notebookId, pageId FROM assignment WHERE tagId IN") -> "ofTags"
        else -> error("unexpected read: ${s.sql}")
    }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (name(s)) {
        "tags" -> {
            val limit = int(s, s.args.size - 2)
            val offset = int(s, s.args.size - 1)
            val ordered = tags.values.sortedWith(
                compareBy({ TagRules.identityKey(it.display) }, { it.display }),
            )
            listOf("id", "display") to ordered.drop(offset).take(limit)
                .map { listOf(Cell.Text(it.id), Cell.Text(it.display)) }
        }
        "identity" -> {
            val notebookId = text(s, 0)
            val pageId = text(s, 1)
            val row = tags[text(s, 2)]
            listOf("id", "display", "attached") to
                if (row == null) emptyList()
                else listOf(
                    listOf(
                        Cell.Text(row.id),
                        Cell.Text(row.display),
                        Cell.Integer(if (Triple(row.id, notebookId, pageId) in assignments) 1L else 0L),
                    ),
                )
        }
        "usage" -> {
            val mine = assignments.filter { it.first == text(s, 0) }
            listOf("notebooks", "pages") to
                if (mine.isEmpty()) listOf(listOf(Cell.Null, Cell.Null))   // SUM over no rows is NULL
                else listOf(
                    listOf(
                        Cell.Integer(mine.count { it.third.isEmpty() }.toLong()),
                        Cell.Integer(mine.count { it.third.isNotEmpty() }.toLong()),
                    ),
                )
        }
        "ofNotebook" -> {
            val notebookId = text(s, 0)
            listOf("tagId", "notebookId", "pageId") to
                assignments.filter { it.second == notebookId }
                    .sortedWith(compareBy({ it.first }, { it.third }))
                    .map { listOf(Cell.Text(it.first), Cell.Text(it.second), Cell.Text(it.third)) }
        }
        "ofTags" -> {
            val ids = (0 until s.args.size - 2).map { text(s, it) }.toSet()
            val limit = int(s, s.args.size - 2)
            val offset = int(s, s.args.size - 1)
            listOf("tagId", "notebookId", "pageId") to
                assignments.filter { it.first in ids }
                    .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
                    .drop(offset).take(limit)
                    .map { listOf(Cell.Text(it.first), Cell.Text(it.second), Cell.Text(it.third)) }
        }
        else -> error("unreachable")
    }

    // ── The four writes, applied literally ──────

    /** Answers `changes()` — the rows the statement touched — and moves the picture. */
    private fun apply(s: Statement): Long = when {
        s.sql.startsWith("INSERT OR IGNORE INTO tag") -> {
            val id = text(s, 0)
            val display = text(s, 1)
            val key = text(s, 2)
            val tagCap = int(s, 4)
            val assignmentCap = int(s, 5)
            // The three things `INSERT OR IGNORE … WHERE (SELECT COUNT(*)) < ? AND …` actually do.
            if (tags.size >= tagCap || assignments.size >= assignmentCap || key in tags) 0L
            else { tags[key] = Row(id, display); 1L }
        }
        s.sql.startsWith("INSERT OR IGNORE INTO assignment") -> {
            val notebookId = text(s, 0)
            val pageId = text(s, 1)
            val key = text(s, 3)
            val cap = int(s, 4)
            val row = tags[key]
            // No such identity ⇒ the SELECT supplies no row ⇒ nothing is inserted. That is the
            // whole point of resolving the tag id inside the statement.
            if (row == null || assignments.size >= cap) 0L
            else if (assignments.add(Triple(row.id, notebookId, pageId))) 1L else 0L
        }
        s.sql.startsWith("DELETE FROM assignment") ->
            if (assignments.remove(Triple(text(s, 0), text(s, 1), text(s, 2)))) 1L else 0L
        s.sql.startsWith("DELETE FROM tag") -> {
            val id = text(s, 0)
            val key = tags.entries.firstOrNull { it.value.id == id }?.key
            if (key == null) 0L else {
                tags.remove(key)
                assignments.removeAll { it.first == id }   // the declared ON DELETE CASCADE
                1L
            }
        }
        else -> error("unexpected write: ${s.sql}")
    }

    private fun text(s: Statement, index: Int): String = (s.args[index] as Cell.Text).value
    private fun int(s: Statement, index: Int): Int = (s.args[index] as Cell.Integer).value.toInt()
}
