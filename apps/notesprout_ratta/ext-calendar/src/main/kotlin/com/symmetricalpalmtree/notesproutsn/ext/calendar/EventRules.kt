package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * What an [Event] is allowed to be (arc 24 / Z1) — pure, pinned, and the same answer on every
 * screen. Two halves, deliberately separated:
 *
 * - [normalize] fixes everything that **can** be fixed without asking: caps, whitespace, a set the
 *   rule's own mode says is meaningless, an end before a start. It is total — it never refuses;
 * - [problem] names what normalization cannot fix, so the editor can say why Save did nothing.
 *
 * The store runs both on the way in, whatever the editor already did: a row that occurs on no day
 * is invisible to every query, which is a worse answer than a refusal.
 *
 * The title takes the **tag rule** for whitespace the codec would otherwise have to escape — tabs
 * and newlines are **dropped**, not turned into spaces. A pasted two-line title becomes one word
 * boundary short rather than one row tall, and a row's text stays a row's text.
 */
object EventRules {

    const val TITLE_MAX = 200
    const val NOTE_TEXT_MAX = 10_000
    const val REMINDERS_MAX = 3
    val INTERVAL_RANGE = 1..99
    val END_COUNT_RANGE = 1..999

    /** Minute of day. 1439 is 11:59 PM; there is no 24:00. */
    val MINUTE_RANGE = 0..1439

    /** What normalization cannot fix — checked on the **normalized** event. */
    enum class Problem { EMPTY_TITLE, UNTIL_BEFORE_START }

    /**
     * [e] with every cap applied and every meaningless field cleared. Idempotent: normalizing a
     * normalized event returns the same event, which is what lets the store re-run it on rows the
     * editor already checked.
     */
    fun normalize(e: Event): Event {
        val title = e.title.filterNot { it == '\t' || it == '\r' || it == '\n' }.trim().take(TITLE_MAX)
        val endDate = maxOf(e.startDate, e.endDate)
        val startMinute = if (e.allDay) null else e.startMinute?.coerceIn(MINUTE_RANGE)
        val coercedEnd = if (e.allDay) null else e.endMinute?.coerceIn(MINUTE_RANGE)
        // An end before the start is not a time — it is a half-finished edit. Clearing it says
        // "no end time", which is exactly what the editor's long-press on the End time does.
        val endMinute = if (coercedEnd != null && startMinute != null && coercedEnd < startMinute) null else coercedEnd
        return e.copy(
            title = title,
            endDate = endDate,
            startMinute = startMinute,
            endMinute = endMinute,
            recurrence = e.recurrence?.let(::normalize),
            reminders = normalize(e.reminders),
            noteText = e.noteText.take(NOTE_TEXT_MAX),
        )
    }

    /** Kept: a lead of at least one day. Deduped by `amount` + `unit`, ordered by the lead the
     *  window arithmetic uses (a week before a 7-day tie is decided by the unit, so the order is
     *  stable), and capped — three heads-up dates is already more than paper gives you. */
    fun normalize(reminders: List<Reminder>): List<Reminder> = reminders
        .filter { it.amount >= 1 }
        .distinctBy { it.amount to it.unit }
        .sortedWith(compareBy({ it.leadDays }, { it.unit.ordinal }))
        .take(REMINDERS_MAX)

    /** The rule with its interval in range and every field its mode does not use cleared — a
     *  weekday set on a monthly rule, an "until" date on a rule that ends never, a count on one
     *  that ends on a date. Clearing them here is what keeps the row and the engine agreeing. */
    fun normalize(rule: RecurrenceRule): RecurrenceRule = RecurrenceRule(
        freq = rule.freq,
        interval = rule.interval.coerceIn(INTERVAL_RANGE),
        weekdays = if (rule.freq == Freq.WEEKLY) rule.weekdays.filterTo(LinkedHashSet()) { it in 1..7 } else emptySet(),
        monthlyMode = rule.monthlyMode,
        endMode = rule.endMode,
        untilDate = if (rule.endMode == EndMode.UNTIL) rule.untilDate else null,
        endCount = if (rule.endMode == EndMode.COUNT) rule.endCount?.coerceIn(END_COUNT_RANGE) else null,
    )

    /**
     * What is wrong with [e] that normalization could not put right, or null.
     *
     * A blank title leaves a row nothing to read. An "ends on" date before the start leaves a
     * series with no occurrence at all — and an event that occurs on no day is invisible to every
     * query, so it would look exactly like a lost save.
     */
    fun problem(e: Event): Problem? {
        if (e.title.isBlank()) return Problem.EMPTY_TITLE
        val r = e.recurrence
        if (r != null && r.endMode == EndMode.UNTIL && r.untilDate != null && r.untilDate.isBefore(e.startDate)) {
            return Problem.UNTIL_BEFORE_START
        }
        return null
    }
}
