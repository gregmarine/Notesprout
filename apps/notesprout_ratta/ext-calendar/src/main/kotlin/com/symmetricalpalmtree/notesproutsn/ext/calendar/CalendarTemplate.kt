package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate

/**
 * Paints a calendar page's grid as the page **template** (arc 23 — Month, Week and Day): a
 * transparent ARGB_8888 bitmap at the page's own size that g-paper sets behind the ink, so grid and
 * ink scale and register as one. Geometry is [CalendarGeometry]'s — nothing is measured here that
 * the hit-test does not also read.
 *
 * og's three layouts verbatim. Month: Sun–Sat header, six rows of seven square cells. Week: 2×4
 * cells, each with its Sun/Mon/… label above the number, and an eighth cell left blank. Both close
 * with the Notes band. Day: half-hour rows filling the page, their time labels in the left gutter —
 * no band. Day numbers sit top-left with a hairline under the number row, out-of-month numbers
 * take the light ink, and **today's number is ringed** — the one mark, because nothing selects; the
 * ring arithmetic lives in [dayCell] alone, so Month and Week can never draw it differently.
 *
 * **Events are drawn into the template too** (arc 24 / Z4), never as a live layer: they are as much
 * part of what the paper *says* as the ruling is, they must scale and register with the ink, and a
 * page that has not changed must not repaint. Month and Week take og's per-type glyphs on the
 * day-number row, right-aligned so the number keeps its corner ([GridMarks] does the arithmetic,
 * [drawGlyph] the ink); Day takes a right-aligned label inside the half-hour row an event lands in
 * ([DayRows]) — **the geometry does not change for them**, and the ink already on a row does not
 * move. Both are `palette.ink`: a mark on the page carries information, so it is black, and it is
 * made *small* rather than grey.
 *
 * Names come from [CalendarDates]' hand lists, never a formatter. Hairlines are filled rects
 * `hairline` px thick on the integer edges the geometry names.
 *
 * Re-baked on every navigation and on `onResume`, because "today" moves.
 */
object CalendarTemplate {

    /** The two inks, passed in from resources — the painter holds no colour of its own. */
    class Palette(val ink: Int, val light: Int)

