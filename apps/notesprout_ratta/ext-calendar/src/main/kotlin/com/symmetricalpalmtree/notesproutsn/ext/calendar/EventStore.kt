package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.Row
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreReads
import com.symmetricalpalmtree.notesproutsn.ink.InkStore
import java.time.LocalDate

/**
 * The events half of the calendar's store (arc 24 / Z1), on `:ext-ink`'s [InkStore] — the same base
 * the pages half sits on, so the batch split, the compensated multi-batch write, the one-rule error
 * mapping and the planned stroke read are shared rather than repeated.
 *
 * **Blocking** — every call runs on `Dispatchers.IO` or a Binder thread, never Main. Every failure
 * is `StoreUnavailable`; the two refusals that are *not* store failures ([EventRules.Problem]) are
 * `IllegalArgumentException`, raised before anything is sent.
 *
 * **It does not apply the schema.** [CalendarStore.open] already did, and the host refuses `exec` /
 * `query` on a binder that has not declared — which is fine here, because the calendar screen is
 * the only door to the events screens and it always opens first.
 *
 * **No read carries an `IN (…)` list** ([EventSql]). A day (or a month's range) costs six queries
 * whatever it holds: the one-offs the range overlaps and their reminders, then the recurring set
 * and its three child sets, each by a JOIN. Expansion happens in Kotlin, because no `WHERE` can
 * answer "does this rule land on that day".
 *
 * **A bad row is a dropped event, never a lost day** ([EventRows]): undecodable rows are counted
 * and logged once per read, and the day still lists everything else. Logs carry counts, ids and
 * durations — **never a title or a note**: event text is user content.
 */
