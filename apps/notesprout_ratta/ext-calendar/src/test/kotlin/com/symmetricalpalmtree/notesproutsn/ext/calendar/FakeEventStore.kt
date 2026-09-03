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
 * The events half's test double for the host's store (arc 24 / Z1) — `FakeCalendarStore`'s recipe
 * with one deliberate difference: **it applies its writes**.
 *
 * The pages half only ever had to prove *which statements* the calendar emits, so its fake records
 * them. The events half has to prove something the recorder cannot: that a save and then a read
 * agree — that an event written with two weekdays, an exception and two reminders comes back on the
 * days it should, that a delete's cascade takes its children, that a scope operation leaves the
 * series a later read can still expand. So every `exec` here is decoded, validated, recorded **and
 * applied** to five in-memory tables by matching the statement's exact text (every shape is one of
 * [EventSql]'s or [NoteSql]'s; anything else is a test bug and fails loudly), and every `query` is
 * answered by evaluating its predicate in Kotlin over those tables. ISO dates compare as strings,
 * exactly as they do in SQLite.
 *
 * What it is still not: SQLite. Real SQL is proved on the Nomad; what is proved here is that the
 * statements this code builds say what it thinks they say.
 */
class FakeEventStore : IExtensionStore {

    // ── The tables ───────────────────────────────────────────────────────────

    /** `event`, id → the row's cells in [EventSql.COLUMNS] order. */
    val events = LinkedHashMap<String, MutableList<Cell>>()
    val weekdays = LinkedHashMap<String, LinkedHashSet<Long>>()
    val exceptions = LinkedHashMap<String, LinkedHashSet<String>>()
    val reminders = LinkedHashMap<String, LinkedHashSet<Pair<Long, String>>>()

    /** `note_stroke`, stroke id → the row. */
    val noteStrokes = LinkedHashMap<String, NoteStroke>()

    class NoteStroke(val id: String, val eventId: String, val order: Long, val cells: List<Cell>)

    // ── The knobs (`FakeCalendarStore`'s) ────────────────────────────────────

    var schema: StoreSchema? = null
    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()
    val calls = ArrayList<String>()
    var failExecAt: Int = -1
    private var execCount = 0
    var failWith: (() -> Throwable)? = null

    val statements: List<Statement> get() = execs.flatten()
    fun sql(): List<String> = statements.map { it.sql }

    // ── IExtensionStore ──────────────────────────────────────────────────────

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
        for (s in list) apply(s)
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

    // ── Applying a write ─────────────────────────────────────────────────────

    private fun apply(s: Statement) {
        val sql = s.sql
        when {
            sql.startsWith("INSERT OR IGNORE INTO event (") -> {
                val id = text(s, 0)
                if (!events.containsKey(id)) events[id] = s.args.toMutableList()
            }
            sql.startsWith("UPDATE event SET type = ") -> events[text(s, s.args.size - 1)]?.let { row ->
                for (i in 0 until 17) row[1 + i] = s.args[i]     // type … noteHeight
                row[UPDATED_AT] = s.args[17]
            }
            sql.startsWith("UPDATE event SET updatedAt = ") -> events[text(s, 1)]?.let { it[UPDATED_AT] = s.args[0] }
            sql.startsWith("UPDATE event SET endMode = ") -> events[text(s, 3)]?.let { row ->
                row[END_MODE] = s.args[0]
                row[UNTIL_DATE] = s.args[1]
                row[END_COUNT] = Cell.Null
                row[UPDATED_AT] = s.args[2]
            }
            sql.startsWith("DELETE FROM event WHERE id = ") -> {
                val id = text(s, 0)
                // The declared cascade, by hand.
                events.remove(id)
                weekdays.remove(id)
                exceptions.remove(id)
                reminders.remove(id)
                noteStrokes.values.removeAll { it.eventId == id }
            }
            sql.startsWith("DELETE FROM event_weekday WHERE eventId = ") -> weekdays.remove(text(s, 0))
            sql.startsWith("INSERT OR IGNORE INTO event_weekday (") ->
                weekdays.getOrPut(text(s, 0)) { LinkedHashSet() } += long(s, 1)
            sql.startsWith("DELETE FROM event_exception WHERE eventId = ") -> exceptions.remove(text(s, 0))
            sql.startsWith("INSERT OR IGNORE INTO event_exception (") ->
                exceptions.getOrPut(text(s, 0)) { LinkedHashSet() } += text(s, 1)
            sql.startsWith("DELETE FROM event_reminder WHERE eventId = ") -> reminders.remove(text(s, 0))
            sql.startsWith("INSERT OR IGNORE INTO event_reminder (") ->
                reminders.getOrPut(text(s, 0)) { LinkedHashSet() } += long(s, 1) to text(s, 2)
            sql.startsWith("INSERT OR REPLACE INTO note_stroke (") -> {
                val id = text(s, 0)
                noteStrokes[id] = NoteStroke(id, text(s, 1), long(s, 2), s.args)
            }
            sql.startsWith("DELETE FROM note_stroke WHERE id = ") -> noteStrokes.remove(text(s, 0))
            sql.startsWith("DELETE FROM note_stroke WHERE eventId = ") -> {
                val id = text(s, 0)
                noteStrokes.values.removeAll { it.eventId == id }
            }
            else -> error("unexpected write: $sql")
        }
    }

