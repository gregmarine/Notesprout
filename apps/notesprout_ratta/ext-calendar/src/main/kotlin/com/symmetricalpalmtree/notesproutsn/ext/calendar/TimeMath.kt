package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * The time picker's arithmetic (arc 24 / Z2) — pure, so the dialog is a thin caller: it steps, it
 * reads the three parts back, and it hands over one minute of day.
 *
 * **A minute of day is the only stored form.** There is no `LocalTime`, no formatter and no time
 * zone anywhere in events: an event is a thing on a calendar page, not an instant, and 9:00 AM is
 * 9:00 AM on whatever device opens the notebook. The 12-hour split lives here and only here.
 *
 * The steppers **wrap and never carry**: 12 → 1 on the hour, 55 → 00 on the minute, and rolling
 * the minute past the top does *not* move the hour. That is the paper answer — the person is
 * dialling three independent parts, not counting elapsed time — and it means no stepper can ever
 * walk the value off the end of the day.
 */
object TimeMath {

    /** The minute stepper's grain. Five minutes is what a calendar entry is worth; a 60-position
     *  stepper is a stepper nobody reaches the end of. */
    const val MINUTE_STEP = 5

    /** How many positions the minute stepper has (0, 5, … 55). */
    const val MINUTE_POSITIONS = 60 / MINUTE_STEP

    /** Where the picker starts when the field has no time yet — 9:00 AM, the top of a day. */
    const val DEFAULT_MINUTE = 9 * 60

    /** The 12-hour hour of [minuteOfDay], 1..12 (midnight and noon are both 12). */
    fun hour12(minuteOfDay: Int): Int {
        val h24 = minuteOfDay.coerceIn(EventRules.MINUTE_RANGE) / 60
        return if (h24 % 12 == 0) 12 else h24 % 12
    }

    /** Whether [minuteOfDay] is in the afternoon half. Noon is PM; midnight is AM. */
    fun isPm(minuteOfDay: Int): Boolean = minuteOfDay.coerceIn(EventRules.MINUTE_RANGE) >= 12 * 60

    /**
     * The minute part of [minuteOfDay], **snapped to the stepper's grain** so the dialog always
     * opens on a position the stepper can actually reach. Nearest wins, and 58 snaps *down* to 55
     * rather than up into the next hour — a picker may not change the hour behind the person's back.
     */
    fun minuteOfHour(minuteOfDay: Int): Int = snap(minuteOfDay.coerceIn(EventRules.MINUTE_RANGE) % 60)

    /** [m] on the nearest stepper position, never past 55. */
    fun snap(m: Int): Int {
        val clamped = m.coerceIn(0, 59)
        return (((clamped + MINUTE_STEP / 2) / MINUTE_STEP) * MINUTE_STEP).coerceAtMost(60 - MINUTE_STEP)
    }

    /** The three parts back together as a minute of day. 12 AM is 0; 12 PM is noon. */
    fun minuteOfDay(hour12: Int, minuteOfHour: Int, pm: Boolean): Int {
        val h = ((hour12 - 1).mod(12)) + 1
        val h24 = (h % 12) + if (pm) 12 else 0
        return (h24 * 60 + minuteOfHour.coerceIn(0, 59)).coerceIn(EventRules.MINUTE_RANGE)
    }

    /** [hour12] moved by [delta] positions, wrapping inside 1..12. */
    fun stepHour(hour12: Int, delta: Int): Int = ((hour12 - 1 + delta).mod(12)) + 1

    /** [minuteOfHour] moved by [delta] stepper positions, wrapping inside 0..55 — and carrying
     *  nothing into the hour, deliberately (see the class doc). */
    fun stepMinute(minuteOfHour: Int, delta: Int): Int =
        ((snap(minuteOfHour) / MINUTE_STEP + delta).mod(MINUTE_POSITIONS)) * MINUTE_STEP
}
