package com.symmetricalpalmtree.notesproutsn.ext.calendar

/**
 * The time picker's arithmetic (arc 24 / Z2) — pure, so the dialog is a thin caller: it reads the
 * three parts out, lets the face and the latches change them, and hands over one minute of day.
 *
 * **A minute of day is the only stored form.** There is no `LocalTime`, no formatter and no time
 * zone anywhere in events: an event is a thing on a calendar page, not an instant, and 9:00 AM is
 * 9:00 AM on whatever device opens the notebook. The 12-hour split lives here and only here.
 *
 * **Three independent parts, and no arithmetic between them.** The hour, the minute and the half are
 * dialled separately — the person is naming a place on a calendar page, not counting elapsed time —
 * so nothing here can walk a value off the end of the day. Arc 24 / Z5b replaced the two steppers
 * with a clock face ([ClockFaceModel]); what stayed is this, the time truth: [snap] and
 * [minuteOfHour] are what put a stored minute on the face's grain, and [minuteOfDay] is what takes
 * the three parts back.
 */
object TimeMath {

    /** The minute face's grain. Five minutes is what a calendar entry is worth; a 60-position dial
     *  is a dial nobody lands on with a pen. */
    const val MINUTE_STEP = 5

    /** How many minute positions there are (0, 5, … 55). */
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
     * The minute part of [minuteOfDay], **snapped to the face's grain** so the dialog always opens
     * on a position the dial actually has. Nearest wins, and 58 snaps *down* to 55 rather than up
     * into the next hour — a picker may not change the hour behind the person's back.
     */
    fun minuteOfHour(minuteOfDay: Int): Int = snap(minuteOfDay.coerceIn(EventRules.MINUTE_RANGE) % 60)

    /** [m] on the nearest face position, never past 55. */
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
}
