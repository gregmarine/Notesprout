package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which half-hour row a Day page's marks land in (arc 24 / Z4), and what the row then says. */
class DayRowsTest {

    private fun allDay(title: String) = DayMark(title, allDay = true, startMinute = null, glyph = Glyph.DOT)
    private fun timeless(title: String) = DayMark(title, allDay = false, startMinute = null, glyph = Glyph.DOT)
    private fun at(minute: Int, title: String = "t") =
        DayMark(title, allDay = false, startMinute = minute, glyph = Glyph.CLOCK)

    private val am = CalendarTarget.HALF_AM
    private val pm = CalendarTarget.HALF_PM

    // ── slotOf ───────────────────────────────────────────────────────────────

    @Test
    fun theMorningHalfHoldsMidnightToJustBeforeNoon() {
        assertEquals(0, DayRows.slotOf(am, 0))
        assertEquals(1, DayRows.slotOf(am, 30))
        assertEquals(1, DayRows.slotOf(am, 59))
        assertEquals(18, DayRows.slotOf(am, 9 * 60))
        assertEquals(23, DayRows.slotOf(am, 719))
        assertNull(DayRows.slotOf(am, 720))
        assertNull(DayRows.slotOf(am, 1439))
    }

    @Test
    fun theAfternoonHalfMirrorsIt() {
        assertEquals(0, DayRows.slotOf(pm, 720))
        assertEquals(1, DayRows.slotOf(pm, 750))
        assertEquals(23, DayRows.slotOf(pm, 1439))
        assertNull(DayRows.slotOf(pm, 719))
        assertNull(DayRows.slotOf(pm, 0))
    }

    // ── bucket ───────────────────────────────────────────────────────────────

    @Test
    fun allDayMarksTakeRowsFromTheTop_onBothHalves() {
        val marks = listOf(allDay("Vacation"), allDay("Birthday"))
        for (half in listOf(am, pm)) {
            val rows = DayRows.bucket(marks, half)
            assertEquals(listOf(0, 1), rows.keys.toList())
            assertEquals(listOf("Vacation"), rows.getValue(0).map { it.title })
            assertEquals(listOf("Birthday"), rows.getValue(1).map { it.title })
        }
    }

    @Test
    fun aTimedMarkSitsAtItsOwnRow_andOnlyOnItsOwnHalf() {
        val marks = listOf(at(9 * 60, "Standup"), at(14 * 60 + 30, "Dentist"))
        assertEquals(mapOf(18 to listOf("Standup")), DayRows.bucket(marks, am).mapValues { it.value.map { m -> m.title } })
        assertEquals(mapOf(5 to listOf("Dentist")), DayRows.bucket(marks, pm).mapValues { it.value.map { m -> m.title } })
    }

    @Test
    fun anAllDayAndAMidnightTimedShareRowZero_andTheRowCountsThem() {
        val marks = listOf(allDay("Vacation"), at(0, "Alarm"))
        val rows = DayRows.bucket(marks, am)
        assertEquals(listOf(0), rows.keys.toList())
        // The order inside the row is the order given (EventOrder.DAY: all-day first).
        assertEquals(listOf("Vacation", "Alarm"), rows.getValue(0).map { it.title })
        assertEquals("2 events", DayRows.label(rows.getValue(0)))
    }

    @Test
    fun aTimelessMarkTakesAnAllDayRow() {
        val rows = DayRows.bucket(listOf(allDay("Vacation"), timeless("Sometime")), am)
        assertEquals(listOf(0, 1), rows.keys.toList())
        assertEquals(listOf("Sometime"), rows.getValue(1).map { it.title })
    }

    @Test
    fun moreAllDayMarksThanRowsDropsTheRest() {
        val marks = List(30) { allDay("e$it") }
        val rows = DayRows.bucket(marks, am)
        assertEquals(CalendarGeometry.DAY_ROWS, rows.size)
        assertEquals(0, rows.keys.first())
        assertEquals(23, rows.keys.last())
        assertEquals(listOf("e23"), rows.getValue(23).map { it.title })
        // A timed mark past the flood still lands on its own row — it was never queued.
        val withTimed = DayRows.bucket(marks + at(9 * 60, "Standup"), am)
        assertTrue(withTimed.getValue(18).map { it.title }.contains("Standup"))
    }

    @Test
    fun keysAreAscendingWhateverOrderTheMarksArriveIn() {
        val rows = DayRows.bucket(listOf(at(11 * 60, "late"), at(60, "early"), allDay("top")), am)
        assertEquals(listOf(0, 2, 22), rows.keys.toList())
    }

    @Test
    fun nothingBucketsToNothing() {
        assertTrue(DayRows.bucket(emptyList(), am).isEmpty())
    }

    // ── label + width ────────────────────────────────────────────────────────

    @Test
    fun oneEntryIsItsTitle_andMoreIsACount() {
        assertEquals("Dentist", DayRows.label(listOf(at(540, "Dentist"))))
        assertEquals("2 events", DayRows.label(listOf(at(540, "Dentist"), at(545, "Call"))))
        assertEquals("3 events", DayRows.label(List(3) { at(540, "x") }))
    }

    @Test
    fun theLabelMayTakeHalfTheRowRightOfTheGutter() {
        assertEquals(600, DayRows.labelMaxWidth(gutterRight = 204, right = 1404))
        assertEquals(0, DayRows.labelMaxWidth(gutterRight = 1404, right = 1404))
    }
}
