package com.notesprout.android

import android.app.Activity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.events.MonthlyMode
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.TaskRecurrence
import com.notesprout.android.data.tasks.TaskWeekdays
import com.notesprout.android.databinding.DialogTaskEditorBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Add / edit editor for a [TaskEntity]. E-ink styled (bordered dialog, no elevation), and the
 * recurrence builder is the same one [EventEditorDialog] uses — frequency, "every N", a weekly
 * weekday set, monthly day-of-month vs ordinal weekday, and ends Never / on a date / after N.
 *
 * Builds the entity locally and hands it back via [onSaved]; [onDeleted] powers the Delete button
 * shown only when editing. The caller persists through [TasksRepository.save], which owns the series
 * bookkeeping — this editor never touches `seriesId` / `seriesIndex` / `seriesAnchorDay`.
 */
object TaskEditorDialog {

    private val REPEAT_LABELS = listOf("Does not repeat", "Daily", "Weekly", "Monthly", "Yearly")
    private val END_LABELS = listOf("Never", "On a date", "After a number of times")

    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    fun show(
        activity: Activity,
        existing: TaskEntity?,
        onSaved: (TaskEntity) -> Unit,
        onDeleted: ((TaskEntity) -> Unit)? = null,
    ) {
        val b = DialogTaskEditorBinding.inflate(activity.layoutInflater)
        val isNew = existing == null
        val base = existing ?: TasksRepository.blank()

        // ── Mutable working state ───────────────────────────────────────────────
        var dueDate: LocalDate? = base.dueEpochDay?.let { LocalDate.ofEpochDay(it) }
        val weekdays = sortedSetOf<Int>().apply {
            // A stored mask of 0 means "the anchor's own weekday" (see TaskWeekdays), which unpacks
            // to nothing — seed it so the toggles never come up with no day selected.
            addAll(TaskWeekdays.unpack(base.recurWeekdays))
            if (isEmpty()) add((dueDate ?: LocalDate.now()).dayOfWeek.value)
        }
        var monthlyMode = MonthlyMode.entries
            .firstOrNull { it.name == base.recurMonthlyMode } ?: MonthlyMode.DAY_OF_MONTH
        var untilDate: LocalDate = base.recurEndEpochDay?.let { LocalDate.ofEpochDay(it) }
            ?: (dueDate ?: LocalDate.now()).plusMonths(1)

        fun repeatFreq(): Freq? = when (b.spTaskRepeat.selectedItemPosition) {
            1 -> Freq.DAILY; 2 -> Freq.WEEKLY; 3 -> Freq.MONTHLY; 4 -> Freq.YEARLY; else -> null
        }

        /** The day the recurrence is anchored on for label purposes — the due date, or today. */
        fun anchor(): LocalDate = dueDate ?: LocalDate.now()

        // ── Render current state into the views ─────────────────────────────────
        fun refresh() {
            b.btnTaskDueDate.text = dueDate?.format(dateFmt) ?: "No date"
            b.btnTaskDueClear.isVisible = dueDate != null

            // A recurring task has to be anchored to a day, or there is no series to walk. Rather
            // than let the user build a rule that silently cannot be saved, the whole builder is
            // replaced by a one-line explanation until a due date exists.
            val dated = dueDate != null
            b.spTaskRepeat.isVisible = dated
            b.tvTaskRepeatNeedsDate.isVisible = !dated

            val freq = repeatFreq().takeIf { dated }
            val repeats = freq != null
            b.grpTaskEvery.isVisible = repeats
            b.grpTaskWeekdays.isVisible = freq == Freq.WEEKLY
            b.grpTaskMonthly.isVisible = freq == Freq.MONTHLY
            b.grpTaskEndBlock.isVisible = repeats
            b.tvTaskUnit.text = when (freq) {
                Freq.DAILY -> "days"; Freq.WEEKLY -> "weeks"; Freq.MONTHLY -> "months"
                Freq.YEARLY -> "years"; null -> ""
            }

            // Weekday toggles reflect the set (buttons ordered Mon..Sun = ISO 1..7).
            listOf(
                b.tglTaskMon, b.tglTaskTue, b.tglTaskWed, b.tglTaskThu,
                b.tglTaskFri, b.tglTaskSat, b.tglTaskSun,
            ).forEachIndexed { i, btn -> btn.isSelected = (i + 1) in weekdays }

            b.rbTaskDayOfMonth.isSelected = monthlyMode == MonthlyMode.DAY_OF_MONTH
            b.rbTaskOrdinal.isSelected = monthlyMode == MonthlyMode.ORDINAL_WEEKDAY
            b.rbTaskDayOfMonth.text = "On day ${anchor().dayOfMonth}"
            b.rbTaskOrdinal.text = "On the ${ordinalLabel(anchor())}"

            b.btnTaskUntilDate.isVisible = b.spTaskEnd.selectedItemPosition == 1
            b.grpTaskCount.isVisible = b.spTaskEnd.selectedItemPosition == 2
            b.btnTaskUntilDate.text = untilDate.format(dateFmt)
        }

        // ── Initial selection (before listeners, so we don't clobber loaded values) ──
        b.spTaskRepeat.attach(activity, REPEAT_LABELS)
        b.spTaskEnd.attach(activity, END_LABELS)
        b.spTaskRepeat.setSelection(
            when (TaskRecurrence.freqOf(base.recurFreq)) {
                Freq.DAILY -> 1; Freq.WEEKLY -> 2; Freq.MONTHLY -> 3; Freq.YEARLY -> 4; null -> 0
            }
        )
        b.spTaskEnd.setSelection(
            when (base.recurEndMode) { EndMode.UNTIL.name -> 1; EndMode.COUNT.name -> 2; else -> 0 }
        )
        b.etTaskTitle.setText(base.title)
        b.etTaskTitle.setSelection(base.title.length)
        b.etTaskInterval.setText((base.recurInterval ?: 1).coerceIn(1, 99).toString())
        b.etTaskCount.setText((base.recurEndCount ?: 10).coerceAtLeast(1).toString())

        // ── Listeners ───────────────────────────────────────────────────────────
        b.spTaskRepeat.onSelect { refresh() }
        b.spTaskEnd.onSelect { refresh() }

        b.btnTaskDueDate.setOnClickListener {
            // The shared e-ink calendar-grid picker, not the native spinner (whose coloured header
            // reads wrong on e-ink). Defaults to today when the task has no date yet.
            DayPickerDialog.show(activity, dueDate ?: LocalDate.now()) { picked ->
                dueDate = picked
                // Keep a lone weekday selection following the anchor, as the event editor does, so
                // "weekly" means "weekly on this task's own day" until the user says otherwise.
                if (weekdays.size == 1) { weekdays.clear(); weekdays.add(picked.dayOfWeek.value) }
                if (untilDate.isBefore(picked)) untilDate = picked.plusMonths(1)
                refresh()
            }
        }
        b.btnTaskDueClear.setOnClickListener {
            dueDate = null
            // Undated and recurring cannot coexist; drop the rule rather than keep a hidden one that
            // would spring back if a date were set again.
            b.spTaskRepeat.setSelection(0)
            refresh()
        }

        listOf(
            b.tglTaskMon, b.tglTaskTue, b.tglTaskWed, b.tglTaskThu,
            b.tglTaskFri, b.tglTaskSat, b.tglTaskSun,
        ).forEachIndexed { i, btn ->
            btn.setOnClickListener {
                val iso = i + 1
                if (iso in weekdays) weekdays.remove(iso) else weekdays.add(iso)
                if (weekdays.isEmpty()) weekdays.add(anchor().dayOfWeek.value) // never empty
                refresh()
            }
        }
        b.rbTaskDayOfMonth.setOnClickListener { monthlyMode = MonthlyMode.DAY_OF_MONTH; refresh() }
        b.rbTaskOrdinal.setOnClickListener { monthlyMode = MonthlyMode.ORDINAL_WEEKDAY; refresh() }
        b.btnTaskUntilDate.setOnClickListener {
            DayPickerDialog.show(activity, untilDate) { d -> untilDate = d; refresh() }
        }

        refresh()

        // ── Build + dialog ──────────────────────────────────────────────────────
        fun build(): TaskEntity {
            val due = dueDate
            val freq = repeatFreq().takeIf { due != null }
            val endMode = when (b.spTaskEnd.selectedItemPosition) {
                1 -> EndMode.UNTIL; 2 -> EndMode.COUNT; else -> EndMode.NEVER
            }
            // A weekday set equal to just the anchor's own day is the engine's default; store 0 so
            // moving the due date keeps carrying the rule with it.
            val weekdayMask = when {
                freq != Freq.WEEKLY -> null
                weekdays.size == 1 && due != null && weekdays.first() == due.dayOfWeek.value -> 0
                else -> TaskWeekdays.pack(weekdays)
            }
            return base.copy(
                title = b.etTaskTitle.text?.toString()?.trim().orEmpty(),
                dueEpochDay = due?.toEpochDay(),
                recurFreq = freq?.name,
                recurInterval = freq?.let {
                    b.etTaskInterval.text?.toString()?.toIntOrNull()?.coerceIn(1, 99) ?: 1
                },
                recurWeekdays = weekdayMask,
                recurMonthlyMode = if (freq == Freq.MONTHLY) monthlyMode.name else null,
                recurEndMode = freq?.let { endMode.name },
                recurEndEpochDay = if (freq != null && endMode == EndMode.UNTIL) untilDate.toEpochDay() else null,
                recurEndCount = if (freq != null && endMode == EndMode.COUNT) {
                    b.etTaskCount.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                } else null,
                updatedAt = System.currentTimeMillis(),
            )
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "New task" else "Edit task")
            .setView(b.root)
            .setPositiveButton("Save", null) // validated click handler installed after show()
            .setNegativeButton("Cancel", null)
        if (!isNew && onDeleted != null) {
            builder.setNeutralButton("Delete") { _, _ -> onDeleted(existing) }
        }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        // Manual Save handler so validation can keep the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val built = build()
            // A blank title renders as an empty box the user cannot identify or re-open meaningfully.
            val error = when {
                built.title.isBlank() -> "Give the task a name."
                // An "ends on" date before the task's own due date makes a series whose first
                // occurrence is already past its end — it would produce no next task, ever. The
                // events editor learned the same lesson the hard way.
                built.recurFreq != null && built.recurEndMode == EndMode.UNTIL.name &&
                    dueDate != null && untilDate.isBefore(dueDate) ->
                    "The \"ends on\" date is before the task is due — pick a later end date."
                else -> null
            }
            if (error != null) {
                Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
            } else {
                onSaved(built)
                dialog.dismiss()
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun ordinalLabel(d: LocalDate): String {
        val ord = (d.dayOfMonth - 1) / 7 + 1
        val isLast = d.dayOfMonth + 7 > d.lengthOfMonth()
        val word = if (ord >= 5 || (isLast && ord >= 4)) "last" else when (ord) {
            1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "4th"
        }
        return "$word ${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
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
}
