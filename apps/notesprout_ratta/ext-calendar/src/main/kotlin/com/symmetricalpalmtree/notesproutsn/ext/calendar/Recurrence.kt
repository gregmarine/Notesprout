package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate

/**
 * The recurrence engine (arc 24 / Z1) — pure, `LocalDate` throughout, a fresh port of og's
 * semantics with one deliberate divergence (below). It answers three questions and enumerates:
 * does this event cover this day, which occurrence covers it, and when is the next one.
 *
 * **Every occurrence keeps the anchor's span length**: an occurrence beginning on `O` covers
 * `[O, O + spanDays]` inclusive. That is what makes a three-day vacation repeat as a three-day
 * vacation rather than as three separate days.
 *
 * **Exceptions are passed in**, not read off the rule ([Event.exceptions] is its own table here).
 * An excluded start contributes no coverage on any day of its span — deleting "this occurrence" of
 * a multi-day event removes the whole occurrence, not the day that was tapped.
 *
 * **Cost.** NEVER / UNTIL rules test only the candidate starts in `[day − spanDays, day]`, so a
 * birthday anchored decades ago is O(spanDays) — effectively O(1). COUNT rules enumerate the first
 * N starts (N is user-small, capped at 999) and test coverage directly, because "the 5th
 * occurrence" cannot be decided from a date alone.
 *
 * ## The one divergence from og: weeks start on Sunday
 *
 * og counts WEEKLY interval weeks from **ISO Mondays**. This calendar's weeks start on **Sunday**
 * everywhere else — the grid's columns, the week page's date, [CalendarDates.weekStart] — and a
 * repeat rule that disagreed with the grid it is drawn on would be a bug the person could see. So
 * the week index here is counted from Sundays, and a listed ISO weekday `d` sits `d % 7` days after
 * its week's Sunday. Recorded in the plan and pinned by `RecurrenceTest` so no reviewer "fixes" it.
 */
object Recurrence {

    // ── The three questions ──────────────────────────────────────────────────

    /** Whether the series covers [day]. */
    fun occursOn(
        rule: RecurrenceRule,
        anchorStart: LocalDate,
        anchorEnd: LocalDate,
        exceptions: Set<LocalDate>,
        day: LocalDate,
    ): Boolean = occurrenceStartCovering(rule, anchorStart, anchorEnd, exceptions, day) != null

    /**
     * The **start** of the occurrence covering [day], or null when none does. For a multi-day span
     * that start can precede [day] — which is the point: an exception, an override and a truncation
     * are all anchored to the occurrence, never to the day that was tapped.
     */
    fun occurrenceStartCovering(
        rule: RecurrenceRule,
        anchorStart: LocalDate,
        anchorEnd: LocalDate,
        exceptions: Set<LocalDate>,
        day: LocalDate,
    ): LocalDate? {
        val span = spanOf(anchorStart, anchorEnd)
        val target = day.toEpochDay()

        if (rule.endMode == EndMode.COUNT) {
            for (start in generateStarts(rule, anchorStart, (rule.endCount ?: 0).coerceAtLeast(0))) {
                if (start in exceptions) continue
                val s = start.toEpochDay()
                if (target in s..(s + span)) return start
            }
            return null
        }

        // NEVER / UNTIL: only a start in [day − span, day] can reach the day, and none before the anchor.
        var o = maxOf(target - span, anchorStart.toEpochDay())
        while (o <= target) {
            val od = LocalDate.ofEpochDay(o)
            if (isValidStart(rule, anchorStart, od) && withinUntil(rule, od) && od !in exceptions) return od
            o++
        }
        return null
    }

    /**
     * The first occurrence start **strictly after** [afterDay] and no later than
     * `afterDay + maxAheadDays`, skipping excluded starts; null when none falls in that window.
     * The bound is the caller's largest reminder lead, which is what keeps the forward scan short.
     */
    fun nextOccurrenceStart(
        rule: RecurrenceRule,
        anchorStart: LocalDate,
        anchorEnd: LocalDate,
        exceptions: Set<LocalDate>,
        afterDay: LocalDate,
        maxAheadDays: Int,
    ): LocalDate? {
        if (maxAheadDays <= 0) return null
        val ceiling = afterDay.toEpochDay() + maxAheadDays

        if (rule.endMode == EndMode.COUNT) {
            return generateStarts(rule, anchorStart, (rule.endCount ?: 0).coerceAtLeast(0))
                .filter { it !in exceptions && it.toEpochDay() > afterDay.toEpochDay() && it.toEpochDay() <= ceiling }
                .minOrNull()
        }

        var o = afterDay.toEpochDay() + 1
        while (o <= ceiling) {
            val od = LocalDate.ofEpochDay(o)
            if (isValidStart(rule, anchorStart, od) && withinUntil(rule, od) && od !in exceptions) return od
            o++
        }
        return null
    }

