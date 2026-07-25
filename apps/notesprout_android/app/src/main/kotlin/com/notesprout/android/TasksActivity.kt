package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.TopGuard
import com.notesprout.android.data.ReopenOutcome
import com.notesprout.android.data.ResolvedGroup
import com.notesprout.android.data.TaskSection
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.TaskRecurrence
import com.notesprout.android.data.tasks.TaskState
import com.notesprout.android.databinding.ActivityTasksBinding
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
 * **Tasks** — the "list of things I need to do" surface.
 *
 * A sibling of the calendar and the scratch pad, and deliberately independent of both: no task is
 * drawn on a calendar grid and no calendar content is read here. (Wiring the two together is a
 * later effort.)
 *
 * Two views, chosen by a toolbar toggle pair:
 *
 * - **Tasks** — every open task, grouped **Overdue → Today → Upcoming → No date**.
 * - **Done** — completed and skipped tasks, grouped by the day they were resolved.
 *
 * Unlike the drawing surfaces this screen keeps the system bars visible — there is no canvas that
 * wants to be full-bleed — so the top guard comes from the live inset ([TopGuard.applyInsetPadding])
 * rather than a fixed reservation.
 */
class TasksActivity : AppCompatActivity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, TasksActivity::class.java))
        }

        fun intent(context: Context): Intent = Intent(context, TasksActivity::class.java)
    }

    private enum class ViewMode { OPEN, DONE }

    private lateinit var binding: ActivityTasksBinding
    private val repo by lazy { TasksRepository() }

    private var mode = ViewMode.OPEN

    /** This Activity instance's identity on the [SurfaceStack]. */
    private var surfaceToken: String = ""

    private val dueFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val groupFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTasksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Record the task screen on the surface stack, so a cold launch reopens it (see SurfaceStack).
        surfaceToken = savedInstanceState?.getString(SurfaceStack.KEY_TOKEN)
            ?: UUID.randomUUID().toString()
        SurfaceStack.attach(this, surfaceEntry())

        binding.btnTasksBack.setOnClickListener { finish() }
        binding.btnViewOpen.setOnClickListener { switchMode(ViewMode.OPEN) }
        binding.btnViewDone.setOnClickListener { switchMode(ViewMode.DONE) }
        binding.btnAddTask.setOnClickListener { openEditor(null) }

        applyMode()
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

    private fun surfaceEntry() = SurfaceEntry(surfaceToken, AppSurface.TASKS)

    // ── View mode ──────────────────────────────────────────────────────────────

    private fun switchMode(next: ViewMode) {
        if (mode == next) return
        mode = next
        applyMode()
        refresh()
    }

    private fun applyMode() {
        binding.btnViewOpen.isSelected = mode == ViewMode.OPEN
        binding.btnViewDone.isSelected = mode == ViewMode.DONE
        binding.tasksEmpty.text = when (mode) {
            ViewMode.OPEN -> "Nothing to do"
            ViewMode.DONE -> "Nothing finished yet"
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            when (mode) {
                ViewMode.OPEN -> renderOpen(repo.openSections(LocalDate.now()))
                ViewMode.DONE -> renderDone(repo.resolvedGroups())
            }
        }
    }

    private fun renderOpen(sections: List<TaskSection>) {
        val inflater = LayoutInflater.from(this)
        binding.tasksList.removeAllViews()
        binding.tasksEmpty.isVisible = sections.isEmpty()
        val today = LocalDate.now()
        for ((index, section) in sections.withIndex()) {
            addSectionHeader(section.kind.label, topGap = index > 0)
            for (task in section.tasks) addTaskRow(inflater, task, dueLabel(task, today))
        }
    }

    private fun renderDone(groups: List<ResolvedGroup>) {
        val inflater = LayoutInflater.from(this)
        binding.tasksList.removeAllViews()
        binding.tasksEmpty.isVisible = groups.isEmpty()
        val today = LocalDate.now()
        for ((index, group) in groups.withIndex()) {
            addSectionHeader(resolvedGroupLabel(group.date, today), topGap = index > 0)
            for (task in group.tasks) addTaskRow(inflater, task, dueLabel = null)
        }
    }

    private fun addTaskRow(inflater: LayoutInflater, task: TaskEntity, dueLabel: String?) {
        val row = ItemTaskBinding.inflate(inflater, binding.tasksList, false)
        val state = TaskState.fromName(task.state)

        row.btnTaskState.setImageResource(
            when (state) {
                TaskState.NOT_DONE -> R.drawable.ic_checkbox_empty
                TaskState.DONE -> R.drawable.ic_checkbox_checked
                TaskState.SKIPPED -> R.drawable.ic_checkbox_skipped
            }
        )
        // The box is the one-tap control, in both directions: check an open task off, or un-check a
        // resolved one back into the list.
        row.btnTaskState.contentDescription = if (state.isResolved) "Mark not done" else "Mark done"
        row.btnTaskState.setOnClickListener {
            if (state.isResolved) reopenTask(task) else resolveTask(task, TaskState.DONE)
        }

        row.tvTaskTitle.text = task.title
        val meta = meta(task, state)
        row.tvTaskMeta.text = meta.orEmpty()
        row.tvTaskMeta.isVisible = meta != null
        row.tvTaskDue.text = dueLabel.orEmpty()
        row.tvTaskDue.isVisible = dueLabel != null

        row.taskRow.setOnClickListener { openEditor(task) }
        row.taskRow.setOnLongClickListener { showActions(task, state); true }
        binding.tasksList.addView(row.root)
    }

    /**
     * The row's long-press menu. Skip lives here rather than on the row itself: it is the rarer
     * choice of the two, and a second always-visible control on every row would clutter a list whose
     * whole point is calm.
     */
    private fun showActions(task: TaskEntity, state: TaskState) {
        val sheet = ActionSheetDialog(this)
            .title(task.title)
            .addAction(R.drawable.ic_edit, "Edit") { openEditor(task) }
        if (state.isResolved) {
            sheet.addAction(R.drawable.ic_undo, "Mark not done") { reopenTask(task) }
        } else {
            sheet.addAction(R.drawable.ic_checkbox_skipped, "Skip") {
                resolveTask(task, TaskState.SKIPPED)
            }
        }
        sheet.addAction(R.drawable.ic_trash, "Delete") { confirmDelete(task) }
        sheet.show()
    }

    /** A bold black section label, matching the Events list's section treatment. */
    private fun addSectionHeader(text: String, topGap: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@TasksActivity, R.color.inkBlack))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
                if (topGap) topMargin = dp(10)
            }
        }
        binding.tasksList.addView(tv)
    }

    // ── Labels ─────────────────────────────────────────────────────────────────

    /**
     * The trailing date label. Null inside Today (the section header already says it) and for an
     * undated task, so those rows stay quiet.
     */
    private fun dueLabel(task: TaskEntity, today: LocalDate): String? {
        val due = task.dueEpochDay ?: return null
        val delta = due - today.toEpochDay()
        return when {
            delta == 0L -> null
            delta == -1L -> "Yesterday"
            delta < 0 -> "${-delta}d ago"
            delta == 1L -> "Tomorrow"
            else -> LocalDate.ofEpochDay(due).format(dueFmt)
        }
    }

    /** Meta line: the recurrence summary, plus the state word on a resolved row. */
    private fun meta(task: TaskEntity, state: TaskState): String? {
        val parts = mutableListOf<String>()
        if (state == TaskState.SKIPPED) parts += state.label
        TaskRecurrence.summary(task)?.let { parts += it }
        if (state.isResolved) {
            task.dueEpochDay?.let { parts += "due ${LocalDate.ofEpochDay(it).format(dueFmt)}" }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun resolvedGroupLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(groupFmt)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── State changes ──────────────────────────────────────────────────────────

    /**
     * Complete or skip [task]. Both resolve the row and advance its series identically — a skip is a
     * decision about the occurrence, not a deletion of it — so they share one path.
     */
    private fun resolveTask(task: TaskEntity, state: TaskState) {
        lifecycleScope.launch {
            val today = LocalDate.now()
            val successor = when (state) {
                TaskState.SKIPPED -> repo.skip(task, today)
                else -> repo.complete(task, today)
            }
            refresh()
            // Only recurring tasks produce a successor, and its date is the non-obvious part of the
            // interaction — the row the user just checked off vanishes and a new one appears
            // somewhere else in the list.
            successor?.dueEpochDay?.let {
                toast("Next due ${LocalDate.ofEpochDay(it).format(dueFmt)}")
            }
        }
    }

    private fun reopenTask(task: TaskEntity) {
        lifecycleScope.launch {
            when (repo.reopen(task)) {
                ReopenOutcome.REOPENED -> refresh()
                ReopenOutcome.SERIES_MOVED_ON ->
                    toast("Later occurrences of this task have already been dealt with.")
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ── Editing ────────────────────────────────────────────────────────────────

    private fun openEditor(existing: TaskEntity?) {
        TaskEditorDialog.show(
            activity = this,
            existing = existing,
            onSaved = { task -> lifecycleScope.launch { repo.save(task); refresh() } },
            onDeleted = { task -> confirmDelete(task) },
        )
    }

    private fun confirmDelete(task: TaskEntity) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete task")
            .setMessage("Delete “${task.title}”?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { repo.delete(task.id); refresh() }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }
}
