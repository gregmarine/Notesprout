package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import java.time.LocalDate

/**
 * Every statement the events half of the calendar sends (arc 24 / Z1), as a pure builder — SQL text
 * and bound arguments and nothing else, so every shape is JVM-testable without a store and pinned
 * by `EventSqlTest` through the host's own validator.
 *
 * Three rules the text carries, each of them a trap somewhere else in this codebase:
 *
 * - **`event` is never `INSERT OR REPLACE`d.** REPLACE deletes the conflicting row first, and with
 *   `foreign_keys` ON that delete CASCADES — it would take the event's weekdays, its exceptions,
 *   its reminders and its whole handwritten note with it (X2's trap, restated for a table that now
 *   has four children). The upsert is [insertEvent] + [updateEvent], **in that order**: two
 *   idempotent statements whose retry converges;
 * - **child rows are a set, rewritten wholesale** — `DELETE … WHERE eventId = ?` then
 *   `INSERT OR IGNORE`, in the same batch as the event row and after it, so the foreign key always
 *   has its parent;
 * - **no read carries an `IN (…)` list.** The bind limit is 999 arguments, and a day with a
 *   thousand events must not read differently from a day with three. A day's one-offs come by span
 *   overlap; the recurring set comes by its indexed flag; both sets' children come by a JOIN
 *   carrying the parent read's own predicate.
 *
 * Enum values are bound as their `name`, dates as ISO text ([CalendarDates.format] — which orders
 * correctly as text, so `startDate <= ?` is a real range scan), booleans as 0/1, and `now` is a
 * parameter rather than a clock read so a test can pin it.
 */
object EventSql {

    /** The `event` row, in one place: every SELECT of an event reads exactly these, in this order. */
    const val COLUMNS: String =
        "id, type, title, startDate, endDate, allDay, startMinute, endMinute, recurring, freq, " +
            "interval, monthlyMode, endMode, untilDate, endCount, noteText, noteWidth, noteHeight, createdAt, updatedAt"

    private const val EVENT_VALUES = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"

    // ── The event row ────────────────────────────────────────────────────────

    /** Half of the upsert: the row if it is not there. `createdAt` is the event's own — a save of an
     *  existing event carries the minute it was first written, whatever this batch is doing. */
    fun insertEvent(e: Event, now: Long): Statement =
        Statement("INSERT OR IGNORE INTO event ($COLUMNS) VALUES ($EVENT_VALUES)", listOf(Cell.of(e.id)) + body(e) + listOf(Cell.of(e.createdAt), Cell.of(now)))

    /** The other half: every column but `id` and `createdAt`, whether or not the insert landed. */
    fun updateEvent(e: Event, now: Long): Statement =
        Statement(
            "UPDATE event SET type = ?, title = ?, startDate = ?, endDate = ?, allDay = ?, startMinute = ?, " +
                "endMinute = ?, recurring = ?, freq = ?, interval = ?, monthlyMode = ?, endMode = ?, untilDate = ?, " +
                "endCount = ?, noteText = ?, noteWidth = ?, noteHeight = ?, updatedAt = ? WHERE id = ?",
            body(e) + listOf(Cell.of(now), Cell.of(e.id)),
        )

    /** The row is unchanged but something under it is not — an exception was added to its series. */
    fun touchEvent(id: String, now: Long): Statement =
        Statement("UPDATE event SET updatedAt = ? WHERE id = ?", now, id)

    /** End a series just before an occurrence ("this and following"): the rule ends on a date, and
     *  whatever count it had is meaningless once it does. */
    fun truncateEvent(id: String, untilDate: LocalDate, now: Long): Statement =
        Statement(
            "UPDATE event SET endMode = ?, untilDate = ?, endCount = NULL, updatedAt = ? WHERE id = ?",
            EndMode.UNTIL.name, CalendarDates.format(untilDate), now, id,
        )

    /** The one hard delete in this arc — the declared cascade takes the three child sets and the note. */
    fun deleteEvent(id: String): Statement =
        Statement("DELETE FROM event WHERE id = ?", id)

    // ── The three child sets ─────────────────────────────────────────────────

    fun clearWeekdays(eventId: String): Statement =
        Statement("DELETE FROM event_weekday WHERE eventId = ?", eventId)

    fun insertWeekday(eventId: String, weekday: Int): Statement =
        Statement("INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?, ?)", eventId, weekday.toLong())

    fun clearExceptions(eventId: String): Statement =
        Statement("DELETE FROM event_exception WHERE eventId = ?", eventId)

    fun insertException(eventId: String, date: LocalDate): Statement =
        Statement("INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)", eventId, CalendarDates.format(date))

    fun clearReminders(eventId: String): Statement =
        Statement("DELETE FROM event_reminder WHERE eventId = ?", eventId)

    fun insertReminder(eventId: String, amount: Int, unit: ReminderUnit): Statement =
        Statement("INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?, ?, ?)", eventId, amount.toLong(), unit.name)

    // ── Reads: the event rows ────────────────────────────────────────────────

