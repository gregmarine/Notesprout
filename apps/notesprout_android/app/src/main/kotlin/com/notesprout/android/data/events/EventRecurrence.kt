package com.notesprout.android.data.events

import java.time.LocalDate

/**
 * The recurrence engine: decides whether a (possibly multi-day, possibly recurring) event covers a
 * given calendar day. Anchored to the event's own start date.
 *
 * A recurring event's span length ([anchorStart]…[anchorEnd]) is preserved for every occurrence: an
 * occurrence beginning on date O covers `[O, O + spanDays]` inclusive.
 *
 * Efficiency: for NEVER / UNTIL rules the check is O(spanDays) — only the few candidate starts that
 * could reach [day] are tested, so a birthday anchored decades ago is still O(1)-ish. COUNT rules
 * enumerate the first N occurrences (N is user-small) and test coverage directly.
 */
object EventRecurrence {

    /** True if the event occurs on (covers) [dayEpochDay]. */
    fun occursOn(rule: RecurrenceRule, anchorStart: Long, anchorEnd: Long, dayEpochDay: Long): Boolean =
        occurrenceStartCovering(rule, anchorStart, anchorEnd, dayEpochDay) != null

    /**
     * The START epoch-day of the occurrence that covers [dayEpochDay], or null if none does. Callers
     * use this to add an exception ("delete this occurrence") or anchor an override at the real
     * occurrence start — which, for a multi-day span, can precede the viewed day.
     */
    fun occurrenceStartCovering(
        rule: RecurrenceRule, anchorStart: Long, anchorEnd: Long, dayEpochDay: Long,
    ): Long? {
        val spanDays = (anchorEnd - anchorStart).coerceAtLeast(0L)
        val anchor = LocalDate.ofEpochDay(anchorStart)

        // Occurrence starts the user has removed ("this occurrence" delete / a date an override
        // replaced). An excluded start contributes no coverage on any day.
        val excluded = rule.exceptionDates

        if (rule.endMode == EndMode.COUNT) {
            val n = (rule.endCount ?: 0).coerceAtLeast(0)
            for (start in generateStarts(rule, anchor, n)) {
                val s = start.toEpochDay()
                if (s in excluded) continue
                if (dayEpochDay in s..(s + spanDays)) return s
            }
            return null
        }

        // NEVER / UNTIL: the only occurrence that can cover `day` starts in [day - spanDays, day].
        val lo = dayEpochDay - spanDays
        var o = maxOf(lo, anchorStart)
        while (o <= dayEpochDay) {
            val od = LocalDate.ofEpochDay(o)
            if (isValidStart(rule, anchor, od) && withinUntil(rule, o) && o !in excluded) return o
            o++
        }
        return null
    }

    /**
     * The START epoch-day of the first occurrence beginning **strictly after** [afterDay] and no later
     * than `afterDay + maxAheadDays`, or null if none falls in that window. Excluded ("this occurrence"
     * removed) starts are skipped. Used by the Events look-ahead to decide how soon an event is coming.
     *
     * Bounded by [maxAheadDays] (the event's largest reminder lead), so the NEVER/UNTIL scan is short;
     * COUNT rules read from the enumerated first-N starts.
     */
    fun nextOccurrenceStart(
        rule: RecurrenceRule, anchorStart: Long, anchorEnd: Long,
        afterDay: Long, maxAheadDays: Int,
    ): Long? {
        if (maxAheadDays <= 0) return null
        val anchor = LocalDate.ofEpochDay(anchorStart)
        val excluded = rule.exceptionDates
        val ceiling = afterDay + maxAheadDays

        if (rule.endMode == EndMode.COUNT) {
            val n = (rule.endCount ?: 0).coerceAtLeast(0)
            return generateStarts(rule, anchor, n)
                .map { it.toEpochDay() }
                .filter { it !in excluded && it > afterDay && it <= ceiling }
                .minOrNull()
        }

        var o = afterDay + 1
        while (o <= ceiling) {
            val od = LocalDate.ofEpochDay(o)
            if (isValidStart(rule, anchor, od) && withinUntil(rule, o) && o !in excluded) return o
            o++
        }
        return null
    }

    private fun withinUntil(rule: RecurrenceRule, startEpochDay: Long): Boolean = when (rule.endMode) {
        EndMode.NEVER -> true
        EndMode.UNTIL -> rule.endEpochDay?.let { startEpochDay <= it } ?: true
        EndMode.COUNT -> true // handled by enumeration, not this path
    }

