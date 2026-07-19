package com.notesprout.android

import android.app.Activity
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.EventPayload
import com.notesprout.android.data.events.EventType
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.events.MonthlyMode
import com.notesprout.android.data.events.RecurrenceRule
import com.notesprout.android.data.events.Reminder
import com.notesprout.android.data.events.ReminderUnit
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.databinding.DialogEventEditorBinding
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Add / edit editor for a calendar [EventEntity]. E-ink styled (bordered dialog, no elevation).
 * Builds the entity locally and hands it back via [onSaved]; [onDeleted] powers the Delete button
 * shown only when editing an existing event. For a *recurring* event the caller
 * ([EventsController]) prompts an edit/delete scope (this occurrence / this-and-following / all)
 * after the build — this editor is scope-agnostic and always returns the whole edited entity.
 */
object EventEditorDialog {

    private val REPEAT_LABELS = listOf("Does not repeat", "Daily", "Weekly", "Monthly", "Yearly")
    private val END_LABELS = listOf("Never", "On a date", "After a number of times")

    /**
     * [occurrenceStart] re-anchors the pre-filled start/end dates to the tapped occurrence of a
     * recurring event (its parent anchor is a different, possibly long-past date). Supplying it lets
     * the user *move* a single occurrence: an untouched date then equals that occurrence's own date,
     * so a real edit is a real move (see [EventsRepository.editOccurrence] /
     * [EventsRepository.editThisAndFollowing]). Null → pre-fill from [existing]'s own dates.
     */
    fun show(
        activity: Activity,
        date: LocalDate,
        existing: EventEntity?,
        onSaved: (EventEntity) -> Unit,
        onDeleted: ((EventEntity) -> Unit)? = null,
        occurrenceStart: Long? = null,
    ) {
        val b = DialogEventEditorBinding.inflate(activity.layoutInflater)
        val payload = existing?.let { EventPayload.fromJson(it.data) } ?: EventPayload()
        val rule = payload.recurrence
        val isNew = existing == null

        // ── Mutable working state ───────────────────────────────────────────────
        // For a recurring occurrence, anchor the shown dates on that occurrence (preserving the span),
        // not the series' parent anchor — so an untouched date stays put and an edited date moves it.
        val span = existing?.let { it.endEpochDay - it.startEpochDay } ?: 0L
        var startDate = occurrenceStart?.let { LocalDate.ofEpochDay(it) }
            ?: existing?.let { LocalDate.ofEpochDay(it.startEpochDay) } ?: date
        var endDate = occurrenceStart?.let { LocalDate.ofEpochDay(it + span) }
            ?: existing?.let { LocalDate.ofEpochDay(it.endEpochDay) } ?: date
        var startMinute: Int? = existing?.startMinute
        var endMinute: Int? = existing?.endMinute
        val weekdays = sortedSetOf<Int>().apply {
            addAll(rule?.weekdays?.map { it.coerceIn(1, 7) } ?: listOf(startDate.dayOfWeek.value))
            if (isEmpty()) add(startDate.dayOfWeek.value)
        }
        var monthlyMode = rule?.monthlyMode ?: MonthlyMode.DAY_OF_MONTH
        var untilDate: LocalDate = rule?.endEpochDay?.let { LocalDate.ofEpochDay(it) } ?: date.plusMonths(1)
        val reminders = payload.reminders.toMutableList()

        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

        // ── Adapters ────────────────────────────────────────────────────────────
        b.spType.attach(activity, EventType.entries.map { it.label })
        b.spRepeat.attach(activity, REPEAT_LABELS)
        b.spEnd.attach(activity, END_LABELS)
        b.spRemindUnit.attach(activity, listOf("days", "weeks"))

        // ── Formatting ──────────────────────────────────────────────────────────
        val timeFmt = DateFormat.getTimeFormat(activity)
        fun fmtDate(d: LocalDate): String = "${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${d.year}"
        fun fmtMin(m: Int): String {
            val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60) }
            return timeFmt.format(cal.time)
        }

        fun repeatFreq(): Freq? = when (b.spRepeat.selectedItemPosition) {
            1 -> Freq.DAILY; 2 -> Freq.WEEKLY; 3 -> Freq.MONTHLY; 4 -> Freq.YEARLY; else -> null
        }

        // ── Render current state into the views ───────────────────────────────────
        fun refresh() {
            b.btnStartDate.text = fmtDate(startDate)
            b.btnEndDate.text = fmtDate(endDate)
            b.grpTime.visibility = if (b.swAllDay.isChecked) View.GONE else View.VISIBLE
            b.btnStartTime.text = startMinute?.let { fmtMin(it) } ?: "—"
            b.btnEndTime.text = endMinute?.let { fmtMin(it) } ?: "None"

            val freq = repeatFreq()
            val repeats = freq != null
            b.grpEvery.visibility = if (repeats) View.VISIBLE else View.GONE
            b.grpWeekdays.visibility = if (freq == Freq.WEEKLY) View.VISIBLE else View.GONE
            b.grpMonthly.visibility = if (freq == Freq.MONTHLY) View.VISIBLE else View.GONE
            b.grpEndBlock.visibility = if (repeats) View.VISIBLE else View.GONE
            b.tvUnit.text = when (freq) {
                Freq.DAILY -> "days"; Freq.WEEKLY -> "weeks"; Freq.MONTHLY -> "months"
                Freq.YEARLY -> "years"; null -> ""
            }

            // Weekday toggles reflect the set (buttons ordered Mon..Sun = ISO 1..7).
            listOf(b.tglMon, b.tglTue, b.tglWed, b.tglThu, b.tglFri, b.tglSat, b.tglSun)
                .forEachIndexed { i, btn -> btn.isSelected = (i + 1) in weekdays }

            b.rbDayOfMonth.isSelected = monthlyMode == MonthlyMode.DAY_OF_MONTH
            b.rbOrdinal.isSelected = monthlyMode == MonthlyMode.ORDINAL_WEEKDAY
            b.rbDayOfMonth.text = "On day ${startDate.dayOfMonth}"
            b.rbOrdinal.text = "On the ${ordinalLabel(startDate)}"

            b.btnUntilDate.visibility = if (b.spEnd.selectedItemPosition == 1) View.VISIBLE else View.GONE
            b.grpCount.visibility = if (b.spEnd.selectedItemPosition == 2) View.VISIBLE else View.GONE
            b.btnUntilDate.text = fmtDate(untilDate)
        }

        // ── Initial selection (before listeners, so we don't clobber loaded values) ──
        b.spType.setSelection(EventType.entries.indexOf(EventType.fromName(existing?.type)).coerceAtLeast(0))
        b.spRepeat.setSelection(
            when (rule?.freq) {
                Freq.DAILY -> 1; Freq.WEEKLY -> 2; Freq.MONTHLY -> 3; Freq.YEARLY -> 4; null -> 0
            }
        )
        b.spEnd.setSelection(when (rule?.endMode) { EndMode.UNTIL -> 1; EndMode.COUNT -> 2; else -> 0 })
        b.swAllDay.isChecked = existing?.allDay ?: true
        b.etTitle.setText(existing?.title ?: "")
        b.etInterval.setText((rule?.interval ?: 1).coerceAtLeast(1).toString())
        b.etCount.setText((rule?.endCount ?: 10).coerceAtLeast(1).toString())

        // ── Listeners ─────────────────────────────────────────────────────────────
        b.spType.onSelect {
            if (isNew) {
                // Offer the type's natural default recurrence on a fresh event.
                val def = EventType.entries[b.spType.selectedItemPosition].defaultFreq
                b.spRepeat.setSelection(when (def) {
                    Freq.DAILY -> 1; Freq.WEEKLY -> 2; Freq.MONTHLY -> 3; Freq.YEARLY -> 4; null -> 0
                })
            }
            refresh()
        }
        b.spRepeat.onSelect { refresh() }
        b.spEnd.onSelect { refresh() }
        b.swAllDay.setOnCheckedChangeListener { _, checked ->
            if (checked) { startMinute = null; endMinute = null }
            else if (startMinute == null) startMinute = 9 * 60
            refresh()
        }

        b.btnStartDate.setOnClickListener {
            pickDate(activity, startDate) { d ->
                startDate = d
                if (endDate.isBefore(startDate)) endDate = startDate
                if (weekdays.size == 1) { weekdays.clear(); weekdays.add(startDate.dayOfWeek.value) }
                refresh()
            }
        }
        b.btnEndDate.setOnClickListener {
            pickDate(activity, endDate) { d -> endDate = if (d.isBefore(startDate)) startDate else d; refresh() }
        }
        b.btnStartTime.setOnClickListener {
            pickTime(activity, startMinute ?: 9 * 60) { m -> startMinute = m; refresh() }
        }
        b.btnEndTime.setOnClickListener {
            pickTime(activity, endMinute ?: startMinute ?: 10 * 60) { m -> endMinute = m; refresh() }
        }
        b.btnEndTime.setOnLongClickListener { endMinute = null; refresh(); true }

        val weekdayBtns = listOf(b.tglMon, b.tglTue, b.tglWed, b.tglThu, b.tglFri, b.tglSat, b.tglSun)
        weekdayBtns.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                val iso = i + 1
                if (iso in weekdays) weekdays.remove(iso) else weekdays.add(iso)
                if (weekdays.isEmpty()) weekdays.add(startDate.dayOfWeek.value) // never empty
                refresh()
            }
        }
        b.rbDayOfMonth.setOnClickListener { monthlyMode = MonthlyMode.DAY_OF_MONTH; refresh() }
        b.rbOrdinal.setOnClickListener { monthlyMode = MonthlyMode.ORDINAL_WEEKDAY; refresh() }
        b.btnUntilDate.setOnClickListener {
            pickDate(activity, untilDate) { d -> untilDate = d; refresh() }
        }

        // ── Reminders (paper-like look-ahead) ─────────────────────────────────────
        fun renderReminders() {
            b.llReminders.removeAllViews()
            reminders.sortBy { it.leadDays }
            for (r in reminders) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.shape_bordered)
                    setPadding(dp(12), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(6) }
                }
                row.addView(TextView(activity).apply {
                    text = r.label()
                    setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(activity).apply {
                    text = "Remove"
                    setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
                    textSize = 13f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener { reminders.remove(r); renderReminders() }
                })
                b.llReminders.addView(row)
            }
        }
        b.btnAddRemind.setOnClickListener {
            val amount = b.etRemindAmount.text?.toString()?.toIntOrNull()?.coerceIn(1, 999) ?: 1
            val unit = if (b.spRemindUnit.selectedItemPosition == 1) ReminderUnit.WEEKS else ReminderUnit.DAYS
            val r = Reminder(amount, unit)
            if (reminders.none { it.amount == r.amount && it.unit == r.unit }) {
                reminders.add(r)
                renderReminders()
            }
        }
        renderReminders()

        refresh()

        // ── Build + dialog ──────────────────────────────────────────────────────
        fun build(): EventEntity {
            val type = EventType.entries[b.spType.selectedItemPosition]
            val title = b.etTitle.text?.toString()?.trim().orEmpty().ifBlank { type.label }
            val allDay = b.swAllDay.isChecked
            val safeEnd = if (endDate.isBefore(startDate)) startDate else endDate
            val freq = repeatFreq()
            val builtRule = freq?.let {
                RecurrenceRule(
                    freq = it,
                    interval = b.etInterval.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    weekdays = if (it == Freq.WEEKLY) weekdays.toList() else emptyList(),
                    monthlyMode = monthlyMode,
                    endMode = when (b.spEnd.selectedItemPosition) { 1 -> EndMode.UNTIL; 2 -> EndMode.COUNT; else -> EndMode.NEVER },
                    endEpochDay = if (b.spEnd.selectedItemPosition == 1) untilDate.toEpochDay() else null,
                    endCount = if (b.spEnd.selectedItemPosition == 2)
                        b.etCount.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1 else null,
                )
            }
            val now = System.currentTimeMillis()
            return EventEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                type = type.name,
                title = title,
                startEpochDay = startDate.toEpochDay(),
                endEpochDay = safeEnd.toEpochDay(),
                allDay = allDay,
                startMinute = if (allDay) null else startMinute,
                endMinute = if (allDay) null else endMinute,
                recurring = builtRule != null,
                data = EventPayload(
                    recurrence = builtRule,
                    notes = payload.notes,
                    reminders = reminders.toList(),
                ).toJson(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                deletedAt = null,
            )
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "New event" else "Edit event")
            .setView(b.root)
            .setPositiveButton("Save") { _, _ -> onSaved(build()) }
            .setNegativeButton("Cancel", null)
        if (!isNew && onDeleted != null) {
            builder.setNeutralButton("Delete") { _, _ -> onDeleted(existing!!) }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun ordinalLabel(d: LocalDate): String {
        val ord = (d.dayOfMonth - 1) / 7 + 1
        val isLast = d.dayOfMonth + 7 > d.lengthOfMonth()
        val word = if (ord >= 5 || (isLast && ord >= 4)) "last" else when (ord) {
            1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "4th"
        }
        val dow = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        return "$word $dow"
    }

    private fun Spinner.attach(activity: Activity, items: List<String>) {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun Spinner.onSelect(block: () -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = block()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** All date fields use the shared e-ink [DayPickerDialog] — the same calendar-grid picker the day
     *  window and calendar views use for day navigation — instead of the native spinner. */
    private fun pickDate(activity: Activity, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        DayPickerDialog.show(activity, initial, onPicked)
    }

    private fun pickTime(activity: Activity, initialMinute: Int, onPicked: (Int) -> Unit) {
        val t = LocalTime.of((initialMinute / 60).coerceIn(0, 23), (initialMinute % 60).coerceIn(0, 59))
        TimePickerDialog(
            activity,
            { _, h, min -> onPicked(h * 60 + min) },
            t.hour, t.minute, DateFormat.is24HourFormat(activity),
        ).show()
    }
}
