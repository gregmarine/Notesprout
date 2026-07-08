package com.notesprout.android.data.events

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Builds a short human-readable description of a [RecurrenceRule] for list rows. */
object RecurrenceSummary {

    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun of(rule: RecurrenceRule): String {
        val base = base(rule)
        val end = endClause(rule)
        return if (end == null) base else "$base · $end"
    }

    private fun base(rule: RecurrenceRule): String {
        val n = rule.interval.coerceAtLeast(1)
        return when (rule.freq) {
            Freq.DAILY -> if (n == 1) "Every day" else "Every $n days"
            Freq.WEEKLY -> {
                val unit = if (n == 1) "week" else "$n weeks"
                val days = weekdayList(rule.weekdays)
                if (days.isEmpty()) "Every $unit" else "Every $unit on $days"
            }
            Freq.MONTHLY -> {
                val unit = if (n == 1) "month" else "$n months"
                "Every $unit"
            }
            Freq.YEARLY -> if (n == 1) "Every year" else "Every $n years"
        }
    }

    private fun endClause(rule: RecurrenceRule): String? = when (rule.endMode) {
        EndMode.NEVER -> null
        EndMode.UNTIL -> rule.endEpochDay?.let { "until " + LocalDate.ofEpochDay(it).format(dateFmt) }
        EndMode.COUNT -> rule.endCount?.let { "for $it times" }
    }

    private fun weekdayList(iso: List<Int>): String =
        iso.sorted().joinToString(", ") { d ->
            java.time.DayOfWeek.of(d.coerceIn(1, 7))
                .getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
}
