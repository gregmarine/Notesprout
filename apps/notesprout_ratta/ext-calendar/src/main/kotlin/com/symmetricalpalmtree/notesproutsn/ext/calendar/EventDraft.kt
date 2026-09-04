package com.symmetricalpalmtree.notesproutsn.ext.calendar

import java.time.LocalDate

/**
 * The event editor's state, as a value (arc 24 / Z2) — every field the screen edits, flat, and
 * every field *rule* as a pure function that answers a new draft.
 *
 * **Why the editor does not edit an [Event].** An [Event]'s recurrence is a nullable object; the
 * editor's is six controls that stay on screen while the repeat is off, remembering what they said.
 * A draft is therefore flat where the event is nested, and the nesting is rebuilt once, in
 * [toEvent], through [EventRules.normalize] — the same normalization the store runs. So what the
 * screen shows and what the row will hold cannot drift: there is one conversion and it is tested.
 *
 * **Every rule here is a function, not an `if` in a click listener.** Choosing a start date after
 * the end date, turning all-day on, switching to weekly with no weekday chosen — each is a small
 * decision about what the *other* fields should now say, and each is the kind of decision that is
 * silently wrong on a device and obvious in a test.
 *
 * The carried-over fields ([id], [exceptions], [createdAt]) are not edited by any control at all;
 * they ride through so [toEvent] can rebuild a whole event without the screen holding the original
 * alongside. The note's three ([noteText], [noteWidth], [noteHeight]) rode through the same way
 * until Z3 gave them [withNoteText] and [withNoteSize] — which is why that phase added two
 * one-line copies rather than three fields.
 *
 * [repeatTouched] is bookkeeping, not content: it records whether the person has said anything
 * about the repeat yet, which is the only thing that decides whether choosing a *type* is allowed
 * to offer that type's usual recurrence. [changedFrom] ignores it for exactly that reason.
 */