    /**
     * Up to [limit] occurrence starts in ascending order, occurrence #1 being the anchor itself
     * whenever the anchor is a valid start. For WEEKLY that is og's shape: the anchor's own week's
     * listed days from the anchor on, so an anchor mid-week does not lose the rest of its week.
     *
     * Every branch carries a guard on the loop count as well as on [limit]: a rule can skip months
     * (a 31st that most months do not have) or years (Feb 29), and a bounded walk that returns
     * fewer starts than asked is a better failure than one that does not return.
     */
    fun generateStarts(rule: RecurrenceRule, anchor: LocalDate, limit: Int): List<LocalDate> {
        if (limit <= 0) return emptyList()
        val n = rule.interval.coerceAtLeast(1)
        val out = ArrayList<LocalDate>(limit)

        when (rule.freq) {
            Freq.DAILY -> {
                var k = 0L
                while (out.size < limit) {
                    out += anchor.plusDays(k * n)
                    k++
                }
            }

            Freq.WEEKLY -> {
                // Sunday-first: a listed ISO weekday `d` sits `d % 7` days after the week's Sunday
                // (Sun = 7 → 0, Mon = 1 → 1 … Sat = 6 → 6), which also gives the ascending order.
                val days = effectiveWeekdays(rule, anchor).sortedBy { it % 7 }
                val weekStart = CalendarDates.weekStart(anchor).toEpochDay()
                var week = 0L
                var guard = 0
                while (out.size < limit && guard < limit * 8 + 400) {
                    val base = weekStart + week * n * 7
                    for (d in days) {
                        val date = LocalDate.ofEpochDay(base + (d % 7))
                        if (!date.isBefore(anchor)) out += date
                        if (out.size >= limit) break
                    }
                    week++
                    guard++
                }
            }

            Freq.MONTHLY -> {
                var k = 0L
                var guard = 0
                while (out.size < limit && guard < limit * 13 + 400) {
                    val month = anchor.withDayOfMonth(1).plusMonths(k * n)
                    val date = when (rule.monthlyMode) {
                        MonthlyMode.DAY_OF_MONTH ->
                            if (anchor.dayOfMonth <= month.lengthOfMonth()) month.withDayOfMonth(anchor.dayOfMonth) else null
                        MonthlyMode.ORDINAL_WEEKDAY -> ordinalDateIn(month, anchor)
                    }
                    if (date != null && !date.isBefore(anchor)) out += date
                    k++
                    guard++
                }
            }

            Freq.YEARLY -> {
                var k = 0
                var guard = 0
                while (out.size < limit && guard < limit * 5 + 400) {
                    val date = runCatching { LocalDate.of(anchor.year + k * n, anchor.monthValue, anchor.dayOfMonth) }.getOrNull()
                    if (date != null && !date.isBefore(anchor)) out += date
                    k++
                    guard++
                }
            }
        }
        return out
    }

    // ── The same three, taking an event ──────────────────────────────────────

    /** [occursOn] for [event]. A one-off answers its own span, so this is a total predicate. */
    fun occursOn(event: Event, day: LocalDate): Boolean {
        val rule = event.recurrence ?: return !day.isBefore(event.startDate) && !day.isAfter(event.endDate)
        return occursOn(rule, event.startDate, event.endDate, event.exceptions, day)
    }

    /** [occurrenceStartCovering] for [event]; a one-off's covering occurrence is its own start. */
    fun occurrenceStartCovering(event: Event, day: LocalDate): LocalDate? {
        val rule = event.recurrence
            ?: return if (occursOn(event, day)) event.startDate else null
        return occurrenceStartCovering(rule, event.startDate, event.endDate, event.exceptions, day)
    }

