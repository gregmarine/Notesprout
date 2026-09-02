package com.symmetricalpalmtree.notesproutsn.extension

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The calendar's date arithmetic (arc 23 / Y1) — pure, `java.time` (minSdk 29), shared by the host
 * (the target sheet), the extension (every page, every title) and the tests, so **nobody guesses the
 * week rule**: weeks start on **Sunday**, never the device locale (the wizard's call).
 *
 * Names come from the hand lists below by index, never from a formatter — arc 5's rule, because CLDR
 * data drifts between devices and a page title is chrome, not locale data. Dates cross the seam and
 * are stored only as ISO `yyyy-MM-dd` ([format] / [parse] — `LocalDate.toString()`'s form, which is
 * `Locale.ROOT`-safe; og's Eastern-Arabic-digit lesson).
 *
 * Kinds are [CalendarTarget]'s: a month page is dated by its first day, a week page by its Sunday, a
 * day page by the day; a day owns two halves (AM / PM). [step] walks a page forward or back — a
 * month by a month, a week by seven days, a day half by half (AM → PM → the next day's AM, and back).
 */
object CalendarDates {

    /** Sun–Sat, the column order of every grid. */
    val DAY_NAMES: List<String> = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** January–December, by `monthValue - 1`. */
    val MONTH_NAMES: List<String> = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Jan–Dec, by `monthValue - 1` — the week and day titles. */
    val MONTH_NAMES_SHORT: List<String> = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** The two halves of a day page, by [CalendarTarget.HALF_AM] / [CalendarTarget.HALF_PM]. */
    val HALF_NAMES: List<String> = listOf("AM", "PM")

    // ── Normalization ────────────────────────────────────────────────────────

    fun monthStart(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    /** The Sunday on or before [day]. `DayOfWeek.value` is Mon=1..Sun=7, so `% 7` makes Sunday 0. */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value % 7).toLong())

    /** The day a page of [kind] holding [day] is dated by. */
    fun periodDate(kind: Int, day: LocalDate): LocalDate = when (kind) {
        CalendarTarget.KIND_MONTH -> monthStart(day)
        CalendarTarget.KIND_WEEK -> weekStart(day)
        CalendarTarget.KIND_DAY -> day
        else -> throw IllegalArgumentException("unknown kind ($kind)")
    }

    /** Whether [day] is exactly the date a page of [kind] would be dated by. */
    fun isNormalized(kind: Int, day: LocalDate): Boolean = periodDate(kind, day) == day

    /** The Sunday that opens a month's 6×7 grid — the week of the 1st. */
    fun firstCell(monthStart: LocalDate): LocalDate = weekStart(monthStart)

    // ── Stepping ─────────────────────────────────────────────────────────────

    /**
     * The page after (or before) the one at ([date], [half]) — the result is normalized for [kind].
     * A month steps by a month from its first day (so Jan 31 can never appear — `plusMonths` clamps
     * and the input is the 1st); a week by seven days; a day AM → PM → the next day's AM, and the
     * mirror going back.
     */
    fun step(kind: Int, date: LocalDate, half: Int, forward: Boolean): Pair<LocalDate, Int> {
        val at = periodDate(kind, date)
        return when (kind) {
            CalendarTarget.KIND_MONTH -> (if (forward) at.plusMonths(1) else at.minusMonths(1)) to CalendarTarget.HALF_AM
            CalendarTarget.KIND_WEEK -> (if (forward) at.plusDays(7) else at.minusDays(7)) to CalendarTarget.HALF_AM
            CalendarTarget.KIND_DAY -> when {
                forward && half == CalendarTarget.HALF_AM -> at to CalendarTarget.HALF_PM
                forward -> at.plusDays(1) to CalendarTarget.HALF_AM
                half == CalendarTarget.HALF_PM -> at to CalendarTarget.HALF_AM
                else -> at.minusDays(1) to CalendarTarget.HALF_PM
            }
            else -> throw IllegalArgumentException("unknown kind ($kind)")
        }
    }

    // ── ISO text ─────────────────────────────────────────────────────────────

    /** `yyyy-MM-dd`, ASCII digits — the only form a date takes on the wire or in a row. */
    fun format(day: LocalDate): String = day.toString()

    /** Strictly `yyyy-MM-dd` (ten ASCII characters, a real calendar day) or null. */
    fun parse(text: String): LocalDate? {
        if (text.length != 10) return null
        for (i in text.indices) {
            val c = text[i]
            val ok = if (i == 4 || i == 7) c == '-' else c in '0'..'9'
            if (!ok) return null
        }
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    // ── Titles ───────────────────────────────────────────────────────────────

    /** "September 2026". */
    fun monthTitle(monthStart: LocalDate): String = "${MONTH_NAMES[monthStart.monthValue - 1]} ${monthStart.year}"

    /** "Aug 30 – Sep 5, 2026"; the year joins each side when the week straddles one
     *  ("Dec 28, 2025 – Jan 3, 2026"), and the month repeats only when it changes. */
    fun weekTitle(sunday: LocalDate): String {
        val saturday = sunday.plusDays(6)
        val from = "${MONTH_NAMES_SHORT[sunday.monthValue - 1]} ${sunday.dayOfMonth}"
        return if (sunday.year != saturday.year) {
            "$from, ${sunday.year} – ${MONTH_NAMES_SHORT[saturday.monthValue - 1]} ${saturday.dayOfMonth}, ${saturday.year}"
        } else if (sunday.month != saturday.month) {
            "$from – ${MONTH_NAMES_SHORT[saturday.monthValue - 1]} ${saturday.dayOfMonth}, ${saturday.year}"
        } else {
            "$from – ${saturday.dayOfMonth}, ${saturday.year}"
        }
    }

    /** "Tue, Sep 1, 2026 · AM". */
    fun dayTitle(day: LocalDate, half: Int): String =
        "${DAY_NAMES[day.dayOfWeek.value % 7]}, ${MONTH_NAMES_SHORT[day.monthValue - 1]} ${day.dayOfMonth}, ${day.year} · ${HALF_NAMES[half]}"
}