    fun month(
        g: CalendarGeometry.Month,
        monthStart: LocalDate,
        today: LocalDate,
        density: Float,
        palette: Palette,
        notesLabel: String,
        marks: Map<LocalDate, List<DayMark>> = emptyMap(),
    ): Bitmap {
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
                    density = density, palette = palette, hp = hp, marks = marks[date].orEmpty(),
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
    fun week(
        g: CalendarGeometry.Week,
        sunday: LocalDate,
        today: LocalDate,
        density: Float,
        palette: Palette,
        notesLabel: String,
        marks: Map<LocalDate, List<DayMark>> = emptyMap(),
    ): Bitmap {
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
                if (index > 6) continue                       // the spare cell: blank paper, no marks
                val date = sunday.plusDays(index.toLong())
                dayCell(
                    canvas, p, bounds, g.cellLeft(c).toFloat(), g.cellTop(r).toFloat(), g.cellW.toFloat(),
                    date, showDayOfWeek = true, inMonth = true, today = today,
                    density = density, palette = palette, hp = hp, marks = marks[date].orEmpty(),
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
     * in the left gutter and the gutter's own hairline running the rows' height. **No Notes band and
     * no closing hairline** — the rows fill the page to the bottom bar, whose own top border closes
     * the ledger (Z5b, 2026-09-04).
     *
     * There is no header band: the page's title in the chrome already names the date and the half.
     *
     * [marks] are the whole day's, in `EventOrder.DAY` — [DayRows] decides which of them this half
     * shows and where. Their labels are drawn **after** every divider, so a hairline is never over
     * a word, right-aligned inside the row and never wider than half of it.
     */
    fun day(
        g: CalendarGeometry.Day,
        half: Int,
        density: Float,
        palette: Palette,
        marks: List<DayMark> = emptyList(),
    ): Bitmap {
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
            canvas.drawText(label, 12f * density, top + g.rowHeight(slot) / 2f - bounds.exactCenterY(), p)
            if (slot > 0) hline(canvas, p, g.left, g.rowDividerY(slot), g.right, hp)
        }
        vline(canvas, p, g.gutterLeft, g.rowsTop, g.rowsBottom, hp)

        // ── The events in the rows — the gutter labels' own size and centring, mirrored.
        val buckets = DayRows.bucket(marks, half)
        if (buckets.isNotEmpty()) {
            val maxWidth = DayRows.labelMaxWidth(g.gutterRight, g.right).toFloat()
            p.style = Paint.Style.FILL; p.color = palette.ink
            p.textSize = 11f * density; p.textAlign = Paint.Align.RIGHT
            for ((slot, entries) in buckets) {
                val label = fit(p, DayRows.label(entries), maxWidth)
                if (label.isEmpty()) continue
                p.getTextBounds(label, 0, label.length, bounds)
                canvas.drawText(label, g.right - 8f * density, g.rowTop(slot) + g.rowHeight(slot) / 2f - bounds.exactCenterY(), p)
            }
            p.textAlign = Paint.Align.LEFT
        }
        return bmp
    }

    /**
     * The note page's one template (arc 24 / Z5a): the "Notes" band label, so the paper half says
     * what it is the way the Month/Week Notes band does — nothing else on it, because a note has no
     * grid, no header, no cells. Prepared exactly as [month] prepares its bitmap.
     */
    fun note(width: Int, height: Int, density: Float, palette: Palette, label: String): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = Rect()
        bandLabel(canvas, p, bounds, 0f, 0f, density, palette, label)
        return bmp
    }

    /**
     * One day cell, the shape og's `drawDayCell` has: the Sun/Mon/… label centred at the top (Week
     * only), the number below-left, **today's ring around that number**, the hairline under the
     * number row, and — since arc 24 / Z4 — the day's **event glyphs right-aligned on that same
     * number row**. Month and Week share it so the ring arithmetic, and now the glyph arithmetic,
     * exist exactly once.
     */
    private fun dayCell(
        canvas: Canvas, p: Paint, bounds: Rect,
        left: Float, top: Float, cellW: Float,
        date: LocalDate, showDayOfWeek: Boolean, inMonth: Boolean, today: LocalDate,
        density: Float, palette: Palette, hp: Float, marks: List<DayMark>,
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
        val centreX = left + pad + numW / 2f
        val rowCy = numTop + numH / 2f
        val isToday = date == today
        if (isToday) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * density; p.color = palette.ink
            canvas.drawCircle(centreX, rowCy, numH * 1.1f, p)
            p.strokeWidth = 0f; p.style = Paint.Style.FILL
        }
        p.color = if (inMonth) palette.ink else palette.light
        canvas.drawText(dayStr, left + pad, baseline, p)
        // The hairline under the number row — og's cell divider, at the cell's own hairline.
        p.color = palette.ink
        val dividerY = (numTop + numH + pad).toInt()
        canvas.drawRect(left, dividerY.toFloat(), left + cellW, dividerY + hp, p)

        if (marks.isEmpty()) return
        // og's sizes: a slot is as tall as the number, held between 10 and 16 dp, and the row is
        // packed against the cell's right inset. The left edge it may not cross is the number's own
        // right edge — **except on today's cell, where the ring is wider than the number**, and a
        // glyph tucked against the digits would touch it.
        val iconSize = numH.toFloat().coerceIn(10f * density, 16f * density)
        val gap = 3f * density
        val rightEdge = left + cellW - 5f * density
        val numRight = if (isToday) centreX + numH * 1.1f else left + pad + numW
        val slots = GridMarks.layout(GridMarks.distinct(marks), numRight + 4f * density, rightEdge, iconSize, gap)
        for (slot in slots) drawGlyph(canvas, p, slot.glyph, slot.x, rowCy - iconSize / 2f, iconSize, density, palette)
    }

