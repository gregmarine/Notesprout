package com.notesprout.android

import android.app.Activity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.RoutinePeriod
import com.notesprout.android.data.tasks.TaskRecurrence
import com.notesprout.android.databinding.DialogRoutineEditorBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Add / edit a routine — a name and a rhythm. Its steps live on the routine's own screen.
 *
 * **Frequency is fixed once created.** Changing it would have to re-derive the due date and re-map
 * every step's position into a different-length period — offset 6 means Saturday in a week but the
 * 7th in a month — with no obviously right answer. Delete and recreate covers the rare case.
 */
object RoutineEditorDialog {

    private val dueFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    /**
     * [onSaved] receives the chosen title and frequency. The caller creates or renames — this dialog
     * does not touch the database, so a new routine and an edited one take the same path out.
     */
    fun show(
        activity: Activity,
        existing: TaskEntity?,
        today: LocalDate,
        onSaved: (title: String, freq: Freq) -> Unit,
        onDeleted: ((TaskEntity) -> Unit)? = null,
    ) {
        val b = DialogRoutineEditorBinding.inflate(activity.layoutInflater)
        val isNew = existing == null
        val existingFreq = TaskRecurrence.freqOf(existing?.recurFreq) ?: Freq.WEEKLY

        fun selectedFreq(): Freq =
            RoutinePeriod.FREQUENCIES.getOrElse(b.spRoutineFreq.selectedItemPosition) { Freq.WEEKLY }

        fun refresh() {
            // A routine's due date is derived, so show what the chosen rhythm actually means. On an
            // existing routine that is its real due date; on a new one, the period it will land in.
            val due = existing?.dueEpochDay
                ?: RoutinePeriod.dueFor(selectedFreq(), today.toEpochDay())
            b.tvRoutineDue.text = "Due ${LocalDate.ofEpochDay(due).format(dueFmt)}"
        }

        b.spRoutineFreq.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            RoutinePeriod.FREQUENCIES.map { RoutinePeriod.label(it) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spRoutineFreq.setSelection(RoutinePeriod.FREQUENCIES.indexOf(existingFreq).coerceAtLeast(0))
        b.etRoutineTitle.setText(existing?.title.orEmpty())
        b.etRoutineTitle.setSelection(b.etRoutineTitle.text?.length ?: 0)

        if (!isNew) {
            // Visibly inert rather than silently ignored — a disabled control is invisible on e-ink
            // (see docs/design-system.md), so it stays enabled-looking but refuses to change.
            b.spRoutineFreq.setOnTouchListener { _, _ ->
                Toast.makeText(
                    activity,
                    "A routine's rhythm is fixed. Create a new routine to change it.",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            }
        } else {
            b.spRoutineFreq.onSelect { refresh() }
        }
        refresh()

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "New routine" else "Edit routine")
            .setView(b.root)
            .setPositiveButton("Save", null) // validated handler installed after show()
            .setNegativeButton("Cancel", null)
        if (!isNew && onDeleted != null) {
            builder.setNeutralButton("Delete") { _, _ -> onDeleted(existing) }
        }

        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val title = b.etRoutineTitle.text?.toString()?.trim().orEmpty()
            if (title.isBlank()) {
                Toast.makeText(activity, "Give the routine a name.", Toast.LENGTH_SHORT).show()
            } else {
                onSaved(title, if (isNew) selectedFreq() else existingFreq)
                dialog.dismiss()
            }
        }
    }

    private fun Spinner.onSelect(block: () -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = block()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
