package com.notesprout.android.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders the calendar grid / timeline for a view as an off-screen bitmap that sits *behind* the
 * ink as the page template (exactly like a notebook ruling template). Also provides finger
 * hit-testing from a tap point back to a [LocalDate] using the identical geometry constants.
 *
 * The template is transparent except for the grid lines / labels — the drawing surface provides the
 * white page background. Coordinates are in canvas pixels (the full content area below the toolbar).
 *
 * Month & Week reserve a "Notes" band at the bottom (square month cells determine its height); the
 * whole surface — grid and notes band alike — is writable.
 */
object CalendarTemplateRenderer {

    enum class CalView { MONTH, WEEK, DAY }

    /** Everything needed to render or hit-test a single calendar page. */
    data class Spec(
        val view: CalView,
        val calYear: Int,
        val calMonth: Int,
        val selectedDate: LocalDate,
        val dayHalf: Int,            // 0 = AM (00–12), 1 = PM (12–24)
        val today: LocalDate = LocalDate.now(),
    )

    private const val DOW_HEADER_DP = 40f
    private const val DAY_GUTTER_DP = 80f   // day-view time-label gutter

    /**
     * Renders the grid/timeline. [highlights] draws the filled "today" circle and the selected-day
     * border (true for the live calendar canvas); pass false when baking a template for export so a
     * transient date highlight isn't frozen permanently onto a notebook page.
     */
    fun render(spec: Spec, widthPx: Int, heightPx: Int, density: Float, highlights: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        when (spec.view) {
            CalView.MONTH -> drawMonth(canvas, spec, widthPx, heightPx, density, highlights)
            CalView.WEEK  -> drawWeek(canvas, spec, widthPx, heightPx, density, highlights)
            CalView.DAY   -> drawDay(canvas, spec, widthPx, heightPx, density)
        }
        return bmp
    }

