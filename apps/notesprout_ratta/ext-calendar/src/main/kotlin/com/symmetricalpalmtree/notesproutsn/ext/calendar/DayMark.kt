package com.symmetricalpalmtree.notesproutsn.ext.calendar

import java.time.LocalDate

/**
 * What the **grid** knows about an event (arc 24 / Z4) — the four things a Month cell's glyph row
 * and a Day page's row label need, and nothing else.
 *
 * A [DayMark] is deliberately **neutral of the store**: no id, no recurrence, no reminders, no note.
 * The grid is baked into the page's template bitmap, which is redrawn whenever the bake key changes
 * ([CalendarActivity]'s `BakeKey`), and that key compares marks **structurally** — so a mark must
 * carry exactly what is drawn and nothing that merely moves underneath it. An occurrence of a
 * recurring event and a one-off that read the same on the page *are* the same mark, and neither
 * causes a repaint the person could not have seen.
 *
 * Note two shapes the day list already allows and the grid has to answer for:
 * `EventRules.normalize` clears [startMinute] whenever [allDay] is set, and a **non**-all-day event
 * may still have no start minute at all (a "timeless" event) — [DayRows] gives both of them a row
 * from the top of the half rather than a row on the clock.
 */
data class DayMark(
    val title: String,
    val allDay: Boolean,
    val startMinute: Int?,
    val glyph: Glyph,
) {
    companion object {
        /** [e] as the grid sees it. */
        fun of(e: Event): DayMark = DayMark(e.title, e.allDay, e.startMinute, Glyph.of(e.type))
    }
}

/**
 * og's six per-type glyphs, in Canvas primitives (the template is **Context-free** — it holds no
 * resources and no drawables, so a glyph is arithmetic on a box). One per [EventType]; the row
 * shows **distinct** glyphs only, so a day with two birthdays shows one cake.
 */
enum class Glyph {
    /** [EventType.BIRTHDAY]. */
    CAKE,

    /** [EventType.ANNIVERSARY]. */
    HEART,

    /** [EventType.VACATION]. */
    SUITCASE,

    /** [EventType.MEETING]. */
    PEOPLE,

    /** [EventType.APPOINTMENT]. */
    CLOCK,

    /** [EventType.OTHER] — the plain event, which is a mark that it happened at all. */
    DOT,
    ;

    companion object {
        /** The glyph [type] draws as. Total, and the one mapping — the pairing is a locked decision. */
        fun of(type: EventType): Glyph = when (type) {
            EventType.BIRTHDAY -> CAKE
            EventType.ANNIVERSARY -> HEART
            EventType.VACATION -> SUITCASE
            EventType.MEETING -> PEOPLE
            EventType.APPOINTMENT -> CLOCK
            EventType.OTHER -> DOT
        }
    }
}

/**
 * The read-side seam the calendar's page loads its marks through — [EventStore] implements it, and
 * the document's tests fake it.
 *
 * It exists so [CalendarDocument] can read marks in the **same** IO hop as the page's strokes
 * without knowing anything about events: the document owns *which page is showing*, and marks are
 * one more thing a showing page has.
 *
 * Days with nothing on them are **absent** from the answer, not present and empty — a month grid
 * asks about 42 days and usually cares about four.
 */
fun interface MarkSource {
    /** Every day in `[from, to]` that holds anything, mapped to its marks in `EventOrder.DAY`. */
    fun marksFor(from: LocalDate, to: LocalDate): Map<LocalDate, List<DayMark>>
}