class EventStore(
    store: IExtensionStore,
    maxPayloadBytes: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
    maxBatchStatements: Int = ExtensionContract.STORE_MAX_BATCH_STATEMENTS,
    private val clock: () -> Long = System::currentTimeMillis,
) : InkStore(store, maxPayloadBytes, maxBatchStatements, TAG) {

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * Every day in `[from, to]` that holds anything, mapped to its events in [EventOrder.DAY].
     * Ascending, and **days with nothing are absent** — a month grid asks about 42 days and usually
     * cares about four of them.
     */
    fun eventsInRange(from: LocalDate, to: LocalDate): Map<LocalDate, List<Event>> = guard {
        var dropped = 0
        val oneOffRows = StoreReads.all(store, EventSql.selectOneOffsOverlapping(from, to)).rows
        val oneOffReminders = remindersBy(EventSql.selectRemindersOverlapping(from, to))
        val recurringRows = StoreReads.all(store, EventSql.selectRecurring()).rows
        val weekdays = weekdaysBy()
        val exceptions = exceptionsBy()
        val recurringReminders = remindersBy(EventSql.selectRecurringReminders())

        val oneOffs = ArrayList<Event>(oneOffRows.size)
        for (row in oneOffRows) {
            val e = decode(row, weekdays, exceptions, oneOffReminders)
            if (e == null) dropped++ else oneOffs += e
        }
        val recurring = ArrayList<Event>(recurringRows.size)
        for (row in recurringRows) {
            val e = decode(row, weekdays, exceptions, recurringReminders)
            if (e == null) dropped++ else recurring += e
        }
        if (dropped > 0) Log.w(TAG, "$dropped event row(s) dropped")

        val out = LinkedHashMap<LocalDate, List<Event>>()
        var day = from
        while (!day.isAfter(to)) {
            val onDay = ArrayList<Event>()
            for (e in oneOffs) if (!e.startDate.isAfter(day) && !e.endDate.isBefore(day)) onDay += e
            for (e in recurring) if (Recurrence.occursOn(e, day)) onDay += e
            if (onDay.isNotEmpty()) out[day] = onDay.sortedWith(EventOrder.DAY)
            day = day.plusDays(1)
        }
        out
    }

    /** One day's events, in [EventOrder.DAY]. */
    fun eventsOn(day: LocalDate): List<Event> = eventsInRange(day, day)[day].orEmpty()

    /** The **Upcoming** look-ahead for [day] ([Upcoming]) — the one-offs starting inside the window
     *  and the whole recurring set, each probed against its own reminders. */
    fun upcomingOn(day: LocalDate): List<UpcomingEvent> = guard {
        val horizon = day.plusDays(Upcoming.MAX_LOOKAHEAD_DAYS.toLong())
        var dropped = 0
        val oneOffRows = StoreReads.all(store, EventSql.selectOneOffsStartingIn(day, horizon)).rows
        val oneOffReminders = remindersBy(EventSql.selectRemindersStartingIn(day, horizon))
        val recurringRows = StoreReads.all(store, EventSql.selectRecurring()).rows
        val weekdays = weekdaysBy()
        val exceptions = exceptionsBy()
        val recurringReminders = remindersBy(EventSql.selectRecurringReminders())

        val oneOffs = ArrayList<Event>(oneOffRows.size)
        for (row in oneOffRows) {
            val e = decode(row, weekdays, exceptions, oneOffReminders)
            if (e == null) dropped++ else oneOffs += e
        }
        val recurring = ArrayList<Event>(recurringRows.size)
        for (row in recurringRows) {
            val e = decode(row, weekdays, exceptions, recurringReminders)
            if (e == null) dropped++ else recurring += e
        }
        if (dropped > 0) Log.w(TAG, "$dropped event row(s) dropped")
        Upcoming.forDay(day, oneOffs, recurring)
    }

    /** One event by id, with its children; null when there is no such row or it will not decode. */
    fun get(id: String): Event? = guard {
        val row = StoreReads.all(store, EventSql.selectEvent(id)).rows.firstOrNull() ?: return@guard null
        val weekdays = LinkedHashSet<Int>()
        for (r in StoreReads.all(store, EventSql.selectWeekdays(id)).rows) EventRows.weekday(r)?.let { weekdays += it }
        val exceptions = LinkedHashSet<LocalDate>()
        for (r in StoreReads.all(store, EventSql.selectExceptions(id)).rows) EventRows.exceptionDate(r)?.let { exceptions += it }
        val reminders = ArrayList<Reminder>()
        for (r in StoreReads.all(store, EventSql.selectReminders(id)).rows) EventRows.reminder(r)?.let { reminders += it }
        EventRows.decode(row, weekdays, exceptions, EventRules.normalize(reminders))
    }

    /** The event's note, read in planned ranges — a note is one page, but a page has no ceiling. */
    fun readNote(eventId: String): List<Pair<Long, Stroke>> = guard {
        readStrokes(eventId, NoteSql.selectStrokeLens(eventId)) { NoteSql.selectStrokes(eventId, it) }
    }

    /** Where new ink on the note starts numbering; `-1` when it holds none. */
    fun noteMaxOrder(eventId: String): Long = guard {
        StoreReads.all(store, NoteSql.selectMaxOrder(eventId)).rows.firstOrNull()?.long("maxOrder") ?: -1L
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * Save [e] — the row, its three child sets and [noteStatements], one statement list and
     * therefore one transaction whenever it fits the batch cap.
     *
     * The caps run first ([EventRules]) whatever the editor already did, and a [EventRules.Problem]
     * is an `IllegalArgumentException` rather than a silent write: a row that occurs on no day is
     * invisible to every query, which would look exactly like a lost save.
     *
     * Past the cap the write is several transactions and a failure part-way is **compensated**. What
     * the compensation is depends on what this save was: a **new** event that only half landed is
     * not an event, so it is deleted by id and the cascade takes whatever children did land; an
     * **existing** event keeps its row and gives back only the strokes this save minted, one
     * `DELETE` each (never an `IN (…)` list — the calendar's placement rule).
     */
    fun save(
        e: Event,
        isNew: Boolean,
        noteStatements: List<Statement> = emptyList(),
        mintedStrokeIds: List<String> = emptyList(),
    ) {
        val event = refuseProblems(e)
        val now = clock()
        guard {
            compensated(EventWrites.save(event, now, noteStatements)) { compensation(event.id, isNew, mintedStrokeIds) }
        }
        Slog.d(TAG) { "save ${event.id}: ${if (isNew) "new" else "existing"}, ${noteStatements.size} note statement(s)" }
    }

    /** Delete at [scope] as seen on [viewedDay]; false when the day maps to no occurrence and there
     *  is nothing to do (never a whole-series delete by accident). */
    fun delete(scope: Scope, event: Event, viewedDay: LocalDate): Boolean {
        val statements = EventWrites.deleteWithScope(scope, event, viewedDay, clock()) ?: return false
        execAll(statements)
        Slog.d(TAG) { "delete ${event.id} at $scope: ${statements.size} statement(s)" }
        return true
    }

    /**
     * Edit at [scope] as seen on [viewedDay]. Answers **the id the edited fields landed under** —
     * the original's for an in-place series edit, a freshly minted one for a "this occurrence"
     * override or a new series — or null when the day maps to no occurrence and there is nothing
     * to do. [original] is null for a brand-new event, which is always a plain save.
     */
    fun edit(
        scope: Scope,
        original: Event?,
        edited: Event,
        viewedDay: LocalDate,
        noteStatements: List<Statement> = emptyList(),
        mintedStrokeIds: List<String> = emptyList(),
    ): String? {
        val event = refuseProblems(edited)
        val newId = newId()
        val landedUnder = EventWrites.editLandsUnder(scope, original, event, viewedDay, newId) ?: return null
        val now = clock()
        val statements = EventWrites.editWithScope(scope, original, event, viewedDay, newId, now, noteStatements) ?: return null
        guard {
            compensated(statements) { compensation(landedUnder, landedUnder == newId, mintedStrokeIds) }
        }
        Slog.d(TAG) { "edit ${event.id} at $scope → $landedUnder: ${statements.size} statement(s)" }
        return landedUnder
    }

    /** A fresh event (or note-page) id. */
    fun newId(): String = CalendarStore.newId()

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** [EventRules] applied, and its [EventRules.Problem]s refused — outside `guard`, because these
     *  are the caller's mistake and not the store being gone. */
    private fun refuseProblems(e: Event): Event {
        val event = EventRules.normalize(e)
        EventRules.problem(event)?.let { throw IllegalArgumentException("event refused: $it") }
        return event
    }

    /** What a failed multi-batch write gives back — see [save]. */
    private fun compensation(id: String, isNew: Boolean, mintedStrokeIds: List<String>): List<Statement> =
        if (isNew) listOf(EventSql.deleteEvent(id)) else mintedStrokeIds.map { NoteSql.dropStroke(it) }

    private fun decode(
        row: Row,
        weekdays: Map<String, Set<Int>>,
        exceptions: Map<String, Set<LocalDate>>,
        reminders: Map<String, List<Reminder>>,
    ): Event? {
        val id = try {
            row.text("id")
        } catch (e: Exception) {
            return null
        }
        return EventRows.decode(row, weekdays[id].orEmpty(), exceptions[id].orEmpty(), reminders[id].orEmpty())
    }

    private fun weekdaysBy(): Map<String, Set<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        for (row in StoreReads.all(store, EventSql.selectRecurringWeekdays()).rows) {
            val id = eventIdOf(row) ?: continue
            EventRows.weekday(row)?.let { out.getOrPut(id) { LinkedHashSet() } += it }
        }
        return out
    }

    private fun exceptionsBy(): Map<String, Set<LocalDate>> {
        val out = HashMap<String, MutableSet<LocalDate>>()
        for (row in StoreReads.all(store, EventSql.selectRecurringExceptions()).rows) {
            val id = eventIdOf(row) ?: continue
            EventRows.exceptionDate(row)?.let { out.getOrPut(id) { LinkedHashSet() } += it }
        }
        return out
    }

    /** The reminders of whatever set [statement] selects, grouped by event and normalized — one
     *  order and one cap, wherever the rows came from. */
    private fun remindersBy(statement: Statement): Map<String, List<Reminder>> {
        val out = HashMap<String, MutableList<Reminder>>()
        for (row in StoreReads.all(store, statement).rows) {
            val id = eventIdOf(row) ?: continue
            EventRows.reminder(row)?.let { out.getOrPut(id) { ArrayList() } += it }
        }
        return out.mapValues { (_, list) -> EventRules.normalize(list) }
    }

    private fun eventIdOf(row: Row): String? = try {
        row.text("eventId")
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "EventStore"
    }
}
