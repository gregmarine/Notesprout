package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Where everything on a calendar page sits (arc 23 / Y1 — Month; Week and Day arrive with Y2) —
 * pure integers, JVM-tested, no `android.graphics`. The template painter draws what this says and
 * the finger hit-test reads it back, so the two can never disagree.
 *
 * **Rule: every dimension is width- or dp-derived; height slack goes to a band; nothing is a
 * proportional slice of the height.** og's Day view sized its rows from the height and is a
 * ledgered bug for it; here the Month grid is square cells from the width, and whatever height is
 * left below the grid is the Notes band.
 *
 * **Hairlines are `round(density)` px on integer edges** — a 1 dp line at the Nomad's 1.875 density
 * is a coin flip otherwise (the standing trap). Every edge here is an `Int`.
 *
 * The page is the whole paper surface; the two chrome bars overlay it, so the layout begins under
 * the top bar ([topInset]) and ends above the bottom one ([bottomInset]).
 */
object CalendarGeometry {

    /** The day-of-week header band, og's value. */
    const val DOW_HEADER_DP = 40f

    /** The Month page's geometry. */
    class Month(
        val width: Int,
        val height: Int,
        /** The hairline thickness in px. */
        val hairline: Int,
        /** The grid's left edge — the width that seven cells and six hairlines do not use is split. */
        val left: Int,
        /** A cell's side; cells are square. */
        val cell: Int,
        val headerTop: Int,
        val headerBottom: Int,
        val gridTop: Int,
        val gridBottom: Int,
        val notesTop: Int,
        val notesBottom: Int,
    ) {
        /** From one cell's left/top edge to the next: the cell plus one hairline. */
        val pitch: Int get() = cell + hairline

        val contentRight: Int get() = left + 7 * cell + 6 * hairline

        val notesHeight: Int get() = maxOf(0, notesBottom - notesTop)

        /** A cell's box, `[left, right)` × `[top, bottom)`. */
        fun cellLeft(col: Int): Int = left + col * pitch
        fun cellTop(row: Int): Int = gridTop + row * pitch

        /** The x of the hairline before column [col] (1..6), its left edge. */
        fun columnDividerX(col: Int): Int = cellLeft(col) - hairline

        /** The y of the hairline above row [row] (1..5), its top edge. */
        fun rowDividerY(row: Int): Int = cellTop(row) - hairline

        /**
         * The day under ([x], [y]) on the 6×7 grid of the month starting [monthStart], or null in
         * the header, the Notes band, the side margins, or on a hairline's own pixels — a cell is a
         * cell, a line is nobody's.
         */
        fun hitTest(x: Float, y: Float, monthStart: LocalDate): LocalDate? {
            if (y < gridTop || y >= gridBottom || x < left || x >= contentRight) return null
            val col = ((x - left) / pitch).toInt()
            val row = ((y - gridTop) / pitch).toInt()
            if (col !in 0..6 || row !in 0..5) return null
            if (x - cellLeft(col) >= cell || y - cellTop(row) >= cell) return null   // on a divider
            return CalendarDates.firstCell(monthStart).plusDays((row * 7 + col).toLong())
        }
    }

    /**
     * The Month page for a [widthPx] × [heightPx] page at [density], under a top bar [topInsetPx]
     * tall and above a bottom bar [bottomInsetPx] tall. Cells are the widest square that lets seven
     * of them and six hairlines fit the width — and, should a page be too short for six of those
     * plus the header (never on a Nomad, but a store can travel), the tallest square that fits the
     * height instead, so the Notes band is never negative and the grid never runs under the bar.
     */
    fun month(widthPx: Int, heightPx: Int, density: Float, topInsetPx: Int, bottomInsetPx: Int): Month {
        val hairline = maxOf(1, density.roundToInt())
        val headerTop = topInsetPx
        val headerH = (DOW_HEADER_DP * density).roundToInt()
        val headerBottom = headerTop + headerH
        val gridTop = headerBottom + hairline
        val bottom = heightPx - bottomInsetPx
        val byWidth = (widthPx - 6 * hairline) / 7
        val byHeight = (bottom - gridTop - 6 * hairline) / 6     // 5 dividers + the Notes band's top line
        val cell = maxOf(1, minOf(byWidth, byHeight))
        val contentW = 7 * cell + 6 * hairline
        val left = (widthPx - contentW) / 2
        val gridBottom = gridTop + 6 * cell + 5 * hairline
        val notesTop = gridBottom + hairline
        return Month(
            width = widthPx, height = heightPx, hairline = hairline, left = left, cell = cell,
            headerTop = headerTop, headerBottom = headerBottom, gridTop = gridTop, gridBottom = gridBottom,
            notesTop = notesTop, notesBottom = maxOf(notesTop, bottom),
        )
    }
}