    // ── Answering a read ─────────────────────────────────────────────────────

    fun name(s: Statement): String {
        val sql = s.sql
        return when {
            sql.startsWith("SELECT ${EventSql.COLUMNS} FROM event WHERE id = ") -> "event"
            sql.startsWith("SELECT ${EventSql.COLUMNS} FROM event WHERE recurring = 0 AND startDate <= ") -> "oneOffsOverlapping"
            sql.startsWith("SELECT ${EventSql.COLUMNS} FROM event WHERE recurring = 0 AND startDate > ") -> "oneOffsStartingIn"
            sql.startsWith("SELECT ${EventSql.COLUMNS} FROM event WHERE recurring = 1") -> "recurring"
            sql.startsWith("SELECT weekday FROM event_weekday") -> "weekdays"
            sql.startsWith("SELECT date FROM event_exception") -> "exceptions"
            sql.startsWith("SELECT amount, unit FROM event_reminder") -> "reminders"
            sql.startsWith("SELECT w.eventId") -> "recurringWeekdays"
            sql.startsWith("SELECT x.eventId") -> "recurringExceptions"
            sql.startsWith("SELECT r.eventId") && sql.endsWith("e.recurring = 1") -> "recurringReminders"
            // The overlap read is the one that names `endDate`; the window read names `startDate` twice.
            sql.startsWith("SELECT r.eventId") && sql.contains("e.endDate >= ?") -> "remindersOverlapping"
            sql.startsWith("SELECT r.eventId") && sql.contains("e.startDate > ?") -> "remindersStartingIn"
            sql.startsWith("SELECT \"order\", LENGTH(blob) AS len FROM note_stroke") -> "noteLens"
            sql.startsWith("SELECT id, \"order\", color, width, style, blob FROM note_stroke") -> "noteStrokes"
            sql.startsWith("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM note_stroke") -> "noteMaxOrder"
            else -> error("unexpected read: $sql")
        }
    }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (val n = name(s)) {
        "event" -> EVENT_COLUMNS to listOfNotNull(events[text(s, 0)])
        "oneOffsOverlapping" -> EVENT_COLUMNS to events.values.filter {
            !recurring(it) && startDate(it) <= text(s, 0) && endDate(it) >= text(s, 1)
        }
        "oneOffsStartingIn" -> EVENT_COLUMNS to events.values.filter {
            !recurring(it) && startDate(it) > text(s, 0) && startDate(it) <= text(s, 1)
        }
        "recurring" -> EVENT_COLUMNS to events.values.filter { recurring(it) }
        "weekdays" -> listOf("weekday") to weekdays[text(s, 0)].orEmpty().map { listOf<Cell>(Cell.Integer(it)) }
        "exceptions" -> listOf("date") to exceptions[text(s, 0)].orEmpty().map { listOf<Cell>(Cell.Text(it)) }
        "reminders" -> listOf("amount", "unit") to
            reminders[text(s, 0)].orEmpty().map { listOf(Cell.Integer(it.first), Cell.Text(it.second)) }
        "recurringWeekdays" -> listOf("eventId", "weekday") to weekdays.entries
            .filter { recurringId(it.key) }
            .flatMap { (id, set) -> set.map { listOf(Cell.Text(id), Cell.Integer(it)) } }
        "recurringExceptions" -> listOf("eventId", "date") to exceptions.entries
            .filter { recurringId(it.key) }
            .flatMap { (id, set) -> set.map { listOf(Cell.Text(id), Cell.Text(it)) } }
        "recurringReminders", "remindersOverlapping", "remindersStartingIn" ->
            listOf("eventId", "amount", "unit") to reminders.entries
                .filter { (id, _) ->
                    val row = events[id] ?: return@filter false
                    when (n) {
                        "recurringReminders" -> recurring(row)
                        "remindersOverlapping" -> !recurring(row) && startDate(row) <= text(s, 0) && endDate(row) >= text(s, 1)
                        else -> !recurring(row) && startDate(row) > text(s, 0) && startDate(row) <= text(s, 1)
                    }
                }
                .flatMap { (id, set) -> set.map { listOf(Cell.Text(id), Cell.Integer(it.first), Cell.Text(it.second)) } }
        "noteLens" -> listOf("order", "len") to notesOf(text(s, 0)).map {
            listOf<Cell>(Cell.Integer(it.order), Cell.Integer((it.cells[6] as Cell.Blob).value.size.toLong()))
        }
        "noteStrokes" -> STROKE_COLUMNS to notesOf(text(s, 0))
            .filter { it.order in long(s, 1)..long(s, 2) }
            .map { listOf(it.cells[0], it.cells[2], it.cells[3], it.cells[4], it.cells[5], it.cells[6]) }
        "noteMaxOrder" -> listOf("maxOrder") to
            listOf(listOf<Cell>(Cell.Integer(notesOf(text(s, 0)).maxOfOrNull { it.order } ?: -1L)))
        else -> error("unreachable")
    }

