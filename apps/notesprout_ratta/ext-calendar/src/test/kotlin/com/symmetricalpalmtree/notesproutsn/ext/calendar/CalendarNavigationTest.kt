package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The anchor rule: the three views are three magnifications of one day, and this is what proves the
 * toggles walk down to it and back up from it. September 2026 throughout — Sep 1 is a Tuesday, so
 * the week of Sep 2 opens on Sunday Aug 30.
 */
class CalendarNavigationTest {

    private val today = LocalDate.of(2026, 9, 2)     // a Wednesday
    private val morning = 9
    private val afternoon = 13

    /** A navigation opened on the Month page holding [today], as a first run does. */
    private fun opened(hour: Int = morning): CalendarNavigation =
        CalendarNavigation().also { it.shown(it.opening(null, today, hour)) }

    private fun CalendarNavigation.go(m: CalendarNavigation.Move?): CalendarNavigation.Move {
        val move = requireNotNull(m)
        shown(move)
        return move
    }

    private fun assertTarget(kind: Int, date: String, half: Int, actual: CalendarTarget) {
        assertEquals(CalendarTarget(kind, date, half), actual)
    }

    @Test
    fun firstRunOpensOnTodaysMonth_anchoredOnToday() {
        val nav = opened()
        assertTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0, nav.target)
        assertEquals(today, nav.anchor)
    }

    @Test
    fun aBookmarkOfAnyKindIsHonoured() {
        val nav = CalendarNavigation()
        val bookmark = CalendarTarget(CalendarTarget.KIND_DAY, "2026-12-24", CalendarTarget.HALF_PM)
        nav.shown(nav.opening(bookmark, today, morning))
        assertEquals(bookmark, nav.target)
        assertEquals(LocalDate.of(2026, 12, 24), nav.anchor)
        assertEquals(CalendarTarget.HALF_PM, nav.anchorHalf)
    }

    @Test
    fun fromThisMonthTheWeekToggleLandsOnThisWeek() {
        val nav = opened()
        val move = nav.go(nav.toggled(CalendarTarget.KIND_WEEK))
        assertTarget(CalendarTarget.KIND_WEEK, "2026-08-30", 0, move.target)
        assertEquals(today, nav.anchor)
    }

    @Test
    fun fromThisMonthTheDayToggleLandsOnTodayAtTheClocksHalf() {
        val early = opened(morning)
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_AM,
            early.go(early.toggled(CalendarTarget.KIND_DAY)).target)

        val late = opened(afternoon)
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_PM,
            late.go(late.toggled(CalendarTarget.KIND_DAY)).target)
    }

    @Test
    fun steppingToAMonthWithoutTodayAnchorsOnItsFirstDay() {
        val nav = opened(afternoon)
        val stepped = nav.go(nav.stepped(forward = true, today, afternoon))
        assertTarget(CalendarTarget.KIND_MONTH, "2026-10-01", 0, stepped.target)
        assertEquals(LocalDate.of(2026, 10, 1), nav.anchor)

        // The week of Oct 1 (a Thursday) opens on Sunday Sep 27 — the anchor is Oct 1, not today.
        assertTarget(CalendarTarget.KIND_WEEK, "2026-09-27", 0,
            nav.go(nav.toggled(CalendarTarget.KIND_WEEK)).target)
        // And down to the day: AM, because the anchor is not today whatever the clock says.
        assertTarget(CalendarTarget.KIND_DAY, "2026-10-01", CalendarTarget.HALF_AM,
            nav.go(nav.toggled(CalendarTarget.KIND_DAY)).target)
    }

    @Test
    fun steppingBackIntoTheMonthHoldingTodayAnchorsOnTodayAgain() {
        val nav = opened()
        nav.go(nav.stepped(forward = true, today, morning))     // October
        nav.go(nav.stepped(forward = false, today, morning))    // back to September
        assertEquals(today, nav.anchor)
    }

    @Test
    fun aTogglePreservesTheAnchorsHalf() {
        val nav = CalendarNavigation()
        val day = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-03", CalendarTarget.HALF_PM)
        nav.shown(nav.opening(day, today, morning))
        assertEquals(CalendarTarget.HALF_PM, nav.anchorHalf)

        // Up to the week the day sits in — the anchor does not move, so neither does its half.
        assertTarget(CalendarTarget.KIND_WEEK, "2026-08-30", 0,
            nav.go(nav.toggled(CalendarTarget.KIND_WEEK)).target)
        assertEquals(LocalDate.of(2026, 9, 3), nav.anchor)
        // …and back down to exactly the page we left, PM included.
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-03", CalendarTarget.HALF_PM,
            nav.go(nav.toggled(CalendarTarget.KIND_DAY)).target)
    }

    @Test
    fun togglingToTheShowingViewDoesNothing() {
        val nav = opened()
        assertNull(nav.toggled(CalendarTarget.KIND_MONTH))
        val week = nav.go(nav.toggled(CalendarTarget.KIND_WEEK))
        assertEquals(CalendarTarget.KIND_WEEK, week.target.kind)
        assertNull(nav.toggled(CalendarTarget.KIND_WEEK))
    }

    @Test
    fun todayIsTodayInTheShowingView_andTheClockPicksTheHalf() {
        val nav = CalendarNavigation()
        nav.shown(nav.opening(CalendarTarget(CalendarTarget.KIND_DAY, "2026-01-05", 0), today, afternoon))
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_PM,
            nav.go(nav.todayMove(today, afternoon)).target)
        assertEquals(today, nav.anchor)

        val onMonth = opened()
        onMonth.go(onMonth.stepped(forward = true, today, morning))     // October
        assertTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0,
            onMonth.go(onMonth.todayMove(today, morning)).target)
    }

    @Test
    fun aDoubleTapOpensThatDaysMorning_andMovesTheAnchor() {
        val nav = opened(afternoon)
        val move = nav.go(nav.dayAt(LocalDate.of(2026, 10, 10)))
        assertTarget(CalendarTarget.KIND_DAY, "2026-10-10", CalendarTarget.HALF_AM, move.target)
        assertEquals(LocalDate.of(2026, 10, 10), nav.anchor)
        // Back up to the week, and the week is October 10th's — the anchor really moved.
        assertTarget(CalendarTarget.KIND_WEEK, "2026-10-04", 0,
            nav.go(nav.toggled(CalendarTarget.KIND_WEEK)).target)
    }

    @Test
    fun aDoubleTapOnTheDayPageIsNothing() {
        val nav = opened()
        nav.go(nav.toggled(CalendarTarget.KIND_DAY))
        assertNull(nav.dayAt(LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun aPickMovesTheAnchorWithinTheShowingView() {
        val nav = opened()
        assertTarget(CalendarTarget.KIND_MONTH, "2026-12-01", 0,
            nav.go(nav.picked(LocalDate.of(2026, 12, 25), today, afternoon)).target)
        assertEquals(LocalDate.of(2026, 12, 25), nav.anchor)
        assertTarget(CalendarTarget.KIND_DAY, "2026-12-25", CalendarTarget.HALF_AM,
            nav.go(nav.toggled(CalendarTarget.KIND_DAY)).target)
    }

    @Test
    fun steppingADayWalksAmToPmToTheNextMorning() {
        val nav = opened()
        nav.go(nav.toggled(CalendarTarget.KIND_DAY))                    // Sep 2 AM (hour 9)
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_PM,
            nav.go(nav.stepped(forward = true, today, morning)).target)
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-03", CalendarTarget.HALF_AM,
            nav.go(nav.stepped(forward = true, today, morning)).target)
        assertEquals(LocalDate.of(2026, 9, 3), nav.anchor)
        assertEquals(CalendarTarget.HALF_AM, nav.anchorHalf)
        assertTarget(CalendarTarget.KIND_DAY, "2026-09-02", CalendarTarget.HALF_PM,
            nav.go(nav.stepped(forward = false, today, morning)).target)
    }
}
