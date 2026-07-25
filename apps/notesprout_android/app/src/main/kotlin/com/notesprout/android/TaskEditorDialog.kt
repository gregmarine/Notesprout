package com.notesprout.android

import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.databinding.DialogTaskEditorBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Add / edit editor for a [TaskEntity]. E-ink styled (bordered dialog, no elevation), same family as
 * [EventEditorDialog].
 *
 * Builds the entity locally and hands it back via [onSaved]; [onDeleted] powers the Delete button
 * shown only when editing. The caller persists through [TasksRepository.save], which owns the series
 * bookkeeping — this editor never touches `seriesId` / `seriesIndex` / `seriesAnchorDay`.
 */
object TaskEditorDialog {

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

        fun refresh() {
            b.btnTaskDueDate.text = dueDate?.format(dateFmt) ?: "No date"
            b.btnTaskDueClear.isVisible = dueDate != null
        }

        b.etTaskTitle.setText(base.title)
        b.etTaskTitle.setSelection(base.title.length)
        refresh()

        b.btnTaskDueDate.setOnClickListener {
            // The shared e-ink calendar-grid picker, not the native spinner (whose coloured header
            // reads wrong on e-ink). Defaults to today when the task has no date yet.
            DayPickerDialog.show(activity, dueDate ?: LocalDate.now()) { picked ->
                dueDate = picked
                refresh()
            }
        }
        b.btnTaskDueClear.setOnClickListener {
            dueDate = null
            refresh()
        }

        fun build(): TaskEntity = base.copy(
            title = b.etTaskTitle.text?.toString()?.trim().orEmpty(),
            dueEpochDay = dueDate?.toEpochDay(),
            updatedAt = System.currentTimeMillis(),
        )

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

        // Manual Save handler so validation can keep the dialog open. A blank title would produce a
        // row that renders as an empty box the user cannot identify or meaningfully re-open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val built = build()
            if (built.title.isBlank()) {
                Toast.makeText(activity, "Give the task a name.", Toast.LENGTH_SHORT).show()
            } else {
                onSaved(built)
                dialog.dismiss()
            }
        }
    }
}
