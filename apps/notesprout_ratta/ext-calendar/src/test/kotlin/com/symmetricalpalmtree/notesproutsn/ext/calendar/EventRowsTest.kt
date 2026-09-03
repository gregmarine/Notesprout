package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A row comes back as the event that wrote it — and a row that cannot mean one is dropped, never thrown. */
class EventRowsTest {

    private val day = LocalDate.of(2026, 9, 1)
    private val columns = FakeEventStore.EVENT_COLUMNS

    /** The row `INSERT` would write, which is the only row this decoder ever has to read. */
    private fun rowFor(e: Event, now: Long = 77L): Row = Row(columns, EventSql.insertEvent(e, now).args)

    private fun with(e: Event, column: String, cell: Cell): Row {
        val cells = EventSql.insertEvent(e, 77L).args.toMutableList()
        cells[columns.indexOf(column)] = cell
        return Row(columns, cells)
    }

    @Test
    fun aGoodRowRoundTrips() {
        val e = testEvent(
            id = "e9",
            type = EventType.MEETING,
            title = "Standup",
            start = day,
            end = day.plusDays(2),
            allDay = false,
            startMinute = 540,
            endMinute = 570,
            recurrence = RecurrenceRule(
                freq = Freq.WEEKLY,
                interval = 2,
                weekdays = setOf(1, 3),
                endMode = EndMode.UNTIL,
                untilDate = day.plusMonths(3),
            ),
            noteText = "bring the notebook",
            noteWidth = 800f,
            noteHeight = 600f,
            createdAt = 5L,
        )
        val decoded = EventRows.decode(rowFor(e), setOf(1, 3), setOf(day.plusDays(14)), e.reminders)!!
        assertEquals(e.copy(exceptions = setOf(day.plusDays(14)), updatedAt = 77L), decoded)
    }

    @Test
    fun aOneOffCarriesTheDefaults_andIgnoresStrayChildRows() {
        val e = testEvent(id = "one", recurrence = null)
        val decoded = EventRows.decode(rowFor(e), setOf(1, 3), setOf(day), emptyList())!!
        assertNull(decoded.recurrence)
        assertTrue("a one-off has no exceptions, whatever rows were handed in", decoded.exceptions.isEmpty())
    }

    @Test
    fun anUnknownEnumNameDropsTheRow() {
        val e = testEvent(recurrence = RecurrenceRule(Freq.DAILY))
        assertNull(EventRows.decode(with(e, "type", Cell.Text("HOLIDAY")), emptySet(), emptySet(), emptyList()))
        assertNull(EventRows.decode(with(e, "freq", Cell.Text("FORTNIGHTLY")), emptySet(), emptySet(), emptyList()))
        assertNull(EventRows.decode(with(e, "monthlyMode", Cell.Text("NEAREST")), emptySet(), emptySet(), emptyList()))
        assertNull(EventRows.decode(with(e, "endMode", Cell.Text("SOMETIME")), emptySet(), emptySet(), emptyList()))
    }

    @Test
    fun aDateThatDoesNotParseDropsTheRow() {
        val e = testEvent(recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.UNTIL, untilDate = day))
        assertNull(EventRows.decode(with(e, "startDate", Cell.Text("yesterday")), emptySet(), emptySet(), emptyList()))
        assertNull(EventRows.decode(with(e, "endDate", Cell.Text("2026-13-01")), emptySet(), emptySet(), emptyList()))
        assertNull(EventRows.decode(with(e, "untilDate", Cell.Text("soon")), emptySet(), emptySet(), emptyList()))
    }

    @Test
    fun anEndBeforeTheStartDropsTheRow() {
        val e = testEvent(start = day, end = day)
        assertNull(EventRows.decode(with(e, "endDate", Cell.Text("2026-08-30")), emptySet(), emptySet(), emptyList()))
    }

    @Test
    fun theRecurringMirrorIsLoadBearing() {
        // `recurring = 1` with a NULL freq: invisible to the one-off read and useless to the
        // expansion read. So is the mirror image.
        val oneOff = testEvent(recurrence = null)
        assertNull(EventRows.decode(with(oneOff, "recurring", Cell.Integer(1)), emptySet(), emptySet(), emptyList()))
        val series = testEvent(recurrence = RecurrenceRule(Freq.DAILY))
        assertNull(EventRows.decode(with(series, "recurring", Cell.Integer(0)), emptySet(), emptySet(), emptyList()))
    }

    @Test
    fun aCellOfTheWrongStorageClassDropsTheRow() {
        val e = testEvent()
        assertNull(EventRows.decode(with(e, "title", Cell.Integer(4)), emptySet(), emptySet(), emptyList()))
        assertNull(EventRows.decode(with(e, "createdAt", Cell.Text("later")), emptySet(), emptySet(), emptyList()))
    }

    @Test
    fun theChildRowHelpers() {
        assertEquals(3, EventRows.weekday(Row(listOf("weekday"), listOf(Cell.Integer(3)))))
        assertNull(EventRows.weekday(Row(listOf("weekday"), listOf(Cell.Integer(9)))))
        assertNull(EventRows.weekday(Row(listOf("weekday"), listOf(Cell.Text("Wed")))))

        assertEquals(day, EventRows.exceptionDate(Row(listOf("date"), listOf(Cell.Text("2026-09-01")))))
        assertNull(EventRows.exceptionDate(Row(listOf("date"), listOf(Cell.Text("2026-9-1")))))

        val reminderColumns = listOf("amount", "unit")
        assertEquals(
            Reminder(2, ReminderUnit.WEEKS),
            EventRows.reminder(Row(reminderColumns, listOf(Cell.Integer(2), Cell.Text("WEEKS")))),
        )
        assertNull(EventRows.reminder(Row(reminderColumns, listOf(Cell.Integer(2), Cell.Text("MONTHS")))))
        assertNull(EventRows.reminder(Row(reminderColumns, listOf(Cell.Integer(0), Cell.Text("DAYS")))))
    }

    @Test
    fun anUnknownTypeNameIsNeverFoldedToOther() {
        assertNull(EventType.fromName("HOLIDAY"))
        assertEquals(EventType.BIRTHDAY, EventType.fromName("BIRTHDAY"))
    }
}
