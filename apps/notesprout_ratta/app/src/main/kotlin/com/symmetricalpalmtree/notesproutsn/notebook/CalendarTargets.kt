package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import java.time.LocalDate

/**
 * The four choices the Send-to-Calendar sheet offers, as [CalendarTarget]s (arc 23 / Y3) — pure, so
 * the one thing the host must not get wrong is JVM-tested.
 *
 * The host never *computes* a period. It knows a day — "today" — and hands it to
 * [CalendarTarget.of], which normalizes through `CalendarDates.periodDate`: the week's Sunday, the
 * month's first day, the day itself. That is the whole point of routing through the contract rather
 * than doing the arithmetic here: the week rule (Sunday-start) is the extension's and the
 * contract's, and a host that guessed it would mint a second row for the same week the first time
 * the two disagreed.
 *
 * "Today" is passed in rather than read from the clock, so a test can put the sheet on any day.
 */
object CalendarTargets {

    /** The four rows, identified — the screen maps each to its own label string. */
    enum class Choice { TODAY_AM, TODAY_PM, THIS_WEEK, THIS_MONTH }

    class Row(val choice: Choice, val target: CalendarTarget)

    /** The four rows, in the wizard's order: Today AM · Today PM · This week · This month. */
    fun rows(today: LocalDate): List<Row> = listOf(
        Row(Choice.TODAY_AM, CalendarTarget.of(CalendarTarget.KIND_DAY, today, CalendarTarget.HALF_AM)),
        Row(Choice.TODAY_PM, CalendarTarget.of(CalendarTarget.KIND_DAY, today, CalendarTarget.HALF_PM)),
        Row(Choice.THIS_WEEK, CalendarTarget.of(CalendarTarget.KIND_WEEK, today)),
        Row(Choice.THIS_MONTH, CalendarTarget.of(CalendarTarget.KIND_MONTH, today)),
    )
}