    /** Whether [o] is a valid occurrence start for [rule] anchored at [a]. */
    private fun isValidStart(rule: RecurrenceRule, a: LocalDate, o: LocalDate): Boolean {
        if (o.isBefore(a)) return false
        val n = rule.interval.coerceAtLeast(1)
        return when (rule.freq) {
            Freq.DAILY -> (o.toEpochDay() - a.toEpochDay()) % n == 0L

            Freq.WEEKLY -> {
                val days = effectiveWeekdays(rule, a)
                if (o.dayOfWeek.value !in days) return false
                val weeks = (weekStart(o) - weekStart(a)) / 7
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

    /**
     * Ascending list of up to [limit] valid occurrence starts (occurrence #1 == the anchor). Used
     * for COUNT-terminated rules and for any future "list upcoming" needs.
     */
    fun generateStarts(rule: RecurrenceRule, anchor: LocalDate, limit: Int): List<LocalDate> {
        if (limit <= 0) return emptyList()
        val n = rule.interval.coerceAtLeast(1)
        val out = ArrayList<LocalDate>(limit)

        when (rule.freq) {
            Freq.DAILY -> {
                var k = 0
                while (out.size < limit) { out.add(anchor.plusDays(k.toLong() * n)); k++ }
            }

            Freq.WEEKLY -> {
                val days = effectiveWeekdays(rule, anchor).sorted()
                val wa = weekStart(anchor)
                var week = 0
                var guard = 0
                while (out.size < limit && guard < limit * 8 + 400) {
                    val base = wa + week.toLong() * n * 7
                    for (d in days) {
                        val date = LocalDate.ofEpochDay(base + (d - 1))
                        if (!date.isBefore(anchor)) out.add(date)
                        if (out.size >= limit) break
                    }
                    week++; guard++
                }
            }

            Freq.MONTHLY -> {
                var k = 0
                var guard = 0
                while (out.size < limit && guard < limit * 13 + 400) {
                    val ym = anchor.withDayOfMonth(1).plusMonths(k.toLong() * n)
                    val date = when (rule.monthlyMode) {
                        MonthlyMode.DAY_OF_MONTH ->
                            if (anchor.dayOfMonth <= ym.lengthOfMonth()) ym.withDayOfMonth(anchor.dayOfMonth) else null
                        MonthlyMode.ORDINAL_WEEKDAY -> ordinalDateIn(ym, anchor)
                    }
                    if (date != null && !date.isBefore(anchor)) out.add(date)
                    k++; guard++
                }
            }

            Freq.YEARLY -> {
                var k = 0
                var guard = 0
                while (out.size < limit && guard < limit * 5 + 400) {
                    val year = anchor.year + k * n
                    val date = runCatching { LocalDate.of(year, anchor.monthValue, anchor.dayOfMonth) }.getOrNull()
                    if (date != null && !date.isBefore(anchor)) out.add(date)
                    k++; guard++
                }
            }
        }
        return out
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private fun effectiveWeekdays(rule: RecurrenceRule, anchor: LocalDate): Set<Int> =
        if (rule.weekdays.isEmpty()) setOf(anchor.dayOfWeek.value)
        else rule.weekdays.map { it.coerceIn(1, 7) }.toSet()

    /** ISO week-start (Monday) as epochDay. */
    private fun weekStart(d: LocalDate): Long = d.toEpochDay() - (d.dayOfWeek.value - 1)

    /** True if [o] matches the anchor's ordinal-weekday-of-month (with "5th" treated as "last"). */
    private fun ordinalMatches(a: LocalDate, o: LocalDate): Boolean {
        val ordA = (a.dayOfMonth - 1) / 7 + 1
        return if (ordA >= 5) isLastWeekday(o) else (o.dayOfMonth - 1) / 7 + 1 == ordA
    }

    private fun isLastWeekday(o: LocalDate): Boolean = o.dayOfMonth + 7 > o.lengthOfMonth()

    /** The concrete date in month [ym] matching the anchor's ordinal weekday, or null if absent. */
    private fun ordinalDateIn(ym: LocalDate, anchor: LocalDate): LocalDate? {
        val ordA = (anchor.dayOfMonth - 1) / 7 + 1
        val targetDow = anchor.dayOfWeek.value
        val first = ym.withDayOfMonth(1)
        val firstDow = first.dayOfWeek.value
        val offset = (targetDow - firstDow + 7) % 7
        val firstMatchDay = 1 + offset // day-of-month of the 1st target weekday
        return if (ordA >= 5) {
            // last occurrence
            var day = firstMatchDay
            while (day + 7 <= ym.lengthOfMonth()) day += 7
            ym.withDayOfMonth(day)
        } else {
            val day = firstMatchDay + (ordA - 1) * 7
            if (day <= ym.lengthOfMonth()) ym.withDayOfMonth(day) else null
        }
    }
}
