package com.notesprout.android.data.tasks

import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.EventRecurrence
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.events.MonthlyMode
import com.notesprout.android.data.events.RecurrenceRule
import com.notesprout.android.data.events.RecurrenceSummary
import com.notesprout.android.data.index.TaskEntity
import java.time.LocalDate

/**
 * Bridges a task's **columnar** recurrence fields to the proven engine in
 * [EventRecurrence], and answers the one question a task series ever asks: *when is the next one due?*
 *
 * The [RecurrenceRule] built here is an in-memory value carrier only. It is handed to the engine and
 * to [RecurrenceSummary] and then discarded — it is **never serialized**, so the task row stays free
 * of JSON.
 *
 * ### Why tasks need their own next-date rule
 *
 * Events ask "does this rule land on day D?". A task asks "the user just finished this one — when is
 * the following one due?", and the answer must be humane about lateness: a daily task due Monday and
 * completed Wednesday is next due **Thursday**, not Tuesday. So [nextDue] walks the series from its
 * original anchor and takes the first occurrence strictly after `max(due day, action day)`:
 *
 * | Series | Due | Resolved | Next due |
 * |---|---|---|---|
 * | Daily | Mon | Wed | Thu |
 * | Daily | Fri | Wed (early) | Sat — not Thu |
 * | Every 3 days from Jan 1 | Jan 4 | Jan 6 | Jan 7 (stays on the anchor's grid) |
 * | Monthly, day 15 | Jan 15 | Feb 3 | Feb 15 |
 * | Yearly, Jul 4 | Jul 4 | Jul 20 | Jul 4 next year |
 * | Weekly Mon/Wed/Fri | Wed | Thu | Fri |
 *
 * Anchoring on [TaskEntity.seriesAnchorDay] rather than the current row's due day is what keeps the
 * phase grid intact in the third row of that table.
 */
object TaskRecurrence {

    /** An interval ceiling. Guards the day-by-day scan below from a nonsense "every 100000 years". */
    private const val MAX_INTERVAL = 99

    fun isRecurring(task: TaskEntity): Boolean = freqOf(task.recurFreq) != null

    /**
     * The task's rule in the shape the events engine expects, or null when the task is one-time.
     * The weekday bitmask is unpacked to the ISO list the rule carries.
     */
    fun ruleOf(task: TaskEntity): RecurrenceRule? {
        val freq = freqOf(task.recurFreq) ?: return null
        return RecurrenceRule(
            freq = freq,
            interval = (task.recurInterval ?: 1).coerceIn(1, MAX_INTERVAL),
            weekdays = TaskWeekdays.unpack(task.recurWeekdays),
            monthlyMode = monthlyModeOf(task.recurMonthlyMode),
            endMode = endModeOf(task.recurEndMode),
            endEpochDay = task.recurEndEpochDay,
            endCount = task.recurEndCount,
        )
    }

    /** Human summary for a task row, e.g. "Every 2 weeks on Mon, Wed". Null when one-time. */
    fun summary(task: TaskEntity): String? = ruleOf(task)?.let { RecurrenceSummary.of(it) }

    /**
     * The due day for the successor generated when [task] is resolved (completed or skipped) on
     * [actionDay], or **null when the series ends here** — because a COUNT is exhausted, an UNTIL
     * date has passed, or the task simply is not recurring.
     */
    fun nextDue(task: TaskEntity, actionDay: Long): Long? {
        val rule = ruleOf(task) ?: return null
        val anchor = task.seriesAnchorDay ?: task.dueEpochDay ?: return null

        // COUNT counts ROWS, not calendar positions. Enforcing it by enumerating the first N valid
        // dates (as the events engine does) would end a late-completed series early: a daily task
        // "3 times" started Jan 1 but finished on Jan 5 would find no enumerated start after Jan 5
        // and stop after one occurrence. The series index is the authority instead.
        val rowLimit = if (rule.endMode == EndMode.COUNT) rule.endCount else null
        if (rowLimit != null && (task.seriesIndex ?: 0) + 1 >= rowLimit) return null

        // …so the date walk itself must ignore COUNT. UNTIL is left in place: the engine's own
        // end-date check is exactly the termination we want.
        val walkRule =
            if (rule.endMode == EndMode.COUNT) rule.copy(endMode = EndMode.NEVER, endCount = null)
            else rule

        val after = maxOf(task.dueEpochDay ?: anchor, actionDay)
        return EventRecurrence.nextOccurrenceStart(
            rule = walkRule,
            anchorStart = anchor,
            anchorEnd = anchor,
            afterDay = after,
            maxAheadDays = lookaheadDays(walkRule, anchor),
        )
    }

    /**
     * How far the engine's day-by-day scan may need to reach to find the next valid occurrence.
     * Generous by design — the scan is cheap, and a bound that is too tight silently ends a series.
     */
    private fun lookaheadDays(rule: RecurrenceRule, anchorDay: Long): Int {
        val n = rule.interval.coerceIn(1, MAX_INTERVAL)
        return when (rule.freq) {
            Freq.DAILY -> n + 1
            Freq.WEEKLY -> 7 * n + 7
            // A day-of-month rule skips months too short to hold it, so 31 × n is not enough
            // headroom: Jan 31 → Mar 31 is a 59-day gap.
            Freq.MONTHLY -> 31 * n + 92
            Freq.YEARLY -> {
                val a = LocalDate.ofEpochDay(anchorDay)
                // Feb 29 only lands on leap years, and a skipped century (2100) stretches the gap
                // between two of them to eight years.
                val leapDay = a.monthValue == 2 && a.dayOfMonth == 29
                if (leapDay) 366 * 8 * n + 400 else 366 * n + 400
            }
        }
    }

    // ── Column ↔ enum parsing (unknown / legacy values read as the safe default) ──

    fun freqOf(name: String?): Freq? = Freq.entries.firstOrNull { it.name == name }

    private fun monthlyModeOf(name: String?): MonthlyMode =
        MonthlyMode.entries.firstOrNull { it.name == name } ?: MonthlyMode.DAY_OF_MONTH

    private fun endModeOf(name: String?): EndMode =
        EndMode.entries.firstOrNull { it.name == name } ?: EndMode.NEVER
}
