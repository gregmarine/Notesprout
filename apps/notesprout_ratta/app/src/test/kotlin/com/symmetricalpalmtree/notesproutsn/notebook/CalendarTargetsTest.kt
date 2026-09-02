package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Arc 23 / Y3 — the Send-to-Calendar sheet's four targets. Every row is built through
 * `CalendarTarget.of`, so construction itself is the `requireValid` check: a row whose date was not
 * normalized for its kind could not be built at all.
 */
class CalendarTargetsTest {

    private fun triples(day: LocalDate) =
        CalendarTargets.rows(day).map { Triple(it.target.kind, it.target.date, it.target.half) }

    @Test
    fun `the four rows are the wizard's order`() {
        assertEquals(
            listOf(
                CalendarTargets.Choice.TODAY_AM,
                CalendarTargets.Choice.TODAY_PM,
                CalendarTargets.Choice.THIS_WEEK,
                CalendarTargets.Choice.THIS_MONTH,
            ),
            CalendarTargets.rows(LocalDate.of(2026, 9, 2)).map { it.choice },
        )
    }

    /** A fixed Wednesday: the day twice, the week's Sunday, the month's first. */
    @Test
    fun `from a Wednesday the four targets are the day, the day, its week and its month`() {
        assertEquals(
            listOf(
                Triple(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_AM),
                Triple(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_PM),
                Triple(CalendarTarget.KIND_WEEK, "2026-08-30", CalendarTarget.HALF_AM),
                Triple(CalendarTarget.KIND_MONTH, "2026-09-01", CalendarTarget.HALF_AM),
            ),
            triples(LocalDate.of(2026, 9, 2)),
        )
    }

    /** Weeks start on Sunday, so a Sunday's week target is that very day. */
    @Test
    fun `on a Sunday the week target is the day itself`() {
        val rows = CalendarTargets.rows(LocalDate.of(2026, 8, 30))
        assertEquals("2026-08-30", rows[2].target.date)
        assertEquals(CalendarTarget.KIND_WEEK, rows[2].target.kind)
    }

    /** …and a Saturday is the far end of the same week: six days back. */
    @Test
    fun `on a Saturday the week target is six days back`() {
        val rows = CalendarTargets.rows(LocalDate.of(2026, 9, 5))
        assertEquals("2026-08-30", rows[2].target.date)
    }

    @Test
    fun `on a month's first the month target is that day`() {
        val rows = CalendarTargets.rows(LocalDate.of(2026, 9, 1))
        assertEquals("2026-09-01", rows[3].target.date)
        assertEquals(CalendarTarget.KIND_MONTH, rows[3].target.kind)
    }

    /** A year-end day: the week walks back across the boundary, the month stays in December. */
    @Test
    fun `a year-end day crosses into the week that started in the same month`() {
        assertEquals(
            listOf(
                Triple(CalendarTarget.KIND_DAY, "2026-12-31", CalendarTarget.HALF_AM),
                Triple(CalendarTarget.KIND_DAY, "2026-12-31", CalendarTarget.HALF_PM),
                Triple(CalendarTarget.KIND_WEEK, "2026-12-27", CalendarTarget.HALF_AM),
                Triple(CalendarTarget.KIND_MONTH, "2026-12-01", CalendarTarget.HALF_AM),
            ),
            triples(LocalDate.of(2026, 12, 31)),
        )
    }

    /** Construction is the validation — every row would have thrown if its date were not normalized
     *  for its kind, so re-running the contract's own check must pass for all four. */
    @Test
    fun `every target satisfies requireValid`() {
        for (day in listOf(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), LocalDate.of(2028, 2, 29),
            LocalDate.of(2026, 9, 2), LocalDate.of(2026, 12, 31),
        )) {
            for (row in CalendarTargets.rows(day)) {
                CalendarTarget.requireValid(row.target.kind, row.target.date, row.target.half)
            }
        }
    }
}
