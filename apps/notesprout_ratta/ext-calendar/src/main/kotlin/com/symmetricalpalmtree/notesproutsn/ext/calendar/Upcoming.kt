package com.symmetricalpalmtree.notesproutsn.ext.calendar

import java.time.LocalDate

/**
 * The **Upcoming** look-ahead (arc 24 / Z1) — pure, og's `upcomingForDay` rule re-derived.
 *
 * A reminder here is a paper-like heads-up, **not a notification**: an event carrying a reminder of
 * lead `L` surfaces on every day `D` where `O − L ≤ D < O`, `O` being the start of its next
 * occurrence. So the section answers "what is coming" without anything ever ringing, which is the
 * whole reason this app has reminders at all.
 *
 * Three consequences worth stating, because each is a question someone will ask of a blank section:
 *
 * - **an event with no reminders never appears here.** Nothing is late and nothing is missed — the
 *   day's own list is where an event with no lead time lives;
 * - **an occurrence today is not upcoming**, it is today. `daysUntil` is always ≥ 1;
 * - **one row per event**, its soonest qualifying occurrence — a daily standup with a two-day lead
 *   is one line, not two.
 *
 * The caller reads the rows; this function only decides. `oneOffs` are the store's non-recurring
 * rows starting in `(day, day + `[MAX_LOOKAHEAD_DAYS]`]` (re-checked here, because a pure function
 * that trusts its caller's `WHERE` clause is a pure function with a hidden precondition), and
 * `recurring` is the whole recurring set with its children.
 */
object Upcoming {

    /** How far ahead the look-ahead ever probes. A lead beyond a year is meaningless for a
     *  paper-like heads-up and would only lengthen the recurring scan. */
    const val MAX_LOOKAHEAD_DAYS = 366

    fun forDay(day: LocalDate, oneOffs: List<Event>, recurring: List<Event>): List<UpcomingEvent> {
        val out = ArrayList<UpcomingEvent>()
        for (e in oneOffs) {
            if (!e.startDate.isAfter(day)) continue   // in progress or past — not upcoming
            addIfWithinLead(out, e, e.startDate, day)
        }
        for (e in recurring) {
            if (e.recurrence == null) continue
            val maxLead = e.reminders.maxOfOrNull { it.leadDays } ?: continue
            val start = Recurrence.nextOccurrenceStart(e, day, minOf(maxLead, MAX_LOOKAHEAD_DAYS)) ?: continue
            addIfWithinLead(out, e, start, day)
        }
        return out.sortedWith(EventOrder.UPCOMING)
    }

    /** Take [event] iff some reminder's lead reaches [day] from [occurrenceStart]. */
    private fun addIfWithinLead(out: MutableList<UpcomingEvent>, event: Event, occurrenceStart: LocalDate, day: LocalDate) {
        val daysUntil = (occurrenceStart.toEpochDay() - day.toEpochDay()).toInt()
        if (daysUntil <= 0) return
        if (event.reminders.none { it.leadDays >= daysUntil }) return
        out += UpcomingEvent(event, occurrenceStart, daysUntil)
    }
}
