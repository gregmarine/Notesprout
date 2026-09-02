package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import java.time.LocalDate

/**
 * Where the organizer is looking, and what every navigation does to it (arc 23 / Y2) — pure,
 * JVM-tested, so the screen is a thin caller: it asks for a [Move], shows it, and tells this it
 * landed.
 *
 * **The anchor is the point of this class.** A calendar page is a *period*, but a person is looking
 * at a *day*, and the three views are three magnifications of that one day. So the state carries an
 * [anchor] day alongside the showing page: toggling Month → Week → Day walks down to the same day
 * rather than to three unrelated first-of-periods.
 *
 * The anchor moves in exactly four ways:
 *
 * - **Open or step** — it is derived from the page ([arriving]): a Day page anchors on its day; a
 *   Month or Week page that **contains today** anchors on *today*, so the first toggle out of this
 *   month lands on this week and out of this week on today; any other period anchors on its own
 *   first day.
 * - **Pick, Today, double-tap** — it is the day the user named.
 * - **Toggle** — it does not move at all. A toggle is a change of magnification, not of place; this
 *   is what lets Week → Day come back to the day (and the half) you left.
 *
 * [anchorHalf] travels with the anchor and is only ever the half *that anchor day* is looking at: a
 * Day page sets it to its own half, and any move to a **new** anchor day resets it — to the clock's
 * half when that day is today (a Day page opened from this morning opens on the morning), otherwise
 * to AM. A toggle to Day then simply uses it, which is why "Week → Day" after "Day Sep 3 PM" is
 * Sep 3 PM again.
 *
 * Nothing here touches the store, the paper or the clock: [today] and the hour are passed in, so a
 * test can put the calendar on any day of any year.
 */
class CalendarNavigation {

    /** A navigation about to happen: the page to show, and where the organizer will be looking
     *  once it is up. Computed without mutating anything, so a show that fails changes nothing —
     *  the screen calls [shown] only after the page is really on the paper. */
    class Move(val target: CalendarTarget, val anchor: LocalDate, val anchorHalf: Int)

    private var current: CalendarTarget? = null

    /** The page showing. Only valid once [shown] has been called. */
    val target: CalendarTarget get() = requireNotNull(current) { "nothing shown yet" }

    /** The showing page's kind — [CalendarTarget.KIND_MONTH] / `_WEEK` / `_DAY`. */
    val kind: Int get() = target.kind

    /** The day the organizer is looking at — see the class doc. */
    var anchor: LocalDate = LocalDate.MIN
        private set

    /** The half [anchor] is looking at; meaningful only for that day. */
    var anchorHalf: Int = CalendarTarget.HALF_AM
        private set

    /** Record that [m] is on the paper. The one place the state changes. */
    fun shown(m: Move) {
        current = m.target
        anchor = m.anchor
        anchorHalf = m.anchorHalf
    }

    // ── The moves ────────────────────────────────────────────────────────────

    /** The page to open on: the store's bookmark of any kind, or — first run, or a bookmark that
     *  did not parse — today's Month. */
    fun opening(bookmark: CalendarTarget?, today: LocalDate, nowHour: Int): Move =
        arriving(bookmark ?: CalendarTarget.of(CalendarTarget.KIND_MONTH, today), today, nowHour)

    /** One period forward or back in the showing view — [CalendarDates.step] is the one rule, for
     *  the pager's two buttons and the finger swipe alike. */
    fun stepped(forward: Boolean, today: LocalDate, nowHour: Int): Move {
        val t = target
        val (date, half) = CalendarDates.step(t.kind, t.localDate, t.half, forward)
        return arriving(CalendarTarget(t.kind, CalendarDates.format(date), half), today, nowHour)
    }

    /** Today, in the showing view. On a Day page the half comes from the clock — before noon is
     *  the morning ledger — because "Today" means now, not midnight. */
    fun todayMove(today: LocalDate, nowHour: Int): Move {
        val half = halfFor(today, today, nowHour)
        return Move(CalendarTarget.of(kind, today, if (kind == CalendarTarget.KIND_DAY) half else CalendarTarget.HALF_AM), today, half)
    }

    /** The day picker's answer: the showing view, at the picked day. The anchor moves with it. */
    fun picked(day: LocalDate, today: LocalDate, nowHour: Int): Move {
        val half = halfFor(day, today, nowHour)
        return Move(CalendarTarget.of(kind, day, if (kind == CalendarTarget.KIND_DAY) half else CalendarTarget.HALF_AM), day, half)
    }

    /**
     * A double-tap on a Month or Week cell: **that day's Day page, AM** (the wizard's call — a
     * double-tap always opens the morning, whatever the clock says). Null on a Day page, where a
     * double-tap is nothing at all.
     */
    fun dayAt(day: LocalDate): Move? {
        if (kind == CalendarTarget.KIND_DAY) return null
        return Move(CalendarTarget.of(CalendarTarget.KIND_DAY, day, CalendarTarget.HALF_AM), day, CalendarTarget.HALF_AM)
    }

    /** Toggle to [toKind] — the same anchor at a different magnification. Null when [toKind] is
     *  already showing: a toggle to the showing view does nothing, rather than re-showing it. */
    fun toggled(toKind: Int): Move? {
        if (toKind == kind) return null
        val half = if (toKind == CalendarTarget.KIND_DAY) anchorHalf else CalendarTarget.HALF_AM
        return Move(CalendarTarget.of(toKind, anchor, half), anchor, anchorHalf)
    }

    // ── The anchor rule ──────────────────────────────────────────────────────

    /** Arriving at [t] by opening or stepping: the anchor is derived from the page itself. */
    private fun arriving(t: CalendarTarget, today: LocalDate, nowHour: Int): Move {
        val day = when (t.kind) {
            CalendarTarget.KIND_DAY -> t.localDate
            CalendarTarget.KIND_MONTH -> if (CalendarDates.monthStart(today) == t.localDate) today else t.localDate
            else -> if (CalendarDates.weekStart(today) == t.localDate) today else t.localDate
        }
        val half = if (t.kind == CalendarTarget.KIND_DAY) t.half else halfFor(day, today, nowHour)
        return Move(t, day, half)
    }

    /** The half a *new* anchor day is looking at: the clock's when it is today, else the morning. */
    private fun halfFor(day: LocalDate, today: LocalDate, nowHour: Int): Int =
        if (day == today) clockHalf(nowHour) else CalendarTarget.HALF_AM

    companion object {
        /** Before noon is the morning ledger. The one place the clock becomes a half. */
        fun clockHalf(nowHour: Int): Int =
            if (nowHour < 12) CalendarTarget.HALF_AM else CalendarTarget.HALF_PM
    }
}