    /** [nextOccurrenceStart] for [event]; a one-off's next start is its own, when it is ahead. */
    fun nextOccurrenceStart(event: Event, afterDay: LocalDate, maxAheadDays: Int): LocalDate? {
        val rule = event.recurrence ?: return event.startDate.takeIf {
            maxAheadDays > 0 && it.isAfter(afterDay) && it.toEpochDay() <= afterDay.toEpochDay() + maxAheadDays
        }
        return nextOccurrenceStart(rule, event.startDate, event.endDate, event.exceptions, afterDay, maxAheadDays)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun spanOf(anchorStart: LocalDate, anchorEnd: LocalDate): Long =
        (anchorEnd.toEpochDay() - anchorStart.toEpochDay()).coerceAtLeast(0L)

    private fun withinUntil(rule: RecurrenceRule, start: LocalDate): Boolean = when (rule.endMode) {
        EndMode.NEVER -> true
        // Inclusive: "until Jan 1" includes an occurrence starting on Jan 1.
        EndMode.UNTIL -> rule.untilDate?.let { !start.isAfter(it) } ?: true
        EndMode.COUNT -> true   // decided by the enumeration, never by this path
    }

    /** Whether [o] is a valid occurrence start for [rule] anchored at [a]. */
    private fun isValidStart(rule: RecurrenceRule, a: LocalDate, o: LocalDate): Boolean {
        if (o.isBefore(a)) return false
        val n = rule.interval.coerceAtLeast(1)
        return when (rule.freq) {
            Freq.DAILY -> (o.toEpochDay() - a.toEpochDay()) % n == 0L

            Freq.WEEKLY -> {
                if (o.dayOfWeek.value !in effectiveWeekdays(rule, a)) return false
                // The divergence, at its site: Sunday weeks, not ISO Monday weeks.
                val weeks = (CalendarDates.weekStart(o).toEpochDay() - CalendarDates.weekStart(a).toEpochDay()) / 7
                weeks % n == 0L
            }

            Freq.MONTHLY -> {
                val months = (o.year - a.year) * 12 + (o.monthValue - a.monthValue)
                if (months < 0 || months % n != 0) return false
                when (rule.monthlyMode) {
                    MonthlyMode.DAY_OF_MONTH -> o.dayOfMonth == a.dayOfMonth
                    MonthlyMode.ORDINAL_WEEKDAY -> o.dayOfWeek == a.dayOfWeek && ordinalMatches(a, o)
                }
            }

            Freq.YEARLY -> {
                val years = o.year - a.year
                years >= 0 && years % n == 0 && o.monthValue == a.monthValue && o.dayOfMonth == a.dayOfMonth
            }
        }
    }

    /** The rule's weekdays, or the anchor's own weekday when it lists none. */
    private fun effectiveWeekdays(rule: RecurrenceRule, anchor: LocalDate): Set<Int> =
        if (rule.weekdays.isEmpty()) setOf(anchor.dayOfWeek.value) else rule.weekdays.mapTo(LinkedHashSet()) { it.coerceIn(1, 7) }

    /** Whether [o] sits in the same ordinal slot of its month as the anchor — with the **5th slot
     *  meaning "last"**, because most months have no fifth Tuesday and "the last Tuesday" is what a
     *  person choosing the 5th one meant. */
    private fun ordinalMatches(a: LocalDate, o: LocalDate): Boolean {
        val ordinal = (a.dayOfMonth - 1) / 7 + 1
        return if (ordinal >= 5) o.dayOfMonth + 7 > o.lengthOfMonth() else (o.dayOfMonth - 1) / 7 + 1 == ordinal
    }

    /** The date in [month] matching [anchor]'s ordinal weekday, or null when that month has none
     *  (a 4th Friday exists everywhere; a 5th does not). */
    private fun ordinalDateIn(month: LocalDate, anchor: LocalDate): LocalDate? {
        val ordinal = (anchor.dayOfMonth - 1) / 7 + 1
        val first = month.withDayOfMonth(1)
        val offset = (anchor.dayOfWeek.value - first.dayOfWeek.value + 7) % 7
        val firstMatch = 1 + offset
        if (ordinal >= 5) {
            var day = firstMatch
            while (day + 7 <= month.lengthOfMonth()) day += 7
            return month.withDayOfMonth(day)
        }
        val day = firstMatch + (ordinal - 1) * 7
        return if (day <= month.lengthOfMonth()) month.withDayOfMonth(day) else null
    }
}
