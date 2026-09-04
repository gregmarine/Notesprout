package com.symmetricalpalmtree.notesproutsn.ext.calendar

import java.util.TreeMap

/**
 * Which half-hour row of a Day page each of its marks lands in (arc 24 / Z4) — **pure**,
 * JVM-tested, no `android.graphics`.
 *
 * The Day page's geometry does **not** change for events (the locked decision): the rows are the
 * rows, the ink already on them does not move, and an event is a right-aligned label inside a row
 * it shares with whatever is written there. So the whole question is *which row*, and that is this:
 *
 * - an **all-day** mark has no time, so it takes a row from the **top** of the half — one per row,
 *   in the order given, on **both** halves (an all-day event is on the afternoon too);
 * - a **timeless** mark (`allDay == false` with no `startMinute` — the shape `EventRules` permits
 *   and `EventOrder.DAY` sorts last) has no row of its own either, so it takes an all-day row;
 * - a **timed** mark sits at the 30-minute row its start minute falls in, and only on the half
 *   whose window holds that minute;
 * - a row can hold both, and then it counts them ("2 events"): the page has one line per row and
 *   the person opens the events screen for the rest.
 *
 * Marks past the last row are **dropped** — there is nowhere to show them, and a label drawn under
 * the closing hairline would be a lie about which half-hour it belongs to.
 */
object DayRows {

    /** Minutes in one half of a day: twelve hours. */
    const val HALF_MINUTES = 12 * 60

    /** Minutes one row covers — the page is half-hour rows. */
    const val ROW_MINUTES = 30

    /** The last row index of a half. */
    val LAST_ROW: Int get() = CalendarGeometry.DAY_ROWS - 1

    /**
     * The row (0..[LAST_ROW]) minute-of-day [minute] falls in on [half], or **null** when the
     * minute is not in that half's window `[half·720, half·720 + 720)`.
     */
    fun slotOf(half: Int, minute: Int): Int? {
        val from = half * HALF_MINUTES
        if (minute < from || minute >= from + HALF_MINUTES) return null
        return (minute - from) / ROW_MINUTES
    }

    /**
     * [marks] bucketed into the rows of [half] — keys **ascending**, and only rows that hold
     * something.
     *
     * One pass, in the order given, so the entries inside a shared row are in the order they
     * arrived (`EventOrder.DAY`: all-day first): an all-day mark takes the next free row from the
     * top, a timed one is *added* to the row its minute names. A row is never re-ordered afterwards
     * — the label only ever reads the first entry's title, and only when it is the only one.
     */
    fun bucket(marks: List<DayMark>, half: Int): Map<Int, List<DayMark>> {
        val out = TreeMap<Int, MutableList<DayMark>>()
        var nextFromTop = 0
        for (m in marks) {
            val row = if (m.allDay || m.startMinute == null) {
                val r = nextFromTop++
                if (r > LAST_ROW) null else r
            } else {
                slotOf(half, m.startMinute)
            }
            if (row != null) out.getOrPut(row) { ArrayList(2) } += m
        }
        return out
    }

    /**
     * What one row says: its single entry's title, or "N events" when it holds more.
     *
     * The wording is [EventWording.dayRowLabel]'s — Z1 already pinned it, and one event described
     * two ways on two surfaces is the thing that file exists to prevent.
     */
    fun label(entries: List<DayMark>): String =
        EventWording.dayRowLabel(entries.size, entries.firstOrNull()?.title.orEmpty())

    /**
     * The widest a row's label may be: **half** the row's width to the right of the gutter.
     *
     * The other half is the person's — a row is a line to write on, and a label that could take the
     * whole of it would sit under whatever is already there.
     */
    fun labelMaxWidth(gutterRight: Int, right: Int): Int = (right - gutterRight) / 2
}
