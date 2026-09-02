package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.os.IBinder
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import com.symmetricalpalmtree.notesproutsn.extension.StorePayload
import com.symmetricalpalmtree.notesproutsn.extension.StoreResult
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.extension.StoreSql
import com.symmetricalpalmtree.notesproutsn.ink.PageInk

/**
 * The pad's test double for the host's store (arc 22 / X2). There is no SQLite on the JVM, so this
 * is what the arc's recipe calls for: a **statement recorder** (every `exec` decoded and kept as
 * its own batch, so batch boundaries are visible) and a **canned-row responder** — the six reads
 * the pad makes are answered from a tiny in-memory picture of the tables that the test sets up
 * directly. Writes are recorded, never applied: what these tests pin is the statements the pad
 * emits, and real SQL is proved on the Nomad.
 *
 * Every statement is run through [StoreSql] on the way in, so a builder the host would refuse fails
 * here first.
 */
class FakeScratchStore : IExtensionStore {

    // ── The canned picture of the tables ──────

    var pages: List<String> = emptyList()
    var current: String? = null
    val ink = HashMap<String, PageInk>()

    /** Add a page with its ink, in list order. */
    fun page(id: String, ink: PageInk = PageInk.EMPTY) {
        pages = pages + id
        this.ink[id] = ink
        if (current == null) current = id
    }

    // ── What was asked ──────

    var schema: StoreSchema? = null
    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()

    /** Call kinds in order — `applySchema`, `exec(n)`, `query(<name>)` — for ordering assertions. */
    val calls = ArrayList<String>()

    /** The `exec` call (0-based) that throws, or −1. */
    var failExecAt: Int = -1
    private var execCount = 0

    /** Set to make every call fail — the "the store is gone" half of the adapter's one rule. */
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
        if (execCount++ == failExecAt) throw IllegalStateException("exec failed")
        execs += list
        return LongArray(list.size) { 1L }
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

    // ── The six reads ──────

    /** A short name for a read, so a test can assert on call ORDER without matching whole SQL. */
    fun name(s: Statement): String = when {
        s.sql.startsWith("SELECT id FROM page") -> "pages"
        s.sql.startsWith("SELECT value FROM state") -> "current"
        s.sql.startsWith("SELECT width, height FROM page") -> "size"
        s.sql.startsWith("SELECT \"order\", LENGTH(blob)") -> "lens"
        s.sql.startsWith("SELECT id, \"order\"") -> "strokes"
        s.sql.startsWith("SELECT COALESCE(MAX(\"order\")") -> "maxOrder"
        else -> error("unexpected read: ${s.sql}")
    }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (name(s)) {
        "pages" -> listOf("id") to pages.map { listOf(Cell.Text(it)) }
        "current" -> listOf("value") to (current?.let { listOf(listOf<Cell>(Cell.Text(it))) } ?: emptyList())
        "size" -> {
            val p = ink[arg(s, 0)]
            listOf("width", "height") to
                if (p == null) emptyList() else listOf(listOf(Cell.Real(p.width.toDouble()), Cell.Real(p.height.toDouble())))
        }
        "lens" -> listOf("order", "len") to (ink[arg(s, 0)]?.strokes.orEmpty()).map { (order, stroke) ->
            listOf(Cell.Integer(order), Cell.Integer(ScratchSql.geometry(stroke).size.toLong()))
        }
        "strokes" -> {
            val from = (s.args[1] as Cell.Integer).value
            val to = (s.args[2] as Cell.Integer).value
            listOf("id", "order", "color", "width", "style", "blob") to
                (ink[arg(s, 0)]?.strokes.orEmpty()).filter { it.first in from..to }.map { (order, stroke) -> row(order, stroke) }
        }
        "maxOrder" -> listOf("maxOrder") to
            listOf(listOf(Cell.Integer((ink[arg(s, 0)]?.strokes.orEmpty()).maxOfOrNull { it.first } ?: -1L)))
        else -> error("unreachable")
    }

    /** One `stroke` row as the host would encode it. */
    fun row(order: Long, stroke: Stroke): List<Cell> = listOf(
        Cell.Text(stroke.id),
        Cell.Integer(order),
        Cell.Integer(stroke.color.toLong()),
        Cell.Real(stroke.width.toDouble()),
        Cell.Text(stroke.style.name),
        Cell.Blob(ScratchSql.geometry(stroke)),
    )

    private fun arg(s: Statement, index: Int): String = (s.args[index] as Cell.Text).value
}
