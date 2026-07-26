package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.TopGuard
import com.notesprout.android.data.ReopenOutcome
import com.notesprout.android.data.TaskSection
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.RoutinePeriod
import com.notesprout.android.data.tasks.TaskRecurrence
import com.notesprout.android.data.tasks.TaskState
import com.notesprout.android.databinding.ActivityRoutineBinding
import com.notesprout.android.databinding.ItemTaskBinding
import com.notesprout.android.state.AppSurface
import com.notesprout.android.state.SurfaceEntry
import com.notesprout.android.state.SurfaceStack
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * One routine occurrence and the steps in it.
 *
 * The screen shows a **single occurrence** — there is only ever one live at a time, and a finished
 * one is history. Steps are grouped Overdue / Today / Upcoming like the main list, which is only
 * meaningful because a step carries its own date inside the routine's period.
 *
 * Resolving the last open step completes the routine, rolls it forward, and returns to the task list:
 * the occurrence is over, so there is nothing left to do here.
 */
class RoutineActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ROUTINE_ID = "routine_id"

        fun intent(context: Context, routineId: String): Intent =
            Intent(context, RoutineActivity::class.java).putExtra(EXTRA_ROUTINE_ID, routineId)

        fun launch(context: Context, routineId: String) {
            context.startActivity(intent(context, routineId))
        }
    }

    private lateinit var binding: ActivityRoutineBinding
    private val repo by lazy { TasksRepository() }

    private lateinit var routineId: String
    private var routine: TaskEntity? = null

    /**
     * A finished occurrence is history: no steps to add, nothing to tick or un-tick, nothing to edit.
     * The repository refuses these anyway ([ReopenOutcome.LOCKED]) — this is the screen not offering
     * what would only be turned down.
     */
    private var readOnly = false

    private var surfaceToken: String = ""

    private val dueFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoutineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        routineId = intent.getStringExtra(EXTRA_ROUTINE_ID).orEmpty()
        if (routineId.isEmpty()) { finish(); return }

        surfaceToken = savedInstanceState?.getString(SurfaceStack.KEY_TOKEN)
            ?: UUID.randomUUID().toString()
        SurfaceStack.attach(this, surfaceEntry())

        binding.btnRoutineBack.setOnClickListener { finish() }
        binding.btnRoutineCalendar.setOnClickListener { CalendarActivity.launch(this) }
        binding.btnRoutineScratchpad.setOnClickListener {
            startActivity(Intent(this, ScratchpadActivity::class.java))
        }
        binding.btnAddStep.setOnClickListener { openStepEditor(null) }
    }

    override fun onResume() {
        super.onResume()
        SurfaceStack.markTop(this, surfaceEntry())
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SurfaceStack.KEY_TOKEN, surfaceToken)
    }

    private fun surfaceEntry() = SurfaceEntry(surfaceToken, AppSurface.ROUTINE, routineId = routineId)

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            val current = repo.get(routineId)
            if (current == null) {
                // Deleted while we were away, or a stale surface-stack entry from a previous launch.
                toast("That routine is gone.")
                finish()
                return@launch
            }
            routine = current
            val state = TaskState.fromName(current.state)
            readOnly = state.isResolved
            val today = LocalDate.now()

            binding.tvRoutineTitle.text = current.title
            binding.tvRoutineDue.text = when {
                // A finished occurrence is named by what happened to it, not by a deadline it no
                // longer has anything to do with.
                state == TaskState.DONE -> "Completed"
                state == TaskState.SKIPPED -> "Skipped"
                else -> current.dueEpochDay
                    ?.let { "Due ${LocalDate.ofEpochDay(it).format(dueFmt)}" }.orEmpty()
            }
            binding.btnAddStep.isVisible = !readOnly
            binding.routineEmpty.text = if (readOnly) "No steps" else "No steps yet"

            // A finished occurrence is history, so date sections say nothing useful about it —
            // "Upcoming" over steps that were completed a week ago is simply wrong. Flat list.
            if (readOnly) renderFlat(repo.members(current.id), today)
            else render(repo.memberSections(current, today), today)
        }
    }

    private fun render(sections: List<TaskSection>, today: LocalDate) {
        val inflater = LayoutInflater.from(this)
        binding.routineList.removeAllViews()
        binding.routineEmpty.isVisible = sections.isEmpty()
        for ((index, section) in sections.withIndex()) {
            addSectionHeader(section.kind.label, topGap = index > 0)
            for (step in section.tasks) addStepRow(inflater, step, today)
        }
    }

    /** A finished occurrence: every step in order, no date grouping. */
    private fun renderFlat(steps: List<TaskEntity>, today: LocalDate) {
        val inflater = LayoutInflater.from(this)
        binding.routineList.removeAllViews()
        binding.routineEmpty.isVisible = steps.isEmpty()
        for (step in steps) addStepRow(inflater, step, today)
    }

    private fun addStepRow(inflater: LayoutInflater, step: TaskEntity, today: LocalDate) {
        val row = ItemTaskBinding.inflate(inflater, binding.routineList, false)
        val state = TaskState.fromName(step.state)

        row.btnTaskState.setImageResource(
            when (state) {
                TaskState.NOT_DONE -> R.drawable.ic_checkbox_empty
                TaskState.DONE -> R.drawable.ic_checkbox_checked
                TaskState.SKIPPED -> R.drawable.ic_checkbox_skipped
            }
        )
        if (readOnly) {
            row.btnTaskState.isClickable = false
            row.btnTaskState.contentDescription = state.label
        } else {
            row.btnTaskState.contentDescription =
                if (state.isResolved) "Mark not done" else "Mark done"
            row.btnTaskState.setOnClickListener {
                if (state.isResolved) reopenStep(step) else resolveStep(step, TaskState.DONE)
            }
        }

        row.tvTaskTitle.text = step.title
        val meta = if (state == TaskState.SKIPPED) state.label else null
        row.tvTaskMeta.text = meta.orEmpty()
        row.tvTaskMeta.isVisible = meta != null

        val due = dueLabel(step, today)
        row.tvTaskDue.text = due.orEmpty()
        row.tvTaskDue.isVisible = due != null

        if (!readOnly) {
            row.taskRow.setOnClickListener { openStepEditor(step) }
            row.taskRow.setOnLongClickListener { showStepActions(step, state); true }
        }
        binding.routineList.addView(row.root)
    }

    private fun addSectionHeader(text: String, topGap: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@RoutineActivity, R.color.inkBlack))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
                if (topGap) topMargin = dp(10)
            }
        }
        binding.routineList.addView(tv)
    }

    /** As on the main list: nothing inside Today, relative when overdue, the date when ahead. */
    private fun dueLabel(step: TaskEntity, today: LocalDate): String? {
        val due = step.dueEpochDay ?: return null
        val delta = due - today.toEpochDay()
        return when {
            delta == 0L -> null
            delta == -1L -> "Yesterday"
            delta < 0 -> "${-delta}d ago"
            delta == 1L -> "Tomorrow"
            else -> LocalDate.ofEpochDay(due).format(dueFmt)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Steps ──────────────────────────────────────────────────────────────────

    private fun openStepEditor(existing: TaskEntity?) {
        val current = routine ?: return
        val freq = TaskRecurrence.freqOf(current.recurFreq) ?: return
        val due = current.dueEpochDay ?: return
        TaskEditorDialog.show(
            activity = this,
            existing = existing,
            member = TaskEditorDialog.MemberContext(
                periodStart = RoutinePeriod.startFor(freq, due),
                periodEnd = due,
            ),
            onSaved = { step -> lifecycleScope.launch { repo.saveMember(current, step); refresh() } },
            onDeleted = { step -> confirmDeleteStep(step) },
        )
    }

    private fun showStepActions(step: TaskEntity, state: TaskState) {
        val sheet = ActionSheetDialog(this)
            .title(step.title)
            .addAction(R.drawable.ic_edit, "Edit") { openStepEditor(step) }
        if (state.isResolved) {
            sheet.addAction(R.drawable.ic_undo, "Mark not done") { reopenStep(step) }
        } else {
            sheet.addAction(R.drawable.ic_checkbox_skipped, "Skip") {
                resolveStep(step, TaskState.SKIPPED)
            }
        }
        sheet.addAction(R.drawable.ic_trash, "Delete") { confirmDeleteStep(step) }
        sheet.show()
    }

    /**
     * Resolve a step. If it was the last one open, the routine completes and rolls forward — and the
     * occurrence on screen is finished, so there is nothing more to do here: say what happened and
     * return to the list, where the next occurrence is now waiting.
     */
    private fun resolveStep(step: TaskEntity, state: TaskState) {
        lifecycleScope.launch {
            val outcome = repo.resolveMember(step, state, LocalDate.now())
            if (outcome.routineCompleted) {
                val next = outcome.nextRoutineDue
                    ?.let { " · next due ${LocalDate.ofEpochDay(it).format(dueFmt)}" }.orEmpty()
                toast("${routine?.title ?: "Routine"} complete$next")
                finish()
            } else {
                refresh()
            }
        }
    }

    private fun reopenStep(step: TaskEntity) {
        lifecycleScope.launch {
            when (repo.reopen(step)) {
                ReopenOutcome.REOPENED -> refresh()
                ReopenOutcome.LOCKED -> toast("This routine is finished — its steps can't be changed.")
                ReopenOutcome.SERIES_MOVED_ON -> refresh()
            }
        }
    }

    private fun confirmDeleteStep(step: TaskEntity) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete step")
            .setMessage("Delete “${step.title}” from this routine?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { repo.delete(step.id); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
