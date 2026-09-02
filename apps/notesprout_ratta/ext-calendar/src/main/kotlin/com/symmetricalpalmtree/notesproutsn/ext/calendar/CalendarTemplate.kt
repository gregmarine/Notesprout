package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Paints a calendar page's grid as the page **template** (arc 23 — Month, Week and Day): a
 * transparent ARGB_8888 bitmap at the page's own size that g-paper sets behind the ink, so grid and
 * ink scale and register as one. Geometry is [CalendarGeometry]'s — nothing is measured here that
 * the hit-test does not also read.
 *
 * og's three layouts verbatim. Month: Sun–Sat header, six rows of seven square cells. Week: 2×4
 * cells, each with its Sun/Mon/… label above the number, and an eighth cell left blank. Both close
 * with the Notes band. Day: half-hour rows with their time labels in the left gutter, and the slack
 * band below. Day numbers sit top-left with a hairline under the number row, out-of-month numbers
 * take the light ink, and **today's number is ringed** — the one mark, because nothing selects; the
 * ring arithmetic lives in [dayCell] alone, so Month and Week can never draw it differently.
 *
 * Names come from [CalendarDates]' hand lists, never a formatter. Hairlines are filled rects
 * `hairline` px thick on the integer edges the geometry names.
 *
 * Re-baked on every navigation and on `onResume`, because "today" moves.
 */
object CalendarTemplate {

    /** The two inks, passed in from resources — the painter holds no colour of its own. */
    class Palette(val ink: Int, val light: Int)

