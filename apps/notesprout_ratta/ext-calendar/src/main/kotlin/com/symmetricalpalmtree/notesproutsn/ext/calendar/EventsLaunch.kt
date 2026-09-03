package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import java.time.LocalDate

/**
 * Which day the events screen opens on when the calendar's Events button is tapped (arc 24 / Z2)
 * — one line of arithmetic, pinned by a test because it is a **locked wizard decision** and not an
 * implementation detail: the **first day of the period showing**, not the anchor.
 *
 * So a Month page opens the 1st, a Week page opens its Sunday, and a Day page opens that day. The
 * anchor is deliberately ignored: from a month you are looking at the month, and "events" from
 * there means "this month's, starting at the top". Stepping to the day you meant is one tap of the
 * bottom pager, and the picker is one more.
 */
object EventsLaunch {

    /** The day to open, given the showing page's [kind] and the day that page is dated by. The
     *  period date is already normalized by [CalendarTarget]; normalizing again costs nothing and
     *  means this answers correctly whatever it is handed. */
    fun launchDay(kind: Int, periodDate: LocalDate): LocalDate =
        when (kind) {
            CalendarTarget.KIND_MONTH, CalendarTarget.KIND_WEEK, CalendarTarget.KIND_DAY ->
                CalendarDates.periodDate(kind, periodDate)
            // An unknown kind cannot come from the calendar screen; answering the day handed over
            // is still a calendar day, which is better than a screen that will not open.
            else -> periodDate
        }
}
