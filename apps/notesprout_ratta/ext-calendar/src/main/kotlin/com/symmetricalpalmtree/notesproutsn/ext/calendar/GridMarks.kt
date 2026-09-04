package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import java.time.LocalDate

/**
 * Where a Month or Week cell's glyphs sit (arc 24 / Z4) — **pure**, JVM-tested, no
 * `android.graphics`. [CalendarTemplate] draws what this says, exactly as
 * [CalendarGeometry] already owns every rect the painter and the hit-test share: the arithmetic
 * that decides whether a third type fits is testable, and the Canvas code is only ink.
 *
 * og's placement rule, re-derived (`CalendarTemplateRenderer.drawDayCellIcons`): the glyph row is
 * **right-aligned** on the day-number row, because the number is left-aligned and the two must
 * never meet; slots are as tall as the number, between 10 and 16 dp; and when there is not room for
 * every distinct type the last slot becomes a `+`, which says "and more" without lying about which.
 */
object GridMarks {

    /**
     * The distinct glyphs of [marks], in **first-seen** order.
     *
     * Marks arrive in `EventOrder.DAY` (all-day first, then by start minute, then title), so
     * first-seen is "earliest in the day, and stable" — a day with a birthday and two meetings shows
     * the cake before the people whichever meeting was typed first.
     */
    fun distinct(marks: List<DayMark>): List<Glyph> {
        val seen = LinkedHashSet<Glyph>(marks.size.coerceAtMost(Glyph.entries.size))
        for (m in marks) seen += m.glyph
        return seen.toList()
    }

    /** One slot on the number row: a [glyph], or **null** for the overflow `+`. [x] is the slot
     *  box's LEFT edge; the box is `iconSize` square. */
    data class Slot(val glyph: Glyph?, val x: Float)

    /**
     * The slots [distinct] takes between [leftEdge] and [rightEdge], each [iconSize] wide with
     * [gap] between them — og's arithmetic:
     *
     * - `maxSlots = ((rightEdge − leftEdge) + gap) / (iconSize + gap)`, floored. Nothing fits when
     *   that is zero or less, and the row draws nothing at all rather than something clipped;
     * - **overflow** is `distinct.size > maxSlots`: the first `maxSlots − 1` glyphs, then the `+`.
     *   So a cell with room for one slot and two types shows a lone `+` — which is honest, where a
     *   single arbitrary glyph would not be;
     * - the slots are packed against [rightEdge], not spread: the first x is
     *   `rightEdge − slots·iconSize − (slots − 1)·gap`, and each next one is `iconSize + gap` on.
     */
    fun layout(distinct: List<Glyph>, leftEdge: Float, rightEdge: Float, iconSize: Float, gap: Float): List<Slot> {
        val pitch = iconSize + gap
        if (pitch <= 0f) return emptyList()
        val maxSlots = (((rightEdge - leftEdge) + gap) / pitch).toInt()
        if (maxSlots <= 0) return emptyList()

        val overflow = distinct.size > maxSlots
        val glyphs = if (overflow) distinct.take(maxSlots - 1) else distinct
        val slots = glyphs.size + if (overflow) 1 else 0
        if (slots == 0) return emptyList()

        var x = rightEdge - slots * iconSize - (slots - 1).coerceAtLeast(0) * gap
        val out = ArrayList<Slot>(slots)
        for (g in glyphs) {
            out += Slot(g, x)
            x += pitch
        }
        if (overflow) out += Slot(null, x)
        return out
    }

    /**
     * The day range a page of [target] shows, inclusive — what [MarkSource.marksFor] is asked for
     * when that page is loaded.
     *
     * A **Month** page asks about all 42 visible cells, the out-of-month ones included: they are
     * real days on the paper, fully writable, and an event on one of them belongs on its cell. A
     * **Week** page is its Sunday plus six (the spare eighth cell is nobody's day). A **Day** page
     * is the one day — both halves come from the same list, so the AM and PM pages of a day never
     * disagree about what is on it.
     */
    fun rangeOf(target: CalendarTarget): Pair<LocalDate, LocalDate> {
        val date = target.localDate
        return when (target.kind) {
            CalendarTarget.KIND_MONTH -> {
                val first = CalendarDates.firstCell(date)
                first to first.plusDays((MONTH_CELLS - 1).toLong())
            }
            CalendarTarget.KIND_WEEK -> date to date.plusDays(6)
            else -> date to date
        }
    }

    /** The Month grid's cells: six rows of seven, every one of them a real day. */
    const val MONTH_CELLS = 42
}
