package com.notesprout.android

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * A clean, e-ink-styled date picker shared by the calendar and the day window (replacing the native
 * [android.app.DatePickerDialog], whose coloured header reads wrong on e-ink). Bordered dialog, no
 * elevation, monochrome.
 *
 * Two modes in one dialog: a **day grid** (Sun–Sat month, prev/next = month) and — by tapping the
 * header title — a **month/year chooser** (prev/next = year, 3×4 month grid). Tapping a day picks it
 * and dismisses; the initially-passed day is a filled black circle, today a stroked ring.
 */
object DayPickerDialog {

    private val WEEKDAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S") // Sunday-first, matches grid

    fun show(activity: Activity, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_day_picker, null)
        val title = view.findViewById<TextView>(R.id.tvPickerMonthYear)
        val grid = view.findViewById<LinearLayout>(R.id.llDayGrid)
        val today = LocalDate.now()
        var shown = YearMonth.of(initial.year, initial.monthValue)
        var monthMode = false // false = day grid; true = month/year chooser
        var dlg: AlertDialog? = null

        fun renderDayGrid() {
            title.text = "${shown.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${shown.year}"
            grid.removeAllViews()
            grid.addView(weekdayHeader(activity))

            val lead = shown.atDay(1).dayOfWeek.value % 7 // ISO Mon=1..Sun=7 → Sunday-first blank count
            val daysInMonth = shown.lengthOfMonth()
            var dayNum = 1
            for (week in 0 until 6) {
                val row = weekRow(activity)
                for (col in 0 until 7) {
                    if (week * 7 + col < lead || dayNum > daysInMonth) {
                        row.addView(spacerCell(activity))
                    } else {
                        val date = shown.atDay(dayNum)
                        row.addView(
                            dayCell(activity, date, selected = date == initial, isToday = date == today) {
                                onPicked(date); dlg?.dismiss()
                            },
                        )
                        dayNum++
                    }
                }
                grid.addView(row)
                if (dayNum > daysInMonth) break // no trailing empty weeks
            }
        }

        fun renderMonthChooser() {
            title.text = shown.year.toString()
            grid.removeAllViews()
            for (r in 0 until 4) {
                val row = weekRow(activity)
                for (c in 0 until 3) {
                    val monthValue = r * 3 + c + 1
                    row.addView(
                        monthCell(activity, Month.of(monthValue), selected = monthValue == shown.monthValue) {
                            shown = YearMonth.of(shown.year, monthValue)
                            monthMode = false
                            renderDayGrid()
                        },
                    )
                }
                grid.addView(row)
            }
        }

        fun render() = if (monthMode) renderMonthChooser() else renderDayGrid()

        title.setOnClickListener { monthMode = !monthMode; render() }
        view.findViewById<View>(R.id.btnPrevMonth).setOnClickListener {
            shown = if (monthMode) shown.minusYears(1) else shown.minusMonths(1); render()
        }
        view.findViewById<View>(R.id.btnNextMonth).setOnClickListener {
            shown = if (monthMode) shown.plusYears(1) else shown.plusMonths(1); render()
        }
        render()

        dlg = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    // ── Cell builders ────────────────────────────────────────────────────────────

    private fun weekRow(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun weekdayHeader(activity: Activity): LinearLayout = weekRow(activity).apply {
        for (label in WEEKDAY_LABELS) {
            addView(TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(color(activity, R.color.inkLight))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { bottomMargin = dp(activity, 4) }
            })
        }
    }

    /** An empty weight-1 grid slot (leading/trailing blanks). */
    private fun spacerCell(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 1f)
    }

    /** A weight-1 slot holding a centred fixed-size square, so the selected/today background is a
     *  true circle rather than an ellipse stretched to the cell width. */
    private fun dayCell(
        activity: Activity, date: LocalDate, selected: Boolean, isToday: Boolean, onClick: () -> Unit,
    ): View {
        val size = dp(activity, 40)
        val label = TextView(activity).apply {
            text = date.dayOfMonth.toString()
            gravity = Gravity.CENTER
            textSize = 15f
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            when {
                selected -> {
                    setBackgroundResource(R.drawable.bg_day_selected)
                    setTextColor(color(activity, R.color.paperWhite))
                    setTypeface(typeface, Typeface.BOLD)
                }
                isToday -> {
                    setBackgroundResource(R.drawable.bg_day_today)
                    setTextColor(color(activity, R.color.inkBlack))
                }
                else -> setTextColor(color(activity, R.color.inkBlack))
            }
        }
        return FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 1f)
            addView(label)
            setOnClickListener { onClick() }
        }
    }

    private fun monthCell(
        activity: Activity, month: Month, selected: Boolean, onClick: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        gravity = Gravity.CENTER
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply {
            val m = dp(activity, 3); setMargins(m, m, m, m)
        }
        if (selected) {
            setBackgroundResource(R.drawable.bg_month_selected)
            setTextColor(color(activity, R.color.paperWhite))
            setTypeface(typeface, Typeface.BOLD)
        } else {
            setBackgroundResource(R.drawable.shape_bordered)
            setTextColor(color(activity, R.color.inkBlack))
        }
        setOnClickListener { onClick() }
    }

    private fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun color(activity: Activity, res: Int): Int = ContextCompat.getColor(activity, res)
}