    /** Maps a finger tap to a date (month/week only). Returns null for the day view, notes band, or empty hits. */
    fun hitTest(spec: Spec, x: Float, y: Float, widthPx: Int, heightPx: Int, density: Float): LocalDate? {
        when (spec.view) {
            CalView.MONTH -> {
                val g = monthGeometry(widthPx, heightPx, density)
                if (y < g.gridTop || y >= g.gridTop + g.gridH) return null
                val col = (x / g.pitchX).toInt().coerceIn(0, 6)
                val row = ((y - g.gridTop) / g.pitchY).toInt().coerceIn(0, 5)
                return firstCellOf(spec.calYear, spec.calMonth).plusDays((row * 7 + col).toLong())
            }
            CalView.WEEK -> {
                val g = weekGeometry(widthPx, heightPx, density)
                if (y >= g.cellsAreaH) return null
                val col = (x / g.pitchX).toInt().coerceIn(0, 3)
                val row = (y / g.pitchY).toInt().coerceIn(0, 1)
                val idx = row * 4 + col
                if (idx >= 7) return null
                return sundayOf(spec.selectedDate).plusDays(idx.toLong())
            }
            CalView.DAY -> return null
        }
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    private class MonthGeo(
        val headerH: Float, val pitchX: Float, val pitchY: Float, val cellW: Float, val cellH: Float,
        val gridTop: Float, val gridH: Float, val notesTop: Float, val notesH: Float,
    )

    private fun monthGeometry(w: Int, h: Int, d: Float): MonthGeo {
        val headerH = DOW_HEADER_DP * d
        // Square cells from full width (6 one-pixel column dividers between 7 columns).
        val cellW = (w - 6) / 7f
        val cellH = cellW
        val pitchX = cellW + 1f
        val pitchY = cellH + 1f
        val gridH = 6 * cellH + 5f          // 6 rows + 5 inter-row dividers
        val gridTop = headerH + 1f          // header + its bottom divider
        val notesTop = gridTop + gridH + 1f // grid + divider above notes
        val notesH = (h - notesTop).coerceAtLeast(0f)
        return MonthGeo(headerH, pitchX, pitchY, cellW, cellH, gridTop, gridH, notesTop, notesH)
    }

    private class WeekGeo(
        val pitchX: Float, val pitchY: Float, val cellW: Float, val cellH: Float,
        val cellsAreaH: Float, val notesTop: Float, val notesH: Float,
    )

    private fun weekGeometry(w: Int, h: Int, d: Float): WeekGeo {
        // Notes band height matches the month view so both feel identical.
        val notesH = monthGeometry(w, h, d).notesH
        val cellsAreaH = if (notesH > 0f) (h - notesH - 1f) else h.toFloat()  // -1 for divider above notes
        val cellW = (w - 3) / 4f            // 3 column dividers between 4 columns
        val cellH = (cellsAreaH - 1f) / 2f  // 2 rows + 1 inter-row divider
        return WeekGeo(cellW + 1f, cellH + 1f, cellW, cellH, cellsAreaH, cellsAreaH + 1f, notesH)
    }

    // ── Month ──────────────────────────────────────────────────────────────────

    private fun drawMonth(canvas: Canvas, spec: Spec, w: Int, h: Int, d: Float, highlights: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = monthGeometry(w, h, d)

        // Day-of-week header labels + dividers
        p.color = Color.BLACK; p.style = Paint.Style.FILL
        p.textSize = 11f * d; p.textAlign = Paint.Align.CENTER
        val bounds = Rect()
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, name ->
            p.getTextBounds(name, 0, name.length, bounds)
            canvas.drawText(name, g.pitchX * i + g.cellW / 2f, g.headerH / 2f - bounds.exactCenterY(), p)
        }
        drawHLine(canvas, p, 0f, g.headerH, w.toFloat())
        for (c in 1 until 7) drawVLine(canvas, p, g.pitchX * c - 1f, 0f, g.headerH)

        val firstCell = firstCellOf(spec.calYear, spec.calMonth)
        for (row in 0 until 6) {
            for (col in 0 until 7) {
                val date = firstCell.plusDays((row * 7 + col).toLong())
                val inMonth = date.year == spec.calYear && date.monthValue == spec.calMonth
                val left = g.pitchX * col
                val top = g.gridTop + g.pitchY * row
                drawDayCell(canvas, p, left, top, g.cellW, g.cellH, date, false, inMonth, spec.selectedDate, spec.today, d, highlights)
                if (col > 0) drawVLine(canvas, p, left - 1f, g.gridTop, g.gridTop + g.gridH)
            }
            if (row > 0) drawHLine(canvas, p, 0f, g.gridTop + g.pitchY * row - 1f, w.toFloat())
        }

        if (g.notesH > 0f) {
            drawHLine(canvas, p, 0f, g.notesTop - 1f, w.toFloat())
            drawNotesLabel(canvas, p, g.notesTop, d)
        }
    }

    // ── Week ───────────────────────────────────────────────────────────────────

    private fun drawWeek(canvas: Canvas, spec: Spec, w: Int, h: Int, d: Float, highlights: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = weekGeometry(w, h, d)
        val weekStart = sundayOf(spec.selectedDate)

        for (row in 0 until 2) {
            for (col in 0 until 4) {
                val idx = row * 4 + col
                val left = g.pitchX * col
                val top = g.pitchY * row
                if (idx < 7) {
                    val date = weekStart.plusDays(idx.toLong())
                    drawDayCell(canvas, p, left, top, g.cellW, g.cellH, date, true, true, spec.selectedDate, spec.today, d, highlights)
                }
                if (col > 0) drawVLine(canvas, p, left - 1f, 0f, g.cellsAreaH)
            }
            if (row > 0) drawHLine(canvas, p, 0f, g.pitchY * row - 1f, w.toFloat())
        }

        if (g.notesH > 0f) {
            drawHLine(canvas, p, 0f, g.cellsAreaH, w.toFloat())
            drawNotesLabel(canvas, p, g.notesTop, d)
        }
    }

    // ── Day ────────────────────────────────────────────────────────────────────