    fun selectEvent(id: String): Statement =
        Statement("SELECT $COLUMNS FROM event WHERE id = ?", id)

    /** Every one-off whose span meets `[from, to]`. A single day is the range `[day, day]`, so this
     *  is the day read too; the arguments are bound the other way round on purpose (`startDate <= to
     *  AND endDate >= from` is what "overlaps" means). */
    fun selectOneOffsOverlapping(from: LocalDate, to: LocalDate): Statement =
        Statement(
            "SELECT $COLUMNS FROM event WHERE recurring = 0 AND startDate <= ? AND endDate >= ?",
            CalendarDates.format(to), CalendarDates.format(from),
        )

    /** Every one-off **starting** in `(fromExclusive, toInclusive]` — the Upcoming window, which is
     *  about when an event begins and never about a span already under way. */
    fun selectOneOffsStartingIn(fromExclusive: LocalDate, toInclusive: LocalDate): Statement =
        Statement(
            "SELECT $COLUMNS FROM event WHERE recurring = 0 AND startDate > ? AND startDate <= ?",
            CalendarDates.format(fromExclusive), CalendarDates.format(toInclusive),
        )

    /** The whole recurring set — expanded in Kotlin, because no `WHERE` can answer "does this rule
     *  land on that day". The `event_recurring` index is what keeps it an index scan. */
    fun selectRecurring(): Statement =
        Statement("SELECT $COLUMNS FROM event WHERE recurring = 1")

    // ── Reads: one event's children ──────────────────────────────────────────

    fun selectWeekdays(eventId: String): Statement =
        Statement("SELECT weekday FROM event_weekday WHERE eventId = ?", eventId)

    fun selectExceptions(eventId: String): Statement =
        Statement("SELECT date FROM event_exception WHERE eventId = ?", eventId)

    fun selectReminders(eventId: String): Statement =
        Statement("SELECT amount, unit FROM event_reminder WHERE eventId = ?", eventId)

    // ── Reads: a whole set's children, one JOIN each ─────────────────────────

    /** Every recurring event's weekdays in one read, each row naming its parent. */
    fun selectRecurringWeekdays(): Statement =
        Statement("SELECT w.eventId AS eventId, w.weekday AS weekday FROM event_weekday w JOIN event e ON e.id = w.eventId WHERE e.recurring = 1")

    fun selectRecurringExceptions(): Statement =
        Statement("SELECT x.eventId AS eventId, x.date AS date FROM event_exception x JOIN event e ON e.id = x.eventId WHERE e.recurring = 1")

    fun selectRecurringReminders(): Statement =
        Statement("SELECT r.eventId AS eventId, r.amount AS amount, r.unit AS unit FROM event_reminder r JOIN event e ON e.id = r.eventId WHERE e.recurring = 1")

    /** A one-off has no weekdays and no exceptions by construction, so reminders are its only child
     *  read — and for a whole set it is the parent read's own predicate, carried onto the JOIN. */
    fun selectRemindersOverlapping(from: LocalDate, to: LocalDate): Statement =
        Statement(
            "SELECT r.eventId AS eventId, r.amount AS amount, r.unit AS unit FROM event_reminder r " +
                "JOIN event e ON e.id = r.eventId WHERE e.recurring = 0 AND e.startDate <= ? AND e.endDate >= ?",
            CalendarDates.format(to), CalendarDates.format(from),
        )

    fun selectRemindersStartingIn(fromExclusive: LocalDate, toInclusive: LocalDate): Statement =
        Statement(
            "SELECT r.eventId AS eventId, r.amount AS amount, r.unit AS unit FROM event_reminder r " +
                "JOIN event e ON e.id = r.eventId WHERE e.recurring = 0 AND e.startDate > ? AND e.startDate <= ?",
            CalendarDates.format(fromExclusive), CalendarDates.format(toInclusive),
        )

    // ── The row's cells ──────────────────────────────────────────────────────

    /** `type … noteHeight` — the 17 columns both halves of the upsert write, in [COLUMNS] order.
     *  `interval` / `monthlyMode` / `endMode` are NOT NULL on every row, so a one-off carries the
     *  defaults rather than a NULL the reader would have to interpret. */
    private fun body(e: Event): List<Cell> {
        val r = e.recurrence
        return listOf(
            Cell.of(e.type.name),
            Cell.of(e.title),
            Cell.of(CalendarDates.format(e.startDate)),
            Cell.of(CalendarDates.format(e.endDate)),
            Cell.of(e.allDay),
            Cell.of(e.startMinute),
            Cell.of(e.endMinute),
            Cell.of(e.recurring),
            Cell.of(r?.freq?.name),
            Cell.of(r?.interval ?: 1),
            Cell.of((r?.monthlyMode ?: MonthlyMode.DAY_OF_MONTH).name),
            Cell.of((r?.endMode ?: EndMode.NEVER).name),
            Cell.of(r?.untilDate?.let { CalendarDates.format(it) }),
            Cell.of(r?.endCount),
            Cell.of(e.noteText),
            Cell.of(e.noteWidth),
            Cell.of(e.noteHeight),
        )
    }
}
