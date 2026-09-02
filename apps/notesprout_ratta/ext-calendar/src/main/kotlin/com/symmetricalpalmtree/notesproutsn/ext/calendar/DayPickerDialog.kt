package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import java.time.LocalDate

/**
 * "Which day?" — the pager title's dialog (arc 23 / Y2). The extension's own, in og's shape: a
 * Sun–Sat day grid whose prev/next step months, a header title that **flips the dialog to a 3×4
 * month chooser** whose prev/next step years, today ringed, the day you came in on filled black.
 * Tapping a day picks it and dismisses; Cancel does nothing.
 *
 * Rebuilt here rather than shared, because og's is `:app`'s and nothing of the host's crosses into
 * an extension. What it shows is [DayPickerModel]'s and is JVM-tested; this file is only views.
 *
 * **The cells are built in code** because a grid of 42 slots is not a layout, it is a loop. Each day
 * sits as a fixed square centred in a weight-1 slot whose height is `@dimen/toolbar_button_size` —
 * the family's one hand-sized tap target (44 dp; 62 dp on the sw720dp tier the Nomad and Manta sit
 * in), never a hardcoded size — so the ring and the fill are true circles rather than ellipses
 * stretched to whatever the screen made the column, and the cell keeps pace with every other
 * hand-sized control when that dimen is retuned.
 *
 * E-ink rules throughout: bordered dialog, no elevation, monochrome — "chosen" is a fill, "today" is
 * a ring, and nothing is greyed.
 */
object DayPickerDialog {

    fun show(activity: Activity, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_day_picker, null)
        val title = view.findViewById<TextView>(R.id.tvPickerMonthYear)
        val grid = view.findViewById<LinearLayout>(R.id.llDayGrid)
        val today = LocalDate.now()
        var shown = CalendarDates.monthStart(initial)
        var monthMode = false            // false = the day grid; true = the month chooser
        var dialog: AlertDialog? = null

        fun renderDays() {
            title.text = DayPickerModel.monthTitle(shown)
            grid.removeAllViews()
            grid.addView(weekdayHeader(activity))
            for (week in DayPickerModel.dayRows(shown)) {
                val row = gridRow(activity)
                for (day in week) {
                    row.addView(
                        if (day == null) spacerCell(activity)
                        else dayCell(activity, day, picked = day == initial, isToday = day == today) {
                            onPicked(day); dialog?.dismiss()
                        },
                    )
                }
                grid.addView(row)
            }
        }

        fun renderMonths() {
            title.text = DayPickerModel.yearTitle(shown.year)
            grid.removeAllViews()
            for (months in DayPickerModel.monthGrid()) {
                val row = gridRow(activity)
                for (month in months) {
                    row.addView(
                        monthCell(activity, month, picked = month == shown.monthValue) {
                            shown = shown.withDayOfMonth(1).withMonth(month)
                            monthMode = false
                            renderDays()
                        },
                    )
                }
                grid.addView(row)
            }
        }

        fun render() = if (monthMode) renderMonths() else renderDays()

        title.setOnClickListener { monthMode = !monthMode; render() }
        // One pair of arrows, two meanings — months in the day grid, years in the month chooser;
        // the title above them always says which, so the pair never has to be labelled twice.
        view.findViewById<View>(R.id.btnPrevMonth).apply {
            TooltipCompat.setTooltipText(this, contentDescription)   // every icon button names itself on a long-press
            setOnClickListener {
                shown = if (monthMode) shown.minusYears(1) else shown.minusMonths(1)
                render()
            }
        }
        view.findViewById<View>(R.id.btnNextMonth).apply {
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener {
                shown = if (monthMode) shown.plusYears(1) else shown.plusMonths(1)
                render()
            }
        }
        render()

        val created = Dialogs.style(
            AlertDialog.Builder(activity)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog = created
        created.show()
        // Three quarters of the screen, whatever the tier (the user's call at Y4): a full-width
        // dialog read as a page and its bordered background sat at the glass's edge, invisible. The
        // window is sized after show() — before it there is no window to size — and the weighted
        // cells then measure against this width rather than the screen's.
        created.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    /** The dialog's width as a fraction of the screen's. */
    private const val WIDTH_FRACTION = 0.75f

    // ── Cells ────────────────────────────────────────────────────────────────

    private fun gridRow(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** S M T W T F S — the one row that is not tappable. It still says something (which column is
     *  Sunday), so it takes ink black and is made *small* to read as secondary — `inkLight` is for
     *  text not meant to be read, and on e-ink it barely is. */
    private fun weekdayHeader(activity: Activity): LinearLayout = gridRow(activity).apply {
        for (letter in DayPickerModel.WEEKDAY_LETTERS) {
            addView(TextView(activity).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(color(activity, R.color.inkBlack))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { bottomMargin = dp(activity, 4) }
            })
        }
    }

    /** A leading or trailing blank: a slot that holds the column open and does nothing. */
    private fun spacerCell(activity: Activity): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, tapSize(activity), 1f)
    }

    private fun dayCell(
        activity: Activity, date: LocalDate, picked: Boolean, isToday: Boolean, onClick: () -> Unit,
    ): View {
        val size = tapSize(activity) - dp(activity, 4)   // the circle sits just inside its slot
        val label = TextView(activity).apply {
            text = date.dayOfMonth.toString()
            gravity = Gravity.CENTER
            textSize = 15f
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            when {
                picked -> {
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
            layoutParams = LinearLayout.LayoutParams(0, tapSize(activity), 1f)
            addView(label)
            setOnClickListener { onClick() }
        }
    }

    private fun monthCell(
        activity: Activity, month: Int, picked: Boolean, onClick: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = CalendarDates.MONTH_NAMES_SHORT[month - 1]
        gravity = Gravity.CENTER
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(0, tapSize(activity), 1f).apply {
            val m = dp(activity, 3)
            setMargins(m, m, m, m)
        }
        if (picked) {
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

    /** The one hand-sized tap target — `@dimen/toolbar_button_size`, never a number here. */
    private fun tapSize(activity: Activity): Int = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

    private fun color(activity: Activity, res: Int): Int = ContextCompat.getColor(activity, res)
}
