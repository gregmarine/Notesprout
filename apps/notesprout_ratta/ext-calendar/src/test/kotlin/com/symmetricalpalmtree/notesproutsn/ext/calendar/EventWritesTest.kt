package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What a Save, a Delete and the three recurring scopes come to, as exact statement lists. */
class EventWritesTest {

    private val now = 99L
    private val anchor = LocalDate.of(2026, 9, 2)
    private val viewed = LocalDate.of(2026, 9, 16)

    /** Every 7 days from Sep 2, with one occurrence already removed. */
    private val series = testEvent(
        id = "e1",
        title = "Standup",
        start = anchor,
        recurrence = RecurrenceRule(Freq.DAILY, interval = 7),
        exceptions = setOf(LocalDate.of(2026, 9, 23)),
        reminders = listOf(Reminder(1, ReminderUnit.DAYS)),
    )

    private fun notePuts(): List<Statement> = listOf(
        Statement("INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)"),
        Statement("DELETE FROM note_stroke WHERE id = ?"),
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    /** The two long statements read as their heads; every other one is short enough to pin whole. */
    private fun shapeOf(s: Statement): String = when {
        s.sql.startsWith("INSERT OR IGNORE INTO event (") -> "INSERT OR IGNORE INTO event ("
        s.sql.startsWith("UPDATE event SET type = ") -> "UPDATE event SET type = "
        else -> s.sql
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    @Test
    fun aSaveIsTheRowThenItsChildrenThenTheNote() {
        val e = testEvent(
            id = "e1",
            start = anchor,
            recurrence = RecurrenceRule(Freq.WEEKLY, weekdays = setOf(3, 1)),
            exceptions = setOf(LocalDate.of(2026, 9, 9)),
            reminders = listOf(Reminder(1, ReminderUnit.DAYS), Reminder(2, ReminderUnit.WEEKS)),
        )
        val batch = EventWrites.save(e, now, notePuts())
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO event (",
                "UPDATE event SET type = ",
                "DELETE FROM event_weekday WHERE eventId = ?",
                "INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?, ?)",
                "INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?, ?)",
                "DELETE FROM event_exception WHERE eventId = ?",
                "INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)",
                "DELETE FROM event_reminder WHERE eventId = ?",
                "INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?, ?, ?)",
                "INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?, ?, ?)",
                "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "DELETE FROM note_stroke WHERE id = ?",
            ),
            batch.map(::shapeOf),
        )
        // Weekdays go in sorted, so a retried batch writes the same rows in the same order.
        assertEquals(listOf(1L, 3L), batch.filter { it.sql.contains("event_weekday (") }.map { (it.args[1] as Cell.Integer).value })
        assertEquals(2, batch.count { it.sql.contains("note_stroke") })
    }

    @Test
    fun aOneOffStillClearsEveryChildSet() {
        val batch = EventWrites.save(testEvent(id = "one"), now)
        assertEquals(
            listOf(
                "DELETE FROM event_weekday WHERE eventId = ?",
                "DELETE FROM event_exception WHERE eventId = ?",
                "DELETE FROM event_reminder WHERE eventId = ?",
            ),
            batch.map { it.sql }.filter { it.startsWith("DELETE") },
        )
        assertEquals(5, batch.size)
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Test
    fun deletingANonRecurringEventOrTheWholeSeriesIsOneStatement() {
        assertEquals(
            listOf("DELETE FROM event WHERE id = ?"),
            EventWrites.deleteWithScope(Scope.THIS, testEvent(id = "one"), viewed, now)!!.map { it.sql },
        )
        assertEquals(
            listOf("DELETE FROM event WHERE id = ?"),
            EventWrites.deleteWithScope(Scope.ALL, series, viewed, now)!!.map { it.sql },
        )
    }

    @Test
    fun deletingThisOccurrenceIsAnExceptionAndAStamp() {
        val batch = EventWrites.deleteWithScope(Scope.THIS, series, viewed, now)!!
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)",
                "UPDATE event SET updatedAt = ? WHERE id = ?",
            ),
            batch.map { it.sql },
        )
        assertEquals("2026-09-16", text(batch[0].args[1]))
    }

    @Test
    fun deletingThisAndFollowingTruncatesToTheDayBefore() {
        val batch = EventWrites.deleteWithScope(Scope.FOLLOWING, series, viewed, now)!!
        assertEquals(listOf("UPDATE event SET endMode = ?, untilDate = ?, endCount = NULL, updatedAt = ? WHERE id = ?"), batch.map { it.sql })
        assertEquals("2026-09-15", text(batch.single().args[1]))
    }

    @Test
    fun aSplitAtTheFirstOccurrenceCollapsesToTheWholeDelete() {
        val batch = EventWrites.deleteWithScope(Scope.FOLLOWING, series, anchor, now)!!
        assertEquals(listOf("DELETE FROM event WHERE id = ?"), batch.map { it.sql })
    }

    @Test
    fun aDayThatMapsToNoOccurrenceIsNothingToDo() {
        val notAnOccurrence = anchor.plusDays(3)
        assertNull(EventWrites.deleteWithScope(Scope.THIS, series, notAnOccurrence, now))
        assertNull(EventWrites.deleteWithScope(Scope.FOLLOWING, series, notAnOccurrence, now))
        assertNull(EventWrites.editWithScope(Scope.THIS, series, series, notAnOccurrence, "new", now))
        assertNull(EventWrites.editLandsUnder(Scope.THIS, series, series, notAnOccurrence, "new"))
    }

    // ── Edit ─────────────────────────────────────────────────────────────────

    @Test
    fun editingThisOccurrenceExceptionsTheSeriesAndWritesAOneOffOverride() {
        val edited = series.copy(title = "Standup (moved)", startDate = viewed.plusDays(1), endDate = viewed.plusDays(1))
        val batch = EventWrites.editWithScope(Scope.THIS, series, edited, viewed, "new", now, notePuts())!!
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)",
                "UPDATE event SET updatedAt = ? WHERE id = ?",
            ),
            batch.take(2).map { it.sql },
        )
        assertEquals("2026-09-16", text(batch[0].args[1]))

        val insert = batch[2]
        val columns = EventSql.COLUMNS.split(", ")
        assertTrue(insert.sql.startsWith("INSERT OR IGNORE INTO event ("))
        assertEquals("new", text(insert.args[columns.indexOf("id")]))
        assertEquals("Standup (moved)", text(insert.args[columns.indexOf("title")]))
        assertEquals("2026-09-17", text(insert.args[columns.indexOf("startDate")]))
        assertEquals(Cell.Integer(0), insert.args[columns.indexOf("recurring")])
        assertEquals(Cell.Null, insert.args[columns.indexOf("freq")])
        assertEquals(Cell.Integer(now), insert.args[columns.indexOf("createdAt")])
        // The override carries the reminders and the note; it inherits no exception of its own.
        assertEquals(1, batch.count { it.sql.startsWith("INSERT OR IGNORE INTO event_reminder") })
        assertTrue(batch.none { it.sql.startsWith("INSERT OR IGNORE INTO event_exception") && text(it.args[0]) == "new" })
        assertEquals(2, batch.count { it.sql.contains("note_stroke") })
        assertEquals("new", EventWrites.editLandsUnder(Scope.THIS, series, edited, viewed, "new"))
    }

    @Test
    fun editingThisAndFollowingTruncatesAndStartsAFreshSeries() {
        val edited = series.copy(title = "Standup v2", startDate = viewed, endDate = viewed)
        val batch = EventWrites.editWithScope(Scope.FOLLOWING, series, edited, viewed, "new", now, notePuts())!!
        assertEquals("UPDATE event SET endMode = ?, untilDate = ?, endCount = NULL, updatedAt = ? WHERE id = ?", batch[0].sql)
        assertEquals("2026-09-15", text(batch[0].args[1]))

        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("new", text(batch[1].args[columns.indexOf("id")]))
        assertEquals("2026-09-16", text(batch[1].args[columns.indexOf("startDate")]))
        assertEquals(Cell.Integer(1), batch[1].args[columns.indexOf("recurring")])
        // No inherited exceptions: they belonged to the tail that was just truncated away.
        assertTrue(batch.none { it.sql.startsWith("INSERT OR IGNORE INTO event_exception") })
        assertEquals("new", EventWrites.editLandsUnder(Scope.FOLLOWING, series, edited, viewed, "new"))
    }

    @Test
    fun aFollowingSplitAtTheFirstOccurrenceCollapsesToTheWholeSeries() {
        val edited = series.copy(title = "Renamed")
        val batch = EventWrites.editWithScope(Scope.FOLLOWING, series, edited, anchor, "new", now)!!
        assertTrue(batch.none { it.sql.startsWith("UPDATE event SET endMode") })
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("e1", text(batch[0].args[columns.indexOf("id")]))
        assertEquals("e1", EventWrites.editLandsUnder(Scope.FOLLOWING, series, edited, anchor, "new"))
    }

    @Test
    fun editingAllKeepsTheAnchorWhenTheDatesComeBackAsThePrefill() {
        // The editor pre-fills from the TAPPED occurrence; saving that back must not re-anchor.
        val prefilled = series.copy(title = "Renamed", startDate = viewed, endDate = viewed)
        val batch = EventWrites.editWithScope(Scope.ALL, series, prefilled, viewed, "new", now)!!
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("e1", text(batch[0].args[columns.indexOf("id")]))
        assertEquals("2026-09-02", text(batch[0].args[columns.indexOf("startDate")]))
        // The exceptions carry forward — an occurrence already removed stays removed.
        val exceptions = batch.filter { it.sql.startsWith("INSERT OR IGNORE INTO event_exception") }
        assertEquals(listOf("2026-09-23"), exceptions.map { text(it.args[1]) })
        assertEquals("e1", EventWrites.editLandsUnder(Scope.ALL, series, prefilled, viewed, "new"))
    }

    @Test
    fun aDeliberatelyChangedDateReAnchorsTheSeries() {
        val moved = series.copy(startDate = viewed.plusDays(1), endDate = viewed.plusDays(1))
        val batch = EventWrites.editWithScope(Scope.ALL, series, moved, viewed, "new", now)!!
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("2026-09-17", text(batch[0].args[columns.indexOf("startDate")]))
    }

    @Test
    fun aNewEventIsAlwaysAPlainSeriesSave() {
        val fresh = testEvent(id = "fresh", title = "New thing")
        val batch = EventWrites.editWithScope(Scope.THIS, null, fresh, viewed, "new", now)!!
        assertEquals(EventWrites.save(fresh, now).map { it.sql }, batch.map { it.sql })
        assertEquals("fresh", EventWrites.editLandsUnder(Scope.THIS, null, fresh, viewed, "new"))
    }

    @Test
    fun editingANonRecurringEventIsAPlainSave() {
        val one = testEvent(id = "one", title = "Dentist")
        val batch = EventWrites.editWithScope(Scope.THIS, one, one.copy(title = "Dentist 2"), viewed, "new", now)!!
        assertEquals("one", text(batch[0].args[EventSql.COLUMNS.split(", ").indexOf("id")]))
        assertEquals("one", EventWrites.editLandsUnder(Scope.THIS, one, one.copy(title = "Dentist 2"), viewed, "new"))
    }
}
