package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate

/**
 * What the day picker shows, with no views in it (arc 23 / Y2) — pure and JVM-tested, so the grid
 * that a finger taps is proved by a test rather than by squinting at a device.
 *
 * The two grids are og's: a **day grid** of Sunday-first weeks with leading blanks and no trailing
 * empty week, and a **month grid** of twelve values in four rows of three. Titles come from
 * [CalendarDates]' hand lists (the month) and from the int itself (the year) — never a formatter,
 * arc 5's rule, and the reason this is the extension's own picker rather than og's.
 */
object DayPickerModel {

    /** Sun-first initials for the day grid's header row. Two of them are "T" and two "S"; that is
     *  what a calendar header looks like, and the column position is what disambiguates. */
    val WEEKDAY_LETTERS: List<String> = listOf("S", "M", "T", "W", "T", "F", "S")

    /**
     * The weeks of the month beginning [monthStart], Sunday-first: each row is seven slots, a slot
     * is a day or null. The first row carries the leading blanks, the last the trailing ones — and
     * there is **never a trailing empty week**, so a 28-day February that begins on a Sunday is
     * exactly four rows tall and the dialog does not grow a blank one.
     */
    fun dayRows(monthStart: LocalDate): List<List<LocalDate?>> {
        val first = CalendarDates.monthStart(monthStart)
        val lead = first.dayOfWeek.value % 7            // ISO Mon=1..Sun=7 → Sunday-first blanks
        val days = first.lengthOfMonth()
        val rows = ArrayList<List<LocalDate?>>(6)
        var day = 1
        for (week in 0 until 6) {
            val row = ArrayList<LocalDate?>(7)
            for (col in 0 until 7) {
                if (week * 7 + col < lead || day > days) {
                    row += null
                } else {
                    row += first.withDayOfMonth(day)
                    day++
                }
            }
            rows += row
            if (day > days) break
        }
        return rows
    }

    /** The month chooser: 1..12 in four rows of three, reading across. */
    fun monthGrid(): List<List<Int>> = (0 until 4).map { r -> (1..3).map { c -> r * 3 + c } }

    /** "September 2026" — the day grid's title, and the tap target that flips to the months. */
    fun monthTitle(monthStart: LocalDate): String = CalendarDates.monthTitle(CalendarDates.monthStart(monthStart))

    /** "2026" — the month chooser's title. */
    fun yearTitle(year: Int): String = year.toString()
}
