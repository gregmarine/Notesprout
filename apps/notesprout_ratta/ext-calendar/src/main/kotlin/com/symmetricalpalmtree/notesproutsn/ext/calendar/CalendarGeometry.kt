package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Where everything on a calendar page sits (arc 23 — Month, Week and Day) — pure integers,
 * JVM-tested, no `android.graphics`. The template painter draws what this says and the finger
 * hit-test reads it back, so the two can never disagree.
 *
 * **Rule for Month and Week: every dimension is width- or dp-derived; height slack goes to the
 * Notes band; nothing is a proportional slice of the height.** The Month grid is square cells from
 * the width and whatever height is left below is the Notes band; Week borrows both. **The Day page
 * is the one exception, by decision (Z5b Manta check, 2026-09-04): its 24 rows share the whole
 * height between the bars evenly and there is no band** — a day is a ledger, not a ledger over a
 * note. og's height-derived Day rows were a ledgered bug because og's *canvas* changed height under
 * the same page (the top guard); here the page is the whole screen, the bars overlay it and the
 * guard is 0 on Ratta, so the height a page is laid out at is the height it is drawn at.
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

    /** The Day page's left gutter, where the time labels live — og's value. */
    const val DAY_GUTTER_DP = 80f

    /** Half-hour rows in one half of a day: twelve hours, two rows an hour. */
    const val DAY_ROWS = 24

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

    // ── Week ─────────────────────────────────────────────────────────────────

    /** The Week page's geometry: 2×4 cells (Sun..Sat and one spare) over the same Notes band. */
    class Week(
        val width: Int,
        val height: Int,
        val hairline: Int,
        /** The cells' left edge — what four cells and three hairlines do not use is split. */
        val left: Int,
        val cellW: Int,
        val cellH: Int,
        val cellsTop: Int,
        val cellsBottom: Int,
        val notesTop: Int,
        val notesBottom: Int,
    ) {
        val pitchX: Int get() = cellW + hairline
        val pitchY: Int get() = cellH + hairline

        val contentRight: Int get() = left + 4 * cellW + 3 * hairline

        val notesHeight: Int get() = maxOf(0, notesBottom - notesTop)

        fun cellLeft(col: Int): Int = left + col * pitchX
        fun cellTop(row: Int): Int = cellsTop + row * pitchY

        /** The x of the hairline before column [col] (1..3), its left edge. */
        fun columnDividerX(col: Int): Int = cellLeft(col) - hairline

        /** The y of the one hairline between the two rows, its top edge. */
        fun rowDividerY(): Int = cellTop(1) - hairline

        /**
         * The day under ([x], [y]) in the week starting [sunday], or null. Cell index is
         * `row * 4 + col`: 0..6 are Sun..Sat, **7 is the spare** — blank, unlabeled, and nobody's
         * day, so it hit-tests to null exactly like the band, the margins and the hairlines do.
         */
        fun hitTest(x: Float, y: Float, sunday: LocalDate): LocalDate? {
            if (y < cellsTop || y >= cellsBottom || x < left || x >= contentRight) return null
            val col = ((x - left) / pitchX).toInt()
            val row = ((y - cellsTop) / pitchY).toInt()
            if (col !in 0..3 || row !in 0..1) return null
            if (x - cellLeft(col) >= cellW || y - cellTop(row) >= cellH) return null   // on a divider
            val index = row * 4 + col
            if (index > 6) return null
            return sunday.plusDays(index.toLong())
        }
    }

    /**
     * The Week page at the same page size as [month]. **The cell area is the Month page's grid
     * area** — header, its hairline, six cells and five hairlines — so the Notes band below it is
     * Month's band to within the integer rounding of halving that area. Cells are the width's
     * quarter, two rows with one divider between them; the eighth cell is spare.
     */
    fun week(widthPx: Int, heightPx: Int, density: Float, topInsetPx: Int, bottomInsetPx: Int): Week {
        val hairline = maxOf(1, density.roundToInt())
        val reference = month(widthPx, heightPx, density, topInsetPx, bottomInsetPx)
        val cellsTop = topInsetPx
        val bottom = heightPx - bottomInsetPx
        val area = maxOf(0, reference.gridBottom - cellsTop)
        val cellW = maxOf(1, (widthPx - 3 * hairline) / 4)
        val left = (widthPx - (4 * cellW + 3 * hairline)) / 2
        val cellH = maxOf(1, (area - hairline) / 2)
        val cellsBottom = cellsTop + 2 * cellH + hairline
        val notesTop = cellsBottom + hairline
        return Week(
            width = widthPx, height = heightPx, hairline = hairline, left = left,
            cellW = cellW, cellH = cellH, cellsTop = cellsTop, cellsBottom = cellsBottom,
            notesTop = notesTop, notesBottom = maxOf(notesTop, bottom),
        )
    }

    // ── Day ──────────────────────────────────────────────────────────────────

    /**
     * The Day page's geometry: [DAY_ROWS] half-hour rows of **one even height** filling the page
     * between the bars and a time-label gutter down the left. **The last row takes the integer
     * division's remainder** (at most 23 px) so the rows meet the bottom bar exactly, and there is
     * no closing hairline — the bar's own top border closes the ledger. The rows span the page's
     * full width, as og's do — [left] and [right] are 0 and the width.
     */
    class Day(
        val width: Int,
        val height: Int,
        val hairline: Int,
        /** The gutter hairline's left edge — the labels live to its left, the ledger to its right. */
        val gutterLeft: Int,
        /** The gutter hairline's right edge. */
        val gutterRight: Int,
        val rowsTop: Int,
        /** Every row's height but the last's — see [rowHeight]. */
        val rowHeight: Int,
        /** The last row's bottom edge: the bottom bar's top, exactly. */
        val rowsBottom: Int,
    ) {
        val left: Int get() = 0
        val right: Int get() = width

        /** From one row's top edge to the next: the row plus its divider. */
        val pitch: Int get() = rowHeight + hairline

        /** Row [i]'s top edge, `i` in 0..[DAY_ROWS] − 1. */
        fun rowTop(i: Int): Int = rowsTop + i * pitch

        /** Row [i]'s height: [rowHeight], except the last row, which runs to [rowsBottom]. */
        fun rowHeight(i: Int): Int = if (i == DAY_ROWS - 1) rowsBottom - rowTop(i) else rowHeight

        /** The y of the hairline above row [i] (1..23), its top edge — the first row has none. */
        fun rowDividerY(i: Int): Int = rowTop(i) - hairline
    }

    /**
     * The Day page for one half. The height between the bars, less the 23 dividers, is split evenly
     * over the 24 rows (integer, never below 1 px); the remainder — at most 23 px — goes to the last
     * row, so [Day.rowsBottom] is the bottom bar's top. A taller page means taller rows.
     */
    fun day(widthPx: Int, heightPx: Int, density: Float, topInsetPx: Int, bottomInsetPx: Int): Day {
        val hairline = maxOf(1, density.roundToInt())
        val rowsTop = topInsetPx
        val bottom = heightPx - bottomInsetPx
        val dividers = (DAY_ROWS - 1) * hairline
        val rowHeight = maxOf(1, (bottom - rowsTop - dividers) / DAY_ROWS)
        val rowsBottom = maxOf(bottom, rowsTop + DAY_ROWS * rowHeight + dividers)
        val gutterLeft = (DAY_GUTTER_DP * density).roundToInt()
        return Day(
            width = widthPx, height = heightPx, hairline = hairline,
            gutterLeft = gutterLeft, gutterRight = gutterLeft + hairline,
            rowsTop = rowsTop, rowHeight = rowHeight, rowsBottom = rowsBottom,
        )
    }

    /**
     * The label on row [slot] (0..[DAY_ROWS] − 1) of the [half] ledger: "12:00 AM", "12:30 AM",
     * "1:00 AM" … "11:30 AM", and the same twelve hours with "PM" for the afternoon half. Built
     * from ints, with AM/PM out of [CalendarDates.HALF_NAMES] — **never a formatter** (arc 5's
     * rule: CLDR data drifts, and a page label is chrome, not locale data).
     */
    fun dayRowLabel(half: Int, slot: Int): String {
        val hour = half * 12 + slot / 2
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val minute = if (slot % 2 == 0) "00" else "30"
        return "$h12:$minute ${CalendarDates.HALF_NAMES[half]}"
    }
}