    fun month(g: CalendarGeometry.Month, monthStart: LocalDate, today: LocalDate, density: Float, palette: Palette, notesLabel: String): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, g.width), maxOf(1, g.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        val hp = g.hairline.toFloat()

        // ── Day-of-week header: labels centred in their columns, hairline below, dividers between.
        p.color = palette.ink; p.style = Paint.Style.FILL
        p.textSize = 11f * density; p.textAlign = Paint.Align.CENTER
        val headerMid = (g.headerTop + g.headerBottom) / 2f
        for (c in 0 until 7) {
            val name = CalendarDates.DAY_NAMES[c]
            p.getTextBounds(name, 0, name.length, bounds)
            canvas.drawText(name, g.cellLeft(c) + g.cell / 2f, headerMid - bounds.exactCenterY(), p)
        }
        hline(canvas, p, g.left, g.headerBottom, g.contentRight, hp)
        for (c in 1 until 7) vline(canvas, p, g.columnDividerX(c), g.headerTop, g.headerBottom, hp)

        // ── The grid.
        for (c in 1 until 7) vline(canvas, p, g.columnDividerX(c), g.gridTop, g.gridBottom, hp)
        for (r in 1 until 6) hline(canvas, p, g.left, g.rowDividerY(r), g.contentRight, hp)

        val firstCell = CalendarDates.firstCell(monthStart)
        for (r in 0 until 6) {
            for (c in 0 until 7) {
                val date = firstCell.plusDays((r * 7 + c).toLong())
                val inMonth = date.month == monthStart.month && date.year == monthStart.year
                dayCell(
                    canvas, p, bounds, g.cellLeft(c).toFloat(), g.cellTop(r).toFloat(), g.cell.toFloat(),
                    date, showDayOfWeek = false, inMonth = inMonth, today = today,
                    density = density, palette = palette, hp = hp,
                )
            }
        }

        // ── The Notes band.
        if (g.notesHeight > 0) {
            hline(canvas, p, g.left, g.notesTop - g.hairline, g.contentRight, hp)
            bandLabel(canvas, p, bounds, g.left.toFloat(), g.notesTop.toFloat(), density, palette, notesLabel)
        }
        return bmp
    }

    /**
     * The Week page: two rows of four cells starting at the top inset, the eighth cell left blank
     * and unlabeled (it is not a day — nothing is written about it), and the same Notes band Month
     * closes with. All seven days are "in month": a week page has no outside.
     */
    fun week(g: CalendarGeometry.Week, sunday: LocalDate, today: LocalDate, density: Float, palette: Palette, notesLabel: String): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, g.width), maxOf(1, g.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        val hp = g.hairline.toFloat()

        p.color = palette.ink
        for (c in 1 until 4) vline(canvas, p, g.columnDividerX(c), g.cellsTop, g.cellsBottom, hp)
        hline(canvas, p, g.left, g.rowDividerY(), g.contentRight, hp)

        for (r in 0 until 2) {
            for (c in 0 until 4) {
                val index = r * 4 + c
                if (index > 6) continue                       // the spare cell: blank paper
                dayCell(
                    canvas, p, bounds, g.cellLeft(c).toFloat(), g.cellTop(r).toFloat(), g.cellW.toFloat(),
                    sunday.plusDays(index.toLong()), showDayOfWeek = true, inMonth = true, today = today,
                    density = density, palette = palette, hp = hp,
                )
            }
        }

        if (g.notesHeight > 0) {
            hline(canvas, p, g.left, g.cellsBottom, g.contentRight, hp)
            bandLabel(canvas, p, bounds, g.left.toFloat(), g.notesTop.toFloat(), density, palette, notesLabel)
        }
        return bmp
    }

    /**
     * The Day page for one [half]: 24 half-hour rows across the full page width, their time labels
     * in the left gutter, the gutter's own hairline running the rows' height, a closing hairline
     * under the last row, and the slack band below it. **The band takes the Notes label only when it
     * is tall enough to hold one** ([CalendarGeometry.SLACK_LABEL_MIN_DP]); otherwise it is blank
     * paper, which is honest — a label crushed against the bottom bar names nothing.
     *
     * There is no header band: the page's title in the chrome already names the date and the half.
     */
    fun day(g: CalendarGeometry.Day, half: Int, density: Float, palette: Palette, notesLabel: String): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, g.width), maxOf(1, g.height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        val hp = g.hairline.toFloat()

        p.color = palette.ink; p.style = Paint.Style.FILL
        p.textSize = 11f * density; p.textAlign = Paint.Align.LEFT
        for (slot in 0 until CalendarGeometry.DAY_ROWS) {
            val top = g.rowTop(slot)
            val label = CalendarGeometry.dayRowLabel(half, slot)
            p.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, 12f * density, top + g.rowHeight / 2f - bounds.exactCenterY(), p)
            if (slot > 0) hline(canvas, p, g.left, g.rowDividerY(slot), g.right, hp)
        }
        vline(canvas, p, g.gutterLeft, g.rowsTop, g.rowsBottom, hp)
        hline(canvas, p, g.left, g.rowsBottom, g.right, hp)

        if (g.slackHeight >= (CalendarGeometry.SLACK_LABEL_MIN_DP * density).roundToInt()) {
            bandLabel(canvas, p, bounds, g.left.toFloat(), g.slackTop.toFloat(), density, palette, notesLabel)
        }
        return bmp
    }

    /**
     * One day cell, the shape og's `drawDayCell` has: the Sun/Mon/… label centred at the top (Week
     * only), the number below-left, **today's ring around that number**, and the hairline under the
     * number row. Month and Week share it so the ring arithmetic exists exactly once.
     */
    private fun dayCell(
        canvas: Canvas, p: Paint, bounds: Rect,
        left: Float, top: Float, cellW: Float,
        date: LocalDate, showDayOfWeek: Boolean, inMonth: Boolean, today: LocalDate,
        density: Float, palette: Palette, hp: Float,
    ) {
        val pad = 5f * density
        val dowAreaH = if (showDayOfWeek) 16f * density else 0f
        if (showDayOfWeek) {
            val name = CalendarDates.DAY_NAMES[date.dayOfWeek.value % 7]
            p.style = Paint.Style.FILL; p.color = palette.ink
            p.textSize = 11f * density; p.textAlign = Paint.Align.CENTER
            p.getTextBounds(name, 0, name.length, bounds)
            canvas.drawText(name, left + cellW / 2f, top + pad - bounds.top, p)
        }

        val dayStr = date.dayOfMonth.toString()
        p.style = Paint.Style.FILL; p.textSize = 13f * density; p.textAlign = Paint.Align.LEFT
        p.getTextBounds(dayStr, 0, dayStr.length, bounds)
        val numW = bounds.width(); val numH = bounds.height()
        val numTop = top + pad + dowAreaH
        val baseline = numTop - bounds.top
        if (date == today) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * density; p.color = palette.ink
            canvas.drawCircle(left + pad + numW / 2f, numTop + numH / 2f, numH * 1.1f, p)
            p.strokeWidth = 0f; p.style = Paint.Style.FILL
        }
        p.color = if (inMonth) palette.ink else palette.light
        canvas.drawText(dayStr, left + pad, baseline, p)
        // The hairline under the number row — og's cell divider, at the cell's own hairline.
        p.color = palette.ink
        val dividerY = (numTop + numH + pad).toInt()
        canvas.drawRect(left, dividerY.toFloat(), left + cellW, dividerY + hp, p)
    }

    /** A band's label — light ink, because it names the paper rather than saying anything on it. */
    private fun bandLabel(
        canvas: Canvas, p: Paint, bounds: Rect, left: Float, top: Float,
        density: Float, palette: Palette, label: String,
    ) {
        p.style = Paint.Style.FILL; p.color = palette.light
        p.textSize = 14f * density; p.textAlign = Paint.Align.LEFT
        p.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(label, left + 8f * density, top + 4f * density - bounds.top, p)
    }

    private fun hline(canvas: Canvas, p: Paint, left: Int, top: Int, right: Int, hp: Float) {
        p.style = Paint.Style.FILL
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), top + hp, p)
    }

    private fun vline(canvas: Canvas, p: Paint, left: Int, top: Int, bottom: Int, hp: Float) {
        p.style = Paint.Style.FILL
        canvas.drawRect(left.toFloat(), top.toFloat(), left + hp, bottom.toFloat(), p)
    }
}
