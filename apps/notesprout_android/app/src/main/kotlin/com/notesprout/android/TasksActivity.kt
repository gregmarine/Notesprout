package com.notesprout.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.IndexGuard
import com.notesprout.android.core.TopGuard
import com.notesprout.android.core.TwoFingerSwipeDown
import com.notesprout.android.data.ReopenOutcome
import com.notesprout.android.data.ResolvedPage
import com.notesprout.android.data.RoutineProgress
import com.notesprout.android.data.TaskSection
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.data.visibleFrom
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.RoutinePeriod
import com.notesprout.android.data.tasks.TaskRecurrence
import com.notesprout.android.data.tasks.TaskRowType
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

    /** Widening scope: what needs attention now → every open task → the resolved history. */
    private enum class ViewMode { OPEN, ALL, DONE }

    private lateinit var binding: ActivityTasksBinding
    private val repo by lazy { TasksRepository() }

    private var mode = ViewMode.OPEN

    /** Done view: whether the user has asked past the default window. Reset on leaving the view. */
    private var doneShowAll = false

    /** This Activity instance's identity on the [SurfaceStack]. */
    private var surfaceToken: String = ""

    private val dueFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val groupFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())

    /**
     * Two-finger downward swipe → the Today dashboard, as on the notebook, the calendar and the day
     * window. This screen and the dashboard are the two list surfaces a day is planned from, so the
     * shortcut earns its place here as much as anywhere.
     */
    private val todaySwipe by lazy { TwoFingerSwipeDown(this) { TodayActivity.launch(this) } }

    /**
     * Feed the Today shortcut, then hand every event straight on.
     *
     * No pen gate and no tool-type filter, unlike the drawing hosts: there is no canvas here to
     * protect a stroke on, and needing two pointers already makes the gesture deliberate. Nothing is
     * consumed either — the task list scrolls, and a swipe that took the sequence from it would eat
     * ordinary taps whenever a second finger was resting on the glass (see [TwoFingerSwipeDown]).
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        todaySwipe.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing has opened the index if Android rebuilt this task itself — see IndexGuard.
        if (!IndexGuard.ready(this)) return
        binding = ActivityTasksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        // Record the task screen on the surface stack, so a cold launch reopens it (see SurfaceStack).
        surfaceToken = savedInstanceState?.getString(SurfaceStack.KEY_TOKEN)
            ?: UUID.randomUUID().toString()
        SurfaceStack.attach(this, surfaceEntry())

        binding.btnTasksBack.setOnClickListener { finish() }
        binding.btnViewOpen.setOnClickListener { switchMode(ViewMode.OPEN) }
        binding.btnViewAll.setOnClickListener { switchMode(ViewMode.ALL) }
        binding.btnViewDone.setOnClickListener { switchMode(ViewMode.DONE) }
        binding.btnAddTask.setOnClickListener { openEditor(null) }
        binding.btnAddRoutine.setOnClickListener { openRoutineEditor(null) }
        binding.btnTasksCalendar.setOnClickListener { CalendarActivity.launch(this) }
        binding.btnTasksScratchpad.setOnClickListener {
            startActivity(Intent(this, ScratchpadActivity::class.java))
        }

        applyMode()
    }

    override fun onResume() {
        super.onResume()
        SurfaceStack.markTop(this, surfaceEntry())
        registerReceiver(dateChangeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        refresh()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(dateChangeReceiver) }
    }

    /**
     * Everything on this screen is relative to *today*, so the day rolling over silently invalidates
     * the whole list: yesterday's tasks become overdue, tomorrow's become today's, and a reminder
     * window may have opened.
     *
     * Returning to the app re-reads the date anyway ([onResume] recomputes `LocalDate.now()`), which
     * covers the common case of closing it one day and opening it the next. This covers the case that
     * would otherwise go stale indefinitely: the screen left open *across* midnight — easy to do on a
     * device that is never really switched off.
     */
    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
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
        // Leaving Done drops the expansion: the next visit starts cheap again, rather than silently
        // inheriting a full-history render forever because it was expanded once.
        doneShowAll = false
        applyMode()
        refresh()
    }

    private fun applyMode() {
        binding.btnViewOpen.isSelected = mode == ViewMode.OPEN
        binding.btnViewAll.isSelected = mode == ViewMode.ALL
        binding.btnViewDone.isSelected = mode == ViewMode.DONE
        binding.tasksEmpty.text = when (mode) {
            ViewMode.OPEN -> "Nothing to do today"
            ViewMode.ALL -> "No open tasks"
            ViewMode.DONE -> "Nothing finished yet"
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch {
            val today = LocalDate.now()
            when (mode) {
                ViewMode.OPEN -> renderOpen(repo.openSections(today), today, repo.routineProgress())
                ViewMode.ALL ->
                    renderOpen(repo.openSections(today, gated = false), today, repo.routineProgress())
                ViewMode.DONE ->
                    renderDone(repo.resolvedGroups(today, doneShowAll), today, repo.routineProgress())
            }
        }
    }

    private fun renderOpen(
        sections: List<TaskSection>,
        today: LocalDate,
        progress: Map<String, RoutineProgress>,
    ) {
        val inflater = LayoutInflater.from(this)
        binding.tasksList.removeAllViews()
        binding.tasksEmpty.isVisible = sections.isEmpty()
        for ((index, section) in sections.withIndex()) {
            addSectionHeader(section.kind.label, topGap = index > 0)
            for (row in section.tasks) {
                if (row.type == TaskRowType.ROUTINE.name) {
                    addRoutineRow(inflater, row, dueLabel(row, today), progress[row.id])
                } else {
                    addTaskRow(inflater, row, dueLabel(row, today))
                }
            }
        }
    }

    /**
     * A routine on the main list: no checkbox, because a routine is finished by working through its
     * steps rather than by being ticked. The repeat glyph sits in the box's slot so rows stay
     * aligned and the two kinds are still distinguishable at a glance.
     */
    private fun addRoutineRow(
        inflater: LayoutInflater,
        routine: TaskEntity,
        dueLabel: String?,
        progress: RoutineProgress?,
    ) {
        val row = ItemTaskBinding.inflate(inflater, binding.tasksList, false)
        row.btnTaskState.setImageResource(R.drawable.ic_routine)
        row.btnTaskState.isClickable = false
        row.btnTaskState.contentDescription = "Routine"

        row.tvTaskTitle.text = routine.title
        row.tvTaskMeta.text = routineMeta(routine, progress)
        row.tvTaskMeta.isVisible = true
        row.tvTaskDue.text = dueLabel.orEmpty()
        row.tvTaskDue.isVisible = dueLabel != null

        row.taskRow.setOnClickListener { openRoutine(routine) }
        row.taskRow.setOnLongClickListener { showRoutineActions(routine); true }
        binding.tasksList.addView(row.root)
    }

    /** e.g. "Weekly · 2 of 5 done", or "Weekly · no steps yet" for one that has not been filled in. */
    private fun routineMeta(routine: TaskEntity, progress: RoutineProgress?): String {
        val total = progress?.total ?: 0
        return if (total == 0) "${rhythmOf(routine)} · no steps yet"
        else "${rhythmOf(routine)} · ${progress!!.resolved} of $total done"
    }

    /**
     * A finished routine in the Done view: the repeat glyph rather than a checkbox (it is still a
     * routine, and it was never ticked to get here), and no way to un-tick it — a finished routine
     * is final. Tapping opens the occurrence read-only; long-press offers only Delete, since editing
     * and reopening are both closed off.
     */
    private fun addResolvedRoutineRow(
        inflater: LayoutInflater,
        routine: TaskEntity,
        progress: RoutineProgress?,
    ) {
        val row = ItemTaskBinding.inflate(inflater, binding.tasksList, false)
        row.btnTaskState.setImageResource(R.drawable.ic_routine)
        row.btnTaskState.isClickable = false
        row.btnTaskState.contentDescription = "Routine"

        row.tvTaskTitle.text = routine.title
        row.tvTaskMeta.text = resolvedRoutineMeta(routine, progress)
        row.tvTaskMeta.isVisible = true
        row.tvTaskDue.isVisible = false

        row.taskRow.setOnClickListener { openRoutine(routine) }
        row.taskRow.setOnLongClickListener {
            ActionSheetDialog(this)
                .title(routine.title)
                .addAction(R.drawable.ic_trash, "Delete") { confirmDeleteRoutine(routine) }
                .show()
            true
        }
        binding.tasksList.addView(row.root)
    }

    /** e.g. "Skipped · Weekly · 3 done · 1 skipped" — what actually happened to the occurrence. */
    private fun resolvedRoutineMeta(routine: TaskEntity, progress: RoutineProgress?): String {
        val parts = mutableListOf<String>()
        if (TaskState.fromName(routine.state) == TaskState.SKIPPED) parts += TaskState.SKIPPED.label
        parts += rhythmOf(routine)
        val total = progress?.total ?: 0
        when {
            total == 0 -> parts += "no steps"
            else -> {
                if (progress!!.done > 0) parts += "${progress.done} done"
                if (progress.skipped > 0) parts += "${progress.skipped} skipped"
            }
        }
        return parts.joinToString(" · ")
    }

    private fun rhythmOf(routine: TaskEntity): String =
        TaskRecurrence.freqOf(routine.recurFreq)?.let { RoutinePeriod.label(it) } ?: "Routine"

    private fun renderDone(
        page: ResolvedPage,
        today: LocalDate,
        progress: Map<String, RoutineProgress>,
    ) {
        val inflater = LayoutInflater.from(this)
        binding.tasksList.removeAllViews()
        // Only truly empty when nothing is being withheld — otherwise "Nothing finished yet" would
        // be a lie told to someone with a year of finished tasks just out of frame.
        binding.tasksEmpty.isVisible = page.groups.isEmpty() && page.olderCount == 0
        for ((index, group) in page.groups.withIndex()) {
            addSectionHeader(resolvedGroupLabel(group.date, today), topGap = index > 0)
            for (row in group.tasks) {
                if (row.type == TaskRowType.ROUTINE.name) {
                    addResolvedRoutineRow(inflater, row, progress[row.id])
                } else {
                    addTaskRow(inflater, row, dueLabel = null)
                }
            }
        }
        if (page.olderCount > 0) addShowEarlierRow(page.olderCount, anythingAbove = page.groups.isNotEmpty())
    }

    /**
     * The way past the Done view's window. Shown only when something is actually behind it, and
     * labelled with the count so the tap has a known cost — this is the control that can turn a
     * cheap render into a few thousand inflated rows.
     */
    private fun addShowEarlierRow(olderCount: Int, anythingAbove: Boolean) {
        val row = TextView(this).apply {
            text = if (anythingAbove) {
                "Show $olderCount earlier"
            } else {
                "Nothing finished in the last ${TasksRepository.DONE_WINDOW_DAYS} days · show $olderCount earlier"
            }
            setTextColor(ContextCompat.getColor(this@TasksActivity, R.color.inkBlack))
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.shape_bordered)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(if (anythingAbove) 10 else 0) }
            setOnClickListener { doneShowAll = true; refresh() }
        }
        binding.tasksList.addView(row)
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
        // Only in All: say why the main list isn't showing this one, so it reads as held back rather
        // than misfiled. In the gated list every row is surfacing by definition, so it never applies.
        if (mode == ViewMode.ALL) hiddenNote(task)?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "Hidden until 24 Jul" for a task whose reminder window has not opened yet; null otherwise. */
    private fun hiddenNote(task: TaskEntity): String? {
        val from = visibleFrom(task) ?: return null
        if (from <= LocalDate.now().toEpochDay()) return null
        return "hidden until ${LocalDate.ofEpochDay(from).format(dueFmt)}"
    }

    private fun resolvedGroupLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(groupFmt)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Routines ───────────────────────────────────────────────────────────────

    /** Open a routine to work through its steps. */
    private fun openRoutine(routine: TaskEntity) = RoutineActivity.launch(this, routine.id)

    private fun openRoutineEditor(existing: TaskEntity?) {
        RoutineEditorDialog.show(
            activity = this,
            existing = existing,
            today = LocalDate.now(),
            onSaved = { title, freq ->
                lifecycleScope.launch {
                    if (existing == null) repo.createRoutine(title, freq, LocalDate.now())
                    else repo.renameRoutine(existing, title)
                    refresh()
                }
            },
            onDeleted = { routine -> confirmDeleteRoutine(routine) },
        )
    }

    private fun showRoutineActions(routine: TaskEntity) {
        ActionSheetDialog(this)
            .title(routine.title)
            .addAction(R.drawable.ic_edit, "Edit") { openRoutineEditor(routine) }
            .addAction(R.drawable.ic_checkbox_skipped, "Skip routine") { confirmSkipRoutine(routine) }
            .addAction(R.drawable.ic_trash, "Delete") { confirmDeleteRoutine(routine) }
            .show()
    }

    /** Skipping a routine resolves every step it still has open, so it is worth confirming. */
    private fun confirmSkipRoutine(routine: TaskEntity) {
        lifecycleScope.launch {
            val open = repo.members(routine.id).count { !TaskState.fromName(it.state).isResolved }
            val message = if (open == 0) {
                "Skip “${routine.title}” and move on to the next one?"
            } else {
                "Skip “${routine.title}”? Its $open remaining " +
                    (if (open == 1) "step" else "steps") + " will be marked skipped."
            }
            styled(
                androidx.appcompat.app.AlertDialog.Builder(this@TasksActivity)
                    .setTitle("Skip routine")
                    .setMessage(message)
                    .setPositiveButton("Skip") { _, _ ->
                        lifecycleScope.launch {
                            val next = repo.skipRoutine(routine, LocalDate.now())
                            refresh()
                            next?.dueEpochDay?.let {
                                toast("Next due ${LocalDate.ofEpochDay(it).format(dueFmt)}")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create(),
            )
        }
    }

    private fun confirmDeleteRoutine(routine: TaskEntity) {
        styled(
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete routine")
                .setMessage("Delete “${routine.title}”? Its steps are deleted with it.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch { repo.deleteRoutine(routine.id); refresh() }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

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
                ReopenOutcome.LOCKED ->
                    toast("A finished routine can't be reopened.")
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
        styled(
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete task")
                .setMessage("Delete “${task.title}”?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch { repo.delete(task.id); refresh() }
                }
                .setNegativeButton("Cancel", null)
                .create(),
        )
    }

    /** The standard e-ink dialog treatment: bordered window, no elevation. */
    private fun styled(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }
}