    private fun drawDay(canvas: Canvas, spec: Spec, w: Int, h: Int, d: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val gutter = DAY_GUTTER_DP * d
        val rowH = h / 24f
        val startHour = if (spec.dayHalf == 0) 0 else 12

        p.color = Color.BLACK; p.style = Paint.Style.FILL
        p.textSize = 11f * d; p.textAlign = Paint.Align.LEFT
        val bounds = Rect()
        for (slot in 0 until 24) {
            val hour = startHour + slot / 2
            val minute = if (slot % 2 == 0) 0 else 30
            val top = rowH * slot
            val label = slotLabel(hour, minute)
            p.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, 12f * d, top + rowH / 2f - bounds.exactCenterY(), p)
            if (slot > 0) drawHLine(canvas, p, 0f, top, w.toFloat())
        }
        drawVLine(canvas, p, gutter, 0f, h.toFloat())
    }

    // ── Shared cell / notes drawing ──────────────────────────────────────────

    private fun drawNotesLabel(canvas: Canvas, p: Paint, notesTop: Float, d: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.GRAY
        p.textSize = 14f * d
        p.textAlign = Paint.Align.LEFT
        val bounds = Rect()
        p.getTextBounds("Notes", 0, 5, bounds)
        canvas.drawText("Notes", 8f * d, notesTop + 4f * d - bounds.top, p)
    }

    private fun drawDayCell(
        canvas: Canvas, p: Paint,
        left: Float, top: Float, cellW: Float, cellH: Float,
        date: LocalDate, showDayOfWeek: Boolean, inMonth: Boolean,
        selectedDate: LocalDate, today: LocalDate, d: Float, highlights: Boolean = true,
    ) {
        val isToday = highlights && date == today
        val isSel = highlights && date == selectedDate
        val topPad = 5f * d
        val leftPad = 5f * d
        val bounds = Rect()

        val numSize = 13f * d
        val dayStr = date.dayOfMonth.toString()
        p.style = Paint.Style.FILL
        p.textSize = numSize
        p.textAlign = Paint.Align.LEFT
        p.getTextBounds(dayStr, 0, dayStr.length, bounds)
        val numW = bounds.width()
        val numH = bounds.height()

        val dowAreaH = if (showDayOfWeek) 16f * d else 0f
        val numTopEdge = top + topPad + dowAreaH
        val numBaseline = numTopEdge - bounds.top
        val numCircleCx = left + leftPad + numW / 2f
        val numCircleCy = numTopEdge + numH / 2f

        if (isSel) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f * d
            p.color = Color.BLACK
            val inset = 2f * d
            canvas.drawRect(left + inset, top + inset, left + cellW - inset, top + cellH - inset, p)
            p.strokeWidth = 0f
        }

        if (showDayOfWeek) {
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            p.textSize = 11f * d
            p.textAlign = Paint.Align.CENTER
            val dowLabel = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            p.getTextBounds(dowLabel, 0, dowLabel.length, bounds)
            canvas.drawText(dowLabel, left + cellW / 2f, top + topPad - bounds.top, p)
        }

        if (isToday) {
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            canvas.drawCircle(numCircleCx, numCircleCy, numH * 1.1f, p)
        }

        p.style = Paint.Style.FILL
        p.textSize = numSize
        p.textAlign = Paint.Align.LEFT
        p.color = when {
            isToday -> Color.WHITE
            !inMonth -> Color.GRAY
            else -> Color.BLACK
        }
        canvas.drawText(dayStr, left + leftPad, numBaseline, p)

        val dividerY = numTopEdge + numH + topPad
        p.color = Color.BLACK
        canvas.drawRect(left, dividerY, left + cellW, dividerY + 1f, p)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun drawHLine(canvas: Canvas, p: Paint, x: Float, y: Float, right: Float) {
        p.style = Paint.Style.FILL; p.color = Color.BLACK
        canvas.drawRect(x, y, right, y + 1f, p)
    }

    private fun drawVLine(canvas: Canvas, p: Paint, x: Float, top: Float, bottom: Float) {
        p.style = Paint.Style.FILL; p.color = Color.BLACK
        canvas.drawRect(x, top, x + 1f, bottom, p)
    }

    private fun firstCellOf(year: Int, month: Int): LocalDate {
        val firstDay = YearMonth.of(year, month).atDay(1)
        val offset = firstDay.dayOfWeek.value % 7   // Sunday = 0
        return firstDay.minusDays(offset.toLong())
    }

    private fun sundayOf(date: LocalDate): LocalDate {
        val dow = date.dayOfWeek.value % 7
        return date.minusDays(dow.toLong())
    }

    private fun slotLabel(hour: Int, minute: Int): String {
        val h12 = when (val hh = hour % 12) { 0 -> 12; else -> hh }
        val amPm = if (hour < 12) "AM" else "PM"
        val min = if (minute == 0) "00" else "30"
        return "$h12:$min $amPm"
    }
}
