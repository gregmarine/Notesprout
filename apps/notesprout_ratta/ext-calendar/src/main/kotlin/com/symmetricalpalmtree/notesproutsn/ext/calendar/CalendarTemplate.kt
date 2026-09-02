package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate

/**
 * Paints a calendar page's grid as the page **template** (arc 23 / Y1 — Month): a transparent
 * ARGB_8888 bitmap at the page's own size that g-paper sets behind the ink, so grid and ink scale
 * and register as one. Geometry is [CalendarGeometry]'s — nothing is measured here that the
 * hit-test does not also read.
 *
 * og's Month verbatim: Sun–Sat header, six rows of seven square cells, day numbers top-left with a
 * hairline under the number row, out-of-month numbers in the light ink, **today's number ringed**
 * (the one mark — nothing selects), and the Notes band below with its label. Names come from
 * [CalendarDates]' hand lists, never a formatter. Hairlines are filled rects `hairline` px thick on
 * the integer edges the geometry names.
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
        val numSize = 13f * density
        val pad = 5f * density
        for (r in 0 until 6) {
            for (c in 0 until 7) {
                val date = firstCell.plusDays((r * 7 + c).toLong())
                val inMonth = date.month == monthStart.month && date.year == monthStart.year
                val left = g.cellLeft(c).toFloat()
                val top = g.cellTop(r).toFloat()
                val dayStr = date.dayOfMonth.toString()
                p.style = Paint.Style.FILL; p.textSize = numSize; p.textAlign = Paint.Align.LEFT
                p.getTextBounds(dayStr, 0, dayStr.length, bounds)
                val numW = bounds.width(); val numH = bounds.height()
                val numTop = top + pad
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
                canvas.drawRect(left, dividerY.toFloat(), left + g.cell, dividerY + hp, p)
            }
        }

        // ── The Notes band.
        if (g.notesHeight > 0) {
            hline(canvas, p, g.left, g.notesTop - g.hairline, g.contentRight, hp)
            p.style = Paint.Style.FILL; p.color = palette.light
            p.textSize = 14f * density; p.textAlign = Paint.Align.LEFT
            p.getTextBounds(notesLabel, 0, notesLabel.length, bounds)
            canvas.drawText(notesLabel, g.left + 8f * density, g.notesTop + 4f * density - bounds.top, p)
        }
        return bmp
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