    /**
     * og's per-type glyph in the box `[x, top, x + s, top + s]`, or the overflow **`+`** when
     * [glyph] is null — Canvas primitives only, because the template holds no `Context` and
     * therefore no drawable. Ink is `palette.ink`, the one colour a mark may take: it carries
     * information, so it is black and small rather than grey.
     *
     * The `Paint` is the caller's and is handed back the way it was found — `FILL`, zero stroke
     * width — because every cell after this one draws text with it.
     */
    private fun drawGlyph(canvas: Canvas, p: Paint, glyph: Glyph?, x: Float, y: Float, s: Float, density: Float, palette: Palette) {
        p.color = palette.ink
        val stroke = (1.4f * density).coerceAtLeast(1.5f)
        when (glyph) {
            Glyph.CAKE -> {
                p.style = Paint.Style.FILL
                // Two candles + flame dots.
                canvas.drawRect(x + 0.35f * s, y + 0.34f * s, x + 0.41f * s, y + 0.62f * s, p)
                canvas.drawRect(x + 0.59f * s, y + 0.34f * s, x + 0.65f * s, y + 0.62f * s, p)
                canvas.drawCircle(x + 0.38f * s, y + 0.26f * s, 0.06f * s, p)
                canvas.drawCircle(x + 0.62f * s, y + 0.26f * s, 0.06f * s, p)
                // Scalloped frosting top.
                canvas.drawCircle(x + 0.26f * s, y + 0.62f * s, 0.13f * s, p)
                canvas.drawCircle(x + 0.5f * s, y + 0.62f * s, 0.13f * s, p)
                canvas.drawCircle(x + 0.74f * s, y + 0.62f * s, 0.13f * s, p)
                // Body.
                canvas.drawRect(x + 0.13f * s, y + 0.62f * s, x + 0.87f * s, y + 0.92f * s, p)
            }
            Glyph.HEART -> {
                p.style = Paint.Style.FILL
                canvas.drawCircle(x + 0.32f * s, y + 0.36f * s, 0.2f * s, p)
                canvas.drawCircle(x + 0.68f * s, y + 0.36f * s, 0.2f * s, p)
                val path = Path().apply {
                    moveTo(x + 0.11f * s, y + 0.44f * s)
                    lineTo(x + 0.89f * s, y + 0.44f * s)
                    lineTo(x + 0.5f * s, y + 0.9f * s)
                    close()
                }
                canvas.drawPath(path, p)
            }
            Glyph.SUITCASE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = stroke
                canvas.drawRect(x + 0.34f * s, y + 0.14f * s, x + 0.66f * s, y + 0.34f * s, p)   // handle
                canvas.drawRect(x + 0.14f * s, y + 0.32f * s, x + 0.86f * s, y + 0.9f * s, p)    // body
                canvas.drawLine(x + 0.5f * s, y + 0.32f * s, x + 0.5f * s, y + 0.9f * s, p)
            }
            Glyph.PEOPLE -> {
                p.style = Paint.Style.FILL
                canvas.drawCircle(x + 0.33f * s, y + 0.34f * s, 0.16f * s, p)
                canvas.drawCircle(x + 0.67f * s, y + 0.34f * s, 0.16f * s, p)
                canvas.drawRoundRect(x + 0.13f * s, y + 0.56f * s, x + 0.53f * s, y + 0.95f * s, 0.12f * s, 0.12f * s, p)
                canvas.drawRoundRect(x + 0.47f * s, y + 0.56f * s, x + 0.87f * s, y + 0.95f * s, 0.12f * s, 0.12f * s, p)
            }
            Glyph.CLOCK -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = stroke
                canvas.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.4f * s, p)
                canvas.drawLine(x + 0.5f * s, y + 0.5f * s, x + 0.5f * s, y + 0.24f * s, p)
                canvas.drawLine(x + 0.5f * s, y + 0.5f * s, x + 0.7f * s, y + 0.56f * s, p)
            }
            Glyph.DOT -> {
                p.style = Paint.Style.FILL
                canvas.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.16f * s, p)
            }
            // The overflow slot: "and more", without claiming which.
            null -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = stroke
                canvas.drawLine(x + 0.5f * s, y + 0.28f * s, x + 0.5f * s, y + 0.72f * s, p)
                canvas.drawLine(x + 0.28f * s, y + 0.5f * s, x + 0.72f * s, y + 0.5f * s, p)
            }
        }
        p.style = Paint.Style.FILL
        p.strokeWidth = 0f
    }

    /**
     * [text] as much of it as fits [maxWidth] at [p]'s current size, with an ellipsis when it does
     * not — `Paint`'s own measuring, because `android.text` is not available at the JVM and this is
     * Canvas code anyway. Answers `""` when not even the ellipsis fits, and the caller draws
     * nothing rather than a smudge.
     */
    private fun fit(p: Paint, text: String, maxWidth: Float): String {
        if (maxWidth <= 0f || text.isEmpty()) return ""
        if (p.measureText(text) <= maxWidth) return text
        val room = maxWidth - p.measureText(ELLIPSIS)
        if (room <= 0f) return ""
        val kept = p.breakText(text, true, room, null)
        if (kept <= 0) return ""
        return text.substring(0, kept) + ELLIPSIS
    }

    private const val ELLIPSIS = "…"

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
