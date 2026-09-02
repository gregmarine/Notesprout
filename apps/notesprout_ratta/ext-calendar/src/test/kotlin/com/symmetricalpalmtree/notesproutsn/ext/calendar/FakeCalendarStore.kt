package com.symmetricalpalmtree.notesproutsn.ext.calendar

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
import com.symmetricalpalmtree.notesproutsn.ink.StrokeBlob

/**
 * The calendar's test double for the host's store (arc 23 / Y1 — the pad's recipe): a **statement
 * recorder** (every `exec` decoded and kept as its own batch) and a **canned-row responder** for the
 * reads the calendar makes, answered from a tiny in-memory picture of the tables the test sets up
 * directly. Writes are recorded, never applied: what these tests pin is the statements the calendar
 * emits, and real SQL is proved on the Nomad.
 *
 * Every statement is run through [StoreSql] on the way in, so a builder the host would refuse fails
 * here first.
 */
class FakeCalendarStore : IExtensionStore {

    class Period(val id: String, val kind: Int, val date: String)
    class Page(val id: String, val periodId: String, val half: Int, val width: Float, val height: Float, val strokes: List<Pair<Long, Stroke>>)

    val periods = ArrayList<Period>()
    val pages = ArrayList<Page>()
    val state = HashMap<String, String>()

    fun period(id: String, kind: Int, date: String) { periods += Period(id, kind, date) }
    fun page(id: String, periodId: String, half: Int, width: Float, height: Float, strokes: List<Pair<Long, Stroke>> = emptyList()) {
        pages += Page(id, periodId, half, width, height, strokes)
    }

    var schema: StoreSchema? = null
    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()
    val calls = ArrayList<String>()
    var failExecAt: Int = -1
    private var execCount = 0
    var failWith: (() -> Throwable)? = null

    val statements: List<Statement> get() = execs.flatten()
    fun sql(): List<String> = statements.map { it.sql }

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

    fun name(s: Statement): String = when {
        s.sql.startsWith("SELECT id FROM period") -> "period"
        s.sql.startsWith("SELECT page.id AS id") -> "page"
        s.sql.startsWith("SELECT \"order\", LENGTH(blob)") -> "lens"
        s.sql.startsWith("SELECT id, \"order\"") -> "strokes"
        s.sql.startsWith("SELECT COALESCE(MAX(\"order\")") -> "maxOrder"
        s.sql.startsWith("SELECT key, value FROM state") -> "state"
        s.sql.startsWith("SELECT (SELECT COUNT(*) FROM period)") -> "counts"
        else -> error("unexpected read: ${s.sql}")
    }

    private fun periodOf(kind: Long, date: String) = periods.firstOrNull { it.kind.toLong() == kind && it.date == date }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (name(s)) {
        "period" -> listOf("id") to listOfNotNull(periodOf(long(s, 0), text(s, 1))?.let { listOf<Cell>(Cell.Text(it.id)) })
        "page" -> {
            val p = periodOf(long(s, 0), text(s, 1))
            val page = p?.let { pp -> pages.firstOrNull { it.periodId == pp.id && it.half.toLong() == long(s, 2) } }
            listOf("id", "periodId", "width", "height") to listOfNotNull(
                page?.let { listOf(Cell.Text(it.id), Cell.Text(it.periodId), Cell.Real(it.width.toDouble()), Cell.Real(it.height.toDouble())) },
            )
        }
        "lens" -> listOf("order", "len") to strokesOf(text(s, 0)).map { (order, stroke) ->
            listOf(Cell.Integer(order), Cell.Integer(StrokeBlob.encode(stroke).size.toLong()))
        }
        "strokes" -> {
            val from = long(s, 1); val to = long(s, 2)
            listOf("id", "order", "color", "width", "style", "blob") to
                strokesOf(text(s, 0)).filter { it.first in from..to }.map { (order, stroke) -> row(order, stroke) }
        }
        "maxOrder" -> listOf("maxOrder") to listOf(listOf(Cell.Integer(strokesOf(text(s, 0)).maxOfOrNull { it.first } ?: -1L)))
        "state" -> listOf("key", "value") to state.map { (k, v) -> listOf(Cell.Text(k), Cell.Text(v)) }
        "counts" -> listOf("periods", "pages", "strokes") to listOf(
            listOf(Cell.Integer(periods.size.toLong()), Cell.Integer(pages.size.toLong()), Cell.Integer(pages.sumOf { it.strokes.size }.toLong())),
        )
        else -> error("unreachable")
    }

    private fun strokesOf(pageId: String) = pages.firstOrNull { it.id == pageId }?.strokes.orEmpty()

    fun row(order: Long, stroke: Stroke): List<Cell> = listOf(
        Cell.Text(stroke.id), Cell.Integer(order), Cell.Integer(stroke.color.toLong()),
        Cell.Real(stroke.width.toDouble()), Cell.Text(stroke.style.name), Cell.Blob(StrokeBlob.encode(stroke)),
    )

    private fun text(s: Statement, i: Int): String = (s.args[i] as Cell.Text).value
    private fun long(s: Statement, i: Int): Long = (s.args[i] as Cell.Integer).value
}