    // ── Seeding + reading back, for the tests ────────────────────────────────

    /** Put [e] in as the store would — the same statements [EventStore.save] sends. */
    fun seed(e: Event, now: Long = e.updatedAt, noteStatements: List<Statement> = emptyList()) {
        for (s in EventWrites.save(e, now, noteStatements)) apply(s)
    }

    /** A note stroke row, written as the note's own statement would. */
    fun seedNote(eventId: String, order: Long, stroke: Stroke) {
        apply(NoteSql.putStroke(eventId, order, stroke))
    }

    private fun notesOf(eventId: String) = noteStrokes.values.filter { it.eventId == eventId }.sortedBy { it.order }

    private fun recurringId(id: String): Boolean = events[id]?.let { recurring(it) } == true
    private fun recurring(row: List<Cell>): Boolean = (row[RECURRING] as Cell.Integer).value != 0L
    private fun startDate(row: List<Cell>): String = (row[START_DATE] as Cell.Text).value
    private fun endDate(row: List<Cell>): String = (row[END_DATE] as Cell.Text).value

    private fun text(s: Statement, i: Int): String = (s.args[i] as Cell.Text).value
    private fun long(s: Statement, i: Int): Long = (s.args[i] as Cell.Integer).value

    companion object {
        val EVENT_COLUMNS: List<String> = EventSql.COLUMNS.split(", ")
        val STROKE_COLUMNS: List<String> = listOf("id", "order", "color", "width", "style", "blob")

        private val START_DATE = EVENT_COLUMNS.indexOf("startDate")
        private val END_DATE = EVENT_COLUMNS.indexOf("endDate")
        private val RECURRING = EVENT_COLUMNS.indexOf("recurring")
        private val END_MODE = EVENT_COLUMNS.indexOf("endMode")
        private val UNTIL_DATE = EVENT_COLUMNS.indexOf("untilDate")
        private val END_COUNT = EVENT_COLUMNS.indexOf("endCount")
        private val UPDATED_AT = EVENT_COLUMNS.indexOf("updatedAt")

        /** A stroke row's blob, so a test can assert what a note read gave back. */
        fun blobOf(stroke: Stroke): ByteArray = StrokeBlob.encode(stroke)
    }
}
