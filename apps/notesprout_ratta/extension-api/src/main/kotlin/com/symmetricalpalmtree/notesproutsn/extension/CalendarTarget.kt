package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable
import java.time.LocalDate

/**
 * One calendar page, named (arc 23 / Y1): the placement of a notebook → calendar transfer, and the
 * page a `receiveInk` lands on. Where the pad names its placement with an int, the calendar's target
 * is a real type, because a page here is a *date* — and a date that is not normalized would mint a
 * second row for the same period.
 *
 * [kind] is one of [KIND_MONTH] / [KIND_WEEK] / [KIND_DAY]. [date] is the period's ISO day
 * (`yyyy-MM-dd`), **already normalized** — a month's first day, a week's Sunday, the day itself
 * ([CalendarDates.isNormalized]); anything else is rejected rather than corrected, because a host
 * that sent the 15th as a month page has a bug, not a rounding problem. [half] is 0 for a month or a
 * week and 0 (AM) or 1 (PM) for a day.
 *
 * The constructor `require`s **are** the validation, both directions — unmarshal is validation, the
 * family rule since E1: a target that fails them crosses as an `IllegalArgumentException`.
 *
 * Wire form: `int kind · String date · int half`. A future field is a compatible tail.
 */
class CalendarTarget(
    val kind: Int,
    val date: String,
    val half: Int,
) : Parcelable {

    init {
        requireValid(kind, date, half)
    }

    /** The target's day as a [LocalDate] — valid by construction. */
    val localDate: LocalDate get() = LocalDate.parse(date)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(kind)
        dest.writeString(date)
        dest.writeInt(half)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is CalendarTarget && other.kind == kind && other.date == date && other.half == half

    override fun hashCode(): Int = (kind * 31 + date.hashCode()) * 31 + half

    override fun toString(): String = "CalendarTarget(kind=$kind, date=$date, half=$half)"

    companion object {
        /** A month page — [date] is the month's first day. */
        const val KIND_MONTH: Int = 0

        /** A week page — [date] is the week's Sunday. */
        const val KIND_WEEK: Int = 1

        /** One half of a day — [date] is the day, [half] says which ledger. */
        const val KIND_DAY: Int = 2

        /** The AM half of a day page (midnight to noon). */
        const val HALF_AM: Int = 0

        /** The PM half of a day page (noon to midnight). */
        const val HALF_PM: Int = 1

        /** The constructor's checks, pure so they are JVM-testable. */
        fun requireValid(kind: Int, date: String, half: Int) {
            require(kind == KIND_MONTH || kind == KIND_WEEK || kind == KIND_DAY) { "unknown kind ($kind)" }
            val day = requireNotNull(CalendarDates.parse(date)) { "date is not an ISO day" }
            require(CalendarDates.isNormalized(kind, day)) { "date is not normalized for kind $kind" }
            require(half == HALF_AM || (kind == KIND_DAY && half == HALF_PM)) { "half $half is not legal for kind $kind" }
        }

        /** A target from a [LocalDate], normalized here — the constructor for a caller that has a day
         *  in hand rather than a page (the host's target sheet). */
        fun of(kind: Int, day: LocalDate, half: Int = HALF_AM): CalendarTarget =
            CalendarTarget(kind, CalendarDates.format(CalendarDates.periodDate(kind, day)), half)

        @JvmField
        val CREATOR: Parcelable.Creator<CalendarTarget> = object : Parcelable.Creator<CalendarTarget> {
            override fun createFromParcel(parcel: Parcel): CalendarTarget =
                CalendarTarget(parcel.readInt(), parcel.readString() ?: "", parcel.readInt())

            override fun newArray(size: Int): Array<CalendarTarget?> = arrayOfNulls(size)
        }
    }
}
