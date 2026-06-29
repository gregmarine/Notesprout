package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.notesprout.android.databinding.ActivityCalendarBinding
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class CalendarActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_NOTEBOOK_ID   = "notebook_id"
        private const val EXTRA_NOTEBOOK_NAME = "notebook_name"

        fun launch(context: Context) {
            context.startActivity(Intent(context, CalendarActivity::class.java))
        }

        fun launchFromNotebook(context: Context, notebookId: String, notebookName: String) {
            context.startActivity(
                Intent(context, CalendarActivity::class.java)
                    .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                    .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
            )
        }
    }

    private enum class CalView { MONTH, WEEK, DAY }

    private lateinit var binding: ActivityCalendarBinding

    // Navigation state
    private var currentView = CalView.MONTH
    private var selectedDate: LocalDate = LocalDate.now()
    private var calYear: Int = LocalDate.now().year
    private var calMonth: Int = LocalDate.now().monthValue
    private var dayHalf = 0  // 0 = AM (midnight–noon), 1 = PM (noon–midnight)

    // Swipe tracking for month/week views (activity-level, works across child cells)
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    // All DayCells for the current grid — invalidated on selection change
    private val activeCells = mutableListOf<DayCell>()

    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val today = LocalDate.now()
        selectedDate = today
        calYear = today.year
        calMonth = today.monthValue
        dayHalf = if (LocalTime.now().hour >= 12) 1 else 0

        setupToolbar()
        renderView()
    }

    // ── Swipe Navigation (month/week) ─────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (currentView == CalView.MONTH || currentView == CalView.WEEK) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { swipeDownX = ev.x; swipeDownY = ev.y }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - swipeDownX
                    val dy = ev.y - swipeDownY
                    if (abs(dx) >= dp(60) && abs(dx) > abs(dy) * 1.5f) {
                        if (dx < 0) stepForward() else stepBack()
                        return true  // consume UP so child click doesn't also fire
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMonthView.setOnClickListener { switchView(CalView.MONTH) }
        binding.btnWeekView.setOnClickListener { switchView(CalView.WEEK) }
        binding.btnDayView.setOnClickListener { switchView(CalView.DAY) }
        binding.btnToday.setOnClickListener { goToToday() }
        binding.btnPrev.setOnClickListener { stepBack() }
        binding.btnNext.setOnClickListener { stepForward() }
        binding.tvMonthYear.setOnClickListener { showMonthYearPicker() }
        syncViewToggles()
        updateMonthYearLabel()
    }

    private fun syncViewToggles() {
        binding.btnMonthView.isSelected = currentView == CalView.MONTH
        binding.btnWeekView.isSelected  = currentView == CalView.WEEK
        binding.btnDayView.isSelected   = currentView == CalView.DAY
    }

    private fun updateMonthYearLabel() {
        binding.tvMonthYear.text = if (currentView == CalView.DAY) {
            val monthName = Month.of(selectedDate.monthValue).getDisplayName(TextStyle.FULL, Locale.getDefault())
            val half = if (dayHalf == 0) "AM" else "PM"
            "${selectedDate.dayOfMonth} $monthName ${selectedDate.year} ($half)"
        } else {
            val monthName = Month.of(calMonth).getDisplayName(TextStyle.FULL, Locale.getDefault())
            "$monthName $calYear"
        }
    }

    private fun switchView(view: CalView) {
        if (currentView == view) return
        currentView = view
        calYear  = selectedDate.year
        calMonth = selectedDate.monthValue
        syncViewToggles()
        updateMonthYearLabel()
        renderView()
    }

    private fun stepBack() {
        when (currentView) {
            CalView.MONTH -> {
                calMonth--
                if (calMonth < 1) { calMonth = 12; calYear-- }
            }
            CalView.WEEK -> {
                selectedDate = selectedDate.minusDays(7)
                calYear = selectedDate.year; calMonth = selectedDate.monthValue
            }
            CalView.DAY -> {
                selectedDate = selectedDate.minusDays(1)
                calYear = selectedDate.year; calMonth = selectedDate.monthValue
            }
        }
        updateMonthYearLabel()
        renderView()
    }

    private fun stepForward() {
        when (currentView) {
            CalView.MONTH -> {
                calMonth++
                if (calMonth > 12) { calMonth = 1; calYear++ }
            }
            CalView.WEEK -> {
                selectedDate = selectedDate.plusDays(7)
                calYear = selectedDate.year; calMonth = selectedDate.monthValue
            }
            CalView.DAY -> {
                selectedDate = selectedDate.plusDays(1)
                calYear = selectedDate.year; calMonth = selectedDate.monthValue
            }
        }
        updateMonthYearLabel()
        renderView()
    }

    private fun goToToday() {
        val today = LocalDate.now()
        selectedDate = today
        calYear  = today.year
        calMonth = today.monthValue
        updateMonthYearLabel()
        renderView()
    }

    // ── View Rendering ────────────────────────────────────────────────────────────

    private fun renderView() {
        binding.calendarContent.removeAllViews()
        activeCells.clear()
        binding.calendarContent.addView(
            when (currentView) {
                CalView.MONTH -> buildMonthView()
                CalView.WEEK  -> buildWeekView()
                CalView.DAY   -> buildDayView()
            }
        )
    }

    private fun onDayTapped(date: LocalDate) {
        selectedDate = date
        for (cell in activeCells) cell.invalidate()
    }

    // ── Month View ────────────────────────────────────────────────────────────────

    private fun buildMonthView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = frameMatch()
        }

        // Day-of-week header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(40))
        }
        for ((i, name) in listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat").withIndex()) {
            if (i > 0) header.addView(vDivider())
            header.addView(headerLabel(name))
        }
        root.addView(header)
        root.addView(hDivider())

        // 6-row × 7-column grid
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        val ym = YearMonth.of(calYear, calMonth)
        val firstDay = ym.atDay(1)
        val offset = firstDay.dayOfWeek.value % 7   // 0 = Sunday … 6 = Saturday
        val firstCell = firstDay.minusDays(offset.toLong())

        for (row in 0 until 6) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            for (col in 0 until 7) {
                if (col > 0) rowView.addView(vDivider())
                val date = firstCell.plusDays((row * 7 + col).toLong())
                val inCurrentMonth = date.year == calYear && date.monthValue == calMonth
                val cell = DayCell(this, date, showDayOfWeek = false).apply {
                    layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                    inMonth = inCurrentMonth
                    setOnClickListener {
                        selectedDate = date
                        if (!inCurrentMonth) {
                            calYear  = date.year
                            calMonth = date.monthValue
                            updateMonthYearLabel()
                            renderView()
                        } else {
                            for (cell in activeCells) cell.invalidate()
                        }
                    }
                }
                activeCells.add(cell)
                rowView.addView(cell)
            }
            grid.addView(rowView)
            if (row < 5) grid.addView(hDivider())
        }

        root.addView(grid)
        return root
    }

    // ── Week View ─────────────────────────────────────────────────────────────────

    private fun buildWeekView(): View {
        val weekStart = sundayOf(selectedDate)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = frameMatch()
        }

        // 2 rows × 4 cols; 7 day cells + 1 blank
        for (row in 0 until 2) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            for (col in 0 until 4) {
                if (col > 0) rowView.addView(vDivider())
                val idx = row * 4 + col
                if (idx < 7) {
                    val date = weekStart.plusDays(idx.toLong())
                    val cell = DayCell(this, date, showDayOfWeek = true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                        inMonth = true
                        setOnClickListener { onDayTapped(date) }
                    }
                    activeCells.add(cell)
                    rowView.addView(cell)
                } else {
                    // Blank filler cell
                    rowView.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
                    })
                }
            }
            root.addView(rowView)
            if (row == 0) root.addView(hDivider())
        }

        return root
    }

    // ── Day View ──────────────────────────────────────────────────────────────────

    private fun buildDayView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = frameMatch()
            setPadding(0, 0, 0, dp(16))
        }

        // 24 time slots — each takes equal weight to fill the remaining space
        val startHour = if (dayHalf == 0) 0 else 12
        for (slot in 0 until 24) {
            val hour   = startHour + slot / 2
            val minute = if (slot % 2 == 0) 0 else 30

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            row.addView(TextView(this).apply {
                text = slotLabel(hour, minute)
                textSize = 11f
                setTextColor(Color.BLACK)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(dp(80), MATCH)
            })
            row.addView(View(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(1, MATCH)
            })
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            })
            root.addView(row)
            root.addView(hDivider())
        }

        // Swipe navigates the linear AM→PM→next-day-AM timeline.
        // Swipe left = forward in time, swipe right = backward in time.
        // At the AM/PM boundary, crossing the edge advances or retreats a full day.
        // Return true on ACTION_DOWN to claim the gesture; without it ACTION_UP never arrives.
        val swipeThreshold = dp(60).toFloat()
        var downX = 0f
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; true }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    if (abs(dx) >= swipeThreshold) {
                        if (dx < 0) {
                            // Forward in time
                            if (dayHalf == 0) {
                                dayHalf = 1; updateMonthYearLabel(); renderView()
                            } else {
                                dayHalf = 0
                                selectedDate = selectedDate.plusDays(1)
                                calYear = selectedDate.year; calMonth = selectedDate.monthValue
                                updateMonthYearLabel(); renderView()
                            }
                        } else {
                            // Backward in time
                            if (dayHalf == 1) {
                                dayHalf = 0; updateMonthYearLabel(); renderView()
                            } else {
                                dayHalf = 1
                                selectedDate = selectedDate.minusDays(1)
                                calYear = selectedDate.year; calMonth = selectedDate.monthValue
                                updateMonthYearLabel(); renderView()
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }

        return root
    }

    // ── Month/Year Picker ─────────────────────────────────────────────────────────

    private fun showMonthYearPicker() {
        var pickerYear = calYear
        val view = layoutInflater.inflate(R.layout.dialog_month_year_picker, null)
        val tvYear = view.findViewById<TextView>(R.id.tvPickerYear)
        tvYear.text = pickerYear.toString()

        var dlg: AlertDialog? = null

        view.findViewById<AppCompatImageButton>(R.id.btnYearMinus).setOnClickListener {
            pickerYear--; tvYear.text = pickerYear.toString()
        }
        view.findViewById<AppCompatImageButton>(R.id.btnYearPlus).setOnClickListener {
            pickerYear++; tvYear.text = pickerYear.toString()
        }

        val monthIds = intArrayOf(
            R.id.btnPickMonth01, R.id.btnPickMonth02, R.id.btnPickMonth03,
            R.id.btnPickMonth04, R.id.btnPickMonth05, R.id.btnPickMonth06,
            R.id.btnPickMonth07, R.id.btnPickMonth08, R.id.btnPickMonth09,
            R.id.btnPickMonth10, R.id.btnPickMonth11, R.id.btnPickMonth12,
        )
        // Highlight the currently-displayed month
        view.findViewById<AppCompatButton>(monthIds[calMonth - 1]).isSelected = true

        monthIds.forEachIndexed { i, id ->
            view.findViewById<AppCompatButton>(id).setOnClickListener {
                calYear      = pickerYear
                calMonth     = i + 1
                selectedDate = LocalDate.of(calYear, calMonth, 1)
                updateMonthYearLabel()
                renderView()
                dlg?.dismiss()
            }
        }

        dlg = AlertDialog.Builder(this)
            .setTitle("Select Month & Year")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.window?.setElevation(0f)
        dlg.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
    }

    // ── DayCell Custom View ────────────────────────────────────────────────────────

    inner class DayCell(
        context: Context,
        val date: LocalDate,
        val showDayOfWeek: Boolean,
    ) : View(context) {

        var inMonth = true

        private val today = LocalDate.now()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = Rect()

        init { setWillNotDraw(false) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val isToday = date == today
            val isSel   = date == selectedDate
            val d = density

            val topPad  = 5f * d
            val leftPad = 5f * d

            // Measure day number — upper-left in both views
            val numSize = 13f * d
            val dayStr  = date.dayOfMonth.toString()
            p.textSize  = numSize
            p.textAlign = Paint.Align.LEFT
            p.getTextBounds(dayStr, 0, dayStr.length, bounds)
            val numW = bounds.width()
            val numH = bounds.height()  // always positive

            // In week view the number sits below the DOW header strip
            val dowAreaH  = if (showDayOfWeek) 16f * d else 0f
            val numTopEdge  = topPad + dowAreaH
            val numBaseline = numTopEdge - bounds.top   // bounds.top < 0 for most fonts
            val numCircleCx = leftPad + numW / 2f
            val numCircleCy = numTopEdge + numH / 2f

            // Selection border
            if (isSel) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f * d
                p.color = Color.BLACK
                val inset = 2f * d
                canvas.drawRect(inset, inset, width - inset, height - inset, p)
            }

            // Day-of-week header centered at top of cell (week view only)
            if (showDayOfWeek) {
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.textSize  = 11f * d
                p.textAlign = Paint.Align.CENTER
                val dowLabel = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                p.getTextBounds(dowLabel, 0, dowLabel.length, bounds)
                canvas.drawText(dowLabel, width / 2f, topPad - bounds.top, p)
            }

            // Today filled circle behind number
            if (isToday) {
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                canvas.drawCircle(numCircleCx, numCircleCy, numH * 1.1f, p)
            }

            // Day number in upper-left corner
            p.style  = Paint.Style.FILL
            p.textSize  = numSize
            p.textAlign = Paint.Align.LEFT
            p.color = when {
                isToday  -> Color.WHITE
                !inMonth -> Color.GRAY
                else     -> Color.BLACK
            }
            p.getTextBounds(dayStr, 0, dayStr.length, bounds)
            canvas.drawText(dayStr, leftPad, numBaseline, p)

            // Divider separating date header from writing area
            val dividerY = (numTopEdge + numH + topPad).toInt().toFloat()
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            canvas.drawRect(0f, dividerY, width.toFloat(), dividerY + 1f, p)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private fun frameMatch() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun hDivider() = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    }

    private fun vDivider() = View(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(1, MATCH)
    }

    private fun headerLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.BLACK)
        gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
    }

    /** Returns the Sunday that starts the week containing [date]. */
    private fun sundayOf(date: LocalDate): LocalDate {
        val dow = date.dayOfWeek.value % 7  // Sunday=0, Saturday=6
        return date.minusDays(dow.toLong())
    }

    /** Formats an hour (0–23) + minute (0 or 30) as "12:00 AM" etc. */
    private fun slotLabel(hour: Int, minute: Int): String {
        val h12 = when (val h = hour % 12) { 0 -> 12; else -> h }
        val amPm = if (hour < 12) "AM" else "PM"
        val min  = if (minute == 0) "00" else "30"
        return "$h12:$min $amPm"
    }
}