data class EventDraft(
    val id: String,
    val type: EventType,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val allDay: Boolean,
    val startMinute: Int?,
    val endMinute: Int?,
    val freq: Freq?,
    val interval: Int,
    val weekdays: Set<Int>,
    val monthlyMode: MonthlyMode,
    val endMode: EndMode,
    val untilDate: LocalDate?,
    val endCount: Int,
    val reminders: List<Reminder>,
    val repeatTouched: Boolean,
    val exceptions: Set<LocalDate>,
    val noteText: String,
    val noteWidth: Float,
    val noteHeight: Float,
    val createdAt: Long,
) {

    // ── Out ──────────────────────────────────────────────────────────────────

    /** The draft as the event it would save as, normalized — caps applied, meaningless fields
     *  cleared, the flat repeat controls folded back into a [RecurrenceRule] (or dropped whole when
     *  the repeat is off). [now] becomes `updatedAt`. */
    fun toEvent(now: Long): Event = EventRules.normalize(
        Event(
            id = id,
            type = type,
            title = title,
            startDate = startDate,
            endDate = endDate,
            allDay = allDay,
            startMinute = startMinute,
            endMinute = endMinute,
            recurrence = freq?.let {
                RecurrenceRule(
                    freq = it,
                    interval = interval,
                    weekdays = weekdays,
                    monthlyMode = monthlyMode,
                    endMode = endMode,
                    untilDate = untilDate,
                    endCount = endCount,
                )
            },
            exceptions = exceptions,
            reminders = reminders,
            noteText = noteText,
            noteWidth = noteWidth,
            noteHeight = noteHeight,
            createdAt = createdAt,
            updatedAt = now,
        )
    )

    /** What Save would refuse, or null — asked of the **normalized** event, so the editor says the
     *  same thing the store would and never reports a problem normalization was about to fix. */
    fun problem(): EventRules.Problem? = EventRules.problem(toEvent(createdAt))

    /** Whether anything the person can see differs from [other]. [repeatTouched] is excluded: it
     *  records a gesture, not a value, and a draft that only differs there has not been edited. */
    fun changedFrom(other: EventDraft): Boolean = copy(repeatTouched = other.repeatTouched) != other

    // ── The field rules ──────────────────────────────────────────────────────

    /**
     * A type chosen.
     *
     * On a **new** event whose repeat has not been touched, the type brings its usual recurrence
     * with it — a birthday repeats yearly unless the person says otherwise ([EventType.defaultFreq]).
     * It is offered once: as soon as the repeat has been said anything about (or the event already
     * existed), changing the type changes only the type. Silently re-writing a repeat somebody
     * chose would be the worst kind of helpful.
     */
    fun withType(type: EventType, isNew: Boolean): EventDraft {
        val typed = copy(type = type)
        if (!isNew || repeatTouched) return typed
        // Not `withFreq`: the offer is not the person touching the repeat, so the flag stays down
        // and a second type change can still offer the second type's default.
        return typed.applyFreq(type.defaultFreq)
    }

    /** The title as typed; the caps and the whitespace rule are [EventRules]'s, at save. */
    fun withTitle(title: String): EventDraft = copy(title = title)

    /**
     * A start date chosen. **The end follows** — a span that inverts is not a span:
     * - an end that sat *on* the old start moves with it (a one-day event stays one day, wherever
     *   it is moved to, including backwards);
     * - any other end is pushed forward only if the new start passed it.
     *
     * An "ends on" date left before the new start is deliberately **not** fixed here: that is the
     * one thing Save refuses out loud ([EventRules.Problem.UNTIL_BEFORE_START]), and quietly
     * repairing it would hide a repeat the person thinks they set.
     */
    fun withStartDate(d: LocalDate): EventDraft =
        copy(startDate = d, endDate = if (endDate == startDate) d else maxOf(endDate, d))

    /** An end date chosen; never before the start (the picker can offer it, the span cannot hold it). */
    fun withEndDate(d: LocalDate): EventDraft = copy(endDate = maxOf(d, startDate))

    /**
     * All-day on or off. On clears both times — an all-day event has none, and [EventRules] would
     * clear them at save anyway. Off seeds a start time when there is none, so the time button has
     * something to read the moment it appears; the end stays empty, because most events do not
     * have one and "no end time" is a real answer.
     */
    fun withAllDay(allDay: Boolean): EventDraft = when {
        allDay -> copy(allDay = true, startMinute = null, endMinute = null)
        startMinute == null -> copy(allDay = false, startMinute = TimeMath.DEFAULT_MINUTE)
        else -> copy(allDay = false)
    }

    /** A start time chosen; an end that now precedes it is cleared, which is the same answer
     *  [EventRules.normalize] gives — so the button never shows a time the save would drop. */
    fun withStartTime(m: Int): EventDraft {
        val start = m.coerceIn(EventRules.MINUTE_RANGE)
        return copy(startMinute = start, endMinute = endMinute?.takeIf { it >= start })
    }

    /** An end time chosen, or cleared (the long-press). One before the start is refused the same
     *  way normalization refuses it: by clearing, not by moving the start. */
    fun withEndTime(m: Int?): EventDraft {
        val end = m?.coerceIn(EventRules.MINUTE_RANGE)
        return copy(endMinute = if (end != null && startMinute != null && end < startMinute) null else end)
    }

    // ── The note (arc 24 / Z3) ───────────────────────────────────────────────

    /** The text half as typed. The cap is [EventRules.NOTE_TEXT_MAX], applied at save by the same
     *  normalization the store runs — the field's own `LengthFilter` is the courtesy, not the rule. */
    fun withNoteText(text: String): EventDraft = copy(noteText = text)

    /**
     * The note page's size, as [NoteSurface.mintedSize] answers it: the area's size once there is
     * ink on the page ("minted with the first stroke"), and whatever the event already held while
     * there is not — so a note with no ink rides its stored size through Save unchanged, and one
     * that has been written on keeps the size it was written at wherever it is next shown.
     */
    fun withNoteSize(width: Float, height: Float): EventDraft = copy(noteWidth = width, noteHeight = height)

    // ── The repeat ───────────────────────────────────────────────────────────

    /** A repeat chosen (null = never). Touching the repeat is what stops a later type change from
     *  offering its default. */
    fun withFreq(freq: Freq?): EventDraft = applyFreq(freq).copy(repeatTouched = true)

    /** Every N units. Clamped to [EventRules.INTERVAL_RANGE] — the steppers never disable
     *  (invisible on e-ink); they simply have nothing left to do at the ends. */
    fun withInterval(delta: Int): EventDraft =
        copy(interval = (interval + delta).coerceIn(EventRules.INTERVAL_RANGE))

    /** One weekday latch toggled ([iso] is `DayOfWeek.value`: Mon = 1 … Sun = 7). Clearing the last
     *  one is allowed: an empty set means "the day this event starts on", which is what
     *  [Recurrence] does with it — so the weekly repeat still lands somewhere and the person has
     *  not been stopped from un-picking a day. */
    fun toggleWeekday(iso: Int): EventDraft {
        val next = LinkedHashSet(weekdays)
        if (!next.remove(iso)) next += iso
        return copy(weekdays = next)
    }

    fun withMonthlyMode(mode: MonthlyMode): EventDraft = copy(monthlyMode = mode)

    /** How the series ends. Both stop conditions are seeded on the way in so the control that
     *  appears is never blank: "on a date" starts at the start date, "after N" at [DEFAULT_END_COUNT]. */
    fun withEndMode(mode: EndMode): EventDraft = copy(
        endMode = mode,
        untilDate = if (mode == EndMode.UNTIL) untilDate ?: startDate else untilDate,
    )

    /** The "ends on" date. Not clamped to the start — see [withStartDate]. */
    fun withUntil(d: LocalDate): EventDraft = copy(untilDate = d)

    /** The occurrence count, clamped to [EventRules.END_COUNT_RANGE]. */
    fun withCount(delta: Int): EventDraft =
        copy(endCount = (endCount + delta).coerceIn(EventRules.END_COUNT_RANGE))

    // ── Reminders ────────────────────────────────────────────────────────────

    /**
     * The event's reminder set to [r], or cleared with null — **one at a time**, which is what the
     * editor offers (the user's call). Normalized on the way in exactly as the store would: a lead
     * of less than a day is no reminder at all, so it lands as none rather than as a reminder that
     * silently disappears at save.
     *
     * [EventRules.REMINDERS_MAX] still stands — it is the store's rule, and an event written before
     * the one-reminder editor may carry three. Saving from that editor is what reduces it to one.
     */
    fun withReminder(r: Reminder?): EventDraft =
        copy(reminders = EventRules.normalize(listOfNotNull(r)))

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** [freq] set, with the one field a frequency implies: switching to weekly with no weekday
     *  chosen lists the start date's own weekday, so the latches show what the repeat will actually
     *  do rather than seven empty boxes. Nothing is cleared when the repeat goes off — the controls
     *  keep what they said, and only [toEvent] drops them. */
    private fun applyFreq(freq: Freq?): EventDraft {
        if (freq == Freq.WEEKLY && weekdays.isEmpty()) {
            return copy(freq = freq, weekdays = linkedSetOf(startDate.dayOfWeek.value))
        }
        return copy(freq = freq)
    }

    companion object {

        /** Where the "after N times" stepper starts. Ten occurrences is a plausible short series and
         *  reads as a sentence; 1 would be a repeat that repeats once. */
        const val DEFAULT_END_COUNT = 10

        /** A brand-new event on [day]: an all-day one-off called nothing, of the generic type. Every
         *  other default is the one that asks the fewest questions — no repeat, no reminder, one day
         *  long. */
        fun blank(id: String, day: LocalDate, now: Long): EventDraft = EventDraft(
            id = id,
            type = EventType.OTHER,
            title = "",
            startDate = day,
            endDate = day,
            allDay = true,
            startMinute = null,
            endMinute = null,
            freq = null,
            interval = 1,
            weekdays = emptySet(),
            monthlyMode = MonthlyMode.DAY_OF_MONTH,
            endMode = EndMode.NEVER,
            untilDate = null,
            endCount = DEFAULT_END_COUNT,
            reminders = emptyList(),
            repeatTouched = false,
            exceptions = emptySet(),
            noteText = "",
            noteWidth = 0f,
            noteHeight = 0f,
            createdAt = now,
        )

        /**
         * An existing event opened. The repeat counts as **touched** whatever it says — an event
         * that exists has already answered the repeat question (even if the answer was "never"), so
         * changing its type must never reach in and change it.
         *
         * The controls a null recurrence leaves unanswered take [blank]'s defaults, so turning the
         * repeat on shows a sensible rule rather than an empty one.
         */
        fun from(e: Event): EventDraft {
            val r = e.recurrence
            return EventDraft(
                id = e.id,
                type = e.type,
                title = e.title,
                startDate = e.startDate,
                endDate = e.endDate,
                allDay = e.allDay,
                startMinute = e.startMinute,
                endMinute = e.endMinute,
                freq = r?.freq,
                interval = r?.interval ?: 1,
                weekdays = r?.weekdays.orEmpty(),
                monthlyMode = r?.monthlyMode ?: MonthlyMode.DAY_OF_MONTH,
                endMode = r?.endMode ?: EndMode.NEVER,
                untilDate = r?.untilDate,
                endCount = r?.endCount ?: DEFAULT_END_COUNT,
                reminders = e.reminders,
                repeatTouched = true,
                exceptions = e.exceptions,
                noteText = e.noteText,
                noteWidth = e.noteWidth,
                noteHeight = e.noteHeight,
                createdAt = e.createdAt,
            )
        }

        /**
         * Which ordinal slot of its month [date] sits in, and whether that slot means **last**.
         *
         * [Recurrence]'s semantics exactly, and the reason this is shared rather than re-derived for
         * the radio's label: a 5th slot is treated as "the last one", because most months have no
         * fifth Tuesday and a person picking the fifth meant the last. A 4th that happens to also be
         * the month's last is still "4th" — that is what the engine matches, so that is what the
         * radio must say.
         *
         * @return the slot (1..5) and whether it reads as "last".
         */
        fun ordinalOf(date: LocalDate): Pair<Int, Boolean> {
            val ordinal = (date.dayOfMonth - 1) / 7 + 1
            return if (ordinal >= 5) 5 to true else ordinal to false
        }
    }
}
