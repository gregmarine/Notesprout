package com.notesprout.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.IndexGuard
import com.notesprout.android.core.TopGuard
import com.notesprout.android.data.EventsRepository
import com.notesprout.android.data.ReopenOutcome
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.data.TodayNotebook
import com.notesprout.android.data.TodayRepository
import com.notesprout.android.data.TodayTask
import com.notesprout.android.data.events.EventRowFormat
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.TaskState
import com.notesprout.android.databinding.ActivityTodayBinding
import com.notesprout.android.databinding.ItemEventBinding
import com.notesprout.android.databinding.ItemTaskBinding
import com.notesprout.android.databinding.ItemTodayNotebookBinding
import com.notesprout.android.state.AppSurface
import com.notesprout.android.state.SurfaceEntry
import com.notesprout.android.state.SurfaceStack
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * **Today** — the dashboard: a focused view of the day, and the jump point for the rest of the app.
 *
 * It shows what today asks of you — tasks overdue or due now, events on the day, and the notebooks
 * that have been touched — and takes you to whichever surface owns the thing you tapped. It has
 * **no drawing surface** and does not edit: the single exception is the task state box, which checks
 * a task off and un-checks one resolved today.
 *
 * Two shapes, chosen by `R.bool.today_single_screen` (sw600dp):
 *
 * - **Single screen** — Tasks | Events side by side over a full-width Notebooks band.
 * - **Tabbed** — the same three sections one at a time, behind a tab row.
 *
 * Both come from layouts carrying an identical id set, so nothing below branches on the variant
 * beyond deciding whether the tab row is live.
 *
 * Like [TasksActivity] this screen keeps the system bars visible — there is no canvas that wants to
 * be full-bleed and no drawing engine at all — so the top guard comes from the live inset
 * ([TopGuard.applyInsetPadding]) rather than the fixed reservation the drawing screens use.
 */
class TodayActivity : AppCompatActivity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(intent(context))
        }

        fun intent(context: Context): Intent = Intent(context, TodayActivity::class.java)
    }

    /** Which section a tabbed device is showing. Ignored entirely on the single screen. */
    private enum class Tab { TASKS, EVENTS, NOTEBOOKS }

    private lateinit var binding: ActivityTodayBinding

    private val tasksRepo by lazy { TasksRepository() }
    private val eventsRepo by lazy { EventsRepository() }
    private val todayRepo by lazy { TodayRepository() }

    private lateinit var tasksSection: TodaySection<TodayTask>
    private lateinit var eventsSection: TodaySection<EventEntity>
    private lateinit var notebooksSection: TodaySection<TodayNotebook>

    private val dueFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    /** The device's own 12/24-hour and date preferences, as every other list in the app uses them. */
    private val clockFmt by lazy { DateFormat.getTimeFormat(this) }
    private val dateFmt by lazy { DateFormat.getMediumDateFormat(this) }

    /** Row wording, shared with the day window's Events list so the two never disagree. */
    private val eventFormat by lazy { EventRowFormat(this) }

    /** True when every section is on screen at once — see `R.bool.today_single_screen`. */
    private var singleScreen = false

    private var tab = Tab.TASKS

    /** This Activity instance's identity on the [SurfaceStack]. */
    private var surfaceToken: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing has opened the index if Android rebuilt this task itself — see IndexGuard.
        if (!IndexGuard.ready(this)) return
        binding = ActivityTodayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        singleScreen = resources.getBoolean(R.bool.today_single_screen)

        // Record the dashboard on the surface stack, so a cold launch reopens it (see SurfaceStack).
        // The entry carries no payload: the screen reads everything fresh from "now" every time.
        surfaceToken = savedInstanceState?.getString(SurfaceStack.KEY_TOKEN)
            ?: UUID.randomUUID().toString()
        SurfaceStack.attach(this, surfaceEntry())

        binding.btnTodayBack.setOnClickListener { finish() }
        binding.btnTodayCalendar.setOnClickListener { CalendarActivity.launch(this) }
        binding.btnTodayTasks.setOnClickListener { TasksActivity.launch(this) }
        binding.btnTodayScratchpad.setOnClickListener {
            startActivity(Intent(this, ScratchpadActivity::class.java))
        }

        binding.btnTabTasks.setOnClickListener { switchTab(Tab.TASKS) }
        binding.btnTabEvents.setOnClickListener { switchTab(Tab.EVENTS) }
        binding.btnTabNotebooks.setOnClickListener { switchTab(Tab.NOTEBOOKS) }

        setupSections()
        applyTab()
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
     * Every row on this screen is relative to *today*, so the day rolling over silently invalidates
     * all of it. [onResume] re-reads the date, which covers closing the app one day and opening it
     * the next; this covers the case that would otherwise go stale indefinitely — the screen left
     * open *across* midnight, easy to do on a device that is never really switched off. Same
     * reasoning, and the same receiver, as [TasksActivity].
     */
    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SurfaceStack.KEY_TOKEN, surfaceToken)
    }

    private fun surfaceEntry() = SurfaceEntry(surfaceToken, AppSurface.TODAY)


    // ── Sections ───────────────────────────────────────────────────────────────

    /** Name each section, wire its create action, and say how one of its rows is built. */
    private fun setupSections() {
        tasksSection = TodaySection(
            ui = binding.sectionTasks,
            title = "Tasks",
            addHint = "New task",
            emptyText = "Nothing due today",
            onAdd = { newTask() },
            makeRow = { row -> taskRow(row) },
        )
        eventsSection = TodaySection(
            ui = binding.sectionEvents,
            title = "Events",
            addHint = "New event",
            emptyText = "Nothing on today",
            onAdd = { newEvent() },
            makeRow = { event -> eventRow(event) },
        )
        notebooksSection = TodaySection(
            ui = binding.sectionNotebooks,
            title = "Notebooks",
            addHint = "New notebook",
            // Only reachable with an empty library: any notebook ever opened stays in recents.
            emptyText = "No notebooks yet",
            onAdd = { newNotebook() },
            makeRow = { notebook -> notebookRow(notebook) },
            // "Notebooks" names a category, not a time. Left unlabelled, a lone Recent group would
            // read as today's work — see TodaySection.alwaysLabelGroups.
            alwaysLabelGroups = true,
        )
    }

    // ── Tabs ───────────────────────────────────────────────────────────────────

    private fun switchTab(next: Tab) {
        if (tab == next) return
        tab = next
        applyTab()
    }

    /**
     * Show the tab row and exactly one section — or, on the single screen, all three and no tabs.
     */
    private fun applyTab() {
        binding.todayTabs.isVisible = !singleScreen
        if (singleScreen) return

        binding.btnTabTasks.isSelected = tab == Tab.TASKS
        binding.btnTabEvents.isSelected = tab == Tab.EVENTS
        binding.btnTabNotebooks.isSelected = tab == Tab.NOTEBOOKS

        binding.sectionTasks.root.isVisible = tab == Tab.TASKS
        binding.sectionEvents.root.isVisible = tab == Tab.EVENTS
        binding.sectionNotebooks.root.isVisible = tab == Tab.NOTEBOOKS
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun refresh() {
        val today = LocalDate.now()
        // FULL on the single screen ("Tuesday, 4 August 2026"), MEDIUM where the bar is tight.
        val style = if (singleScreen) FormatStyle.FULL else FormatStyle.MEDIUM
        binding.tvTodayDate.text =
            today.format(DateTimeFormatter.ofLocalizedDate(style).withLocale(Locale.getDefault()))

        lifecycleScope.launch {
            tasksSection.submit(todayRepo.tasks(today))
            eventsSection.submit(todayRepo.events(today))
            notebooksSection.submit(todayRepo.notebooks(this@TodayActivity, today))
        }
    }

    /**
     * Re-read the tasks section alone — for the check-off, which is the one thing that happens
     * *on* this screen rather than to it.
     *
     * Ticking a task cannot change an event or a notebook, and a full [refresh] is not free: the
     * notebooks read alone walks the folder tree three times, lists every notebook in the library,
     * and looks up a row per card. Paying that on every checkbox tap is work the user waits for on a
     * device that repaints slowly. Everything else still refreshes on resume, on a date change, and
     * on return from any surface this screen opens.
     */
    private fun refreshTasks() {
        lifecycleScope.launch { tasksSection.submit(todayRepo.tasks(LocalDate.now())) }
    }

    // ── Notebook rows ──────────────────────────────────────────────────────────

    /**
     * One notebook. The whole row opens it through the ordinary [NotebookActivity] path, so an
     * encrypted notebook meets its usual unlock prompt — the dashboard knows nothing about keys
     * beyond drawing the lock.
     */
    private fun notebookRow(notebook: TodayNotebook): View {
        val item = ItemTodayNotebookBinding.inflate(
            layoutInflater, binding.sectionNotebooks.sectionList, false,
        )
        item.ivNotebookIcon.setImageResource(
            if (notebook.locked) R.drawable.ic_lock else R.drawable.ic_notebook
        )
        item.ivNotebookIcon.contentDescription =
            if (notebook.locked) "Encrypted notebook" else "Notebook"

        item.tvNotebookName.text = notebook.name
        item.tvNotebookMeta.text = notebookMeta(notebook)
        item.tvNotebookTime.text = notebookTime(notebook)

        item.notebookRow.setOnClickListener {
            // Tap-time "Opening…" overlay; the destination keeps it up until its first page renders.
            com.notesprout.android.core.OpeningOverlay.showThen(this) {
                startActivity(
                    Intent(this, NotebookActivity::class.java)
                        .putExtra(NotebookActivity.EXTRA_NOTEBOOK_ID, notebook.id)
                        .putExtra(NotebookActivity.EXTRA_NOTEBOOK_NAME, notebook.name)
                )
            }
        }
        return item.root
    }

    /**
     * Where the notebook lives, and — for a Today row — what happened to it there.
     *
     * Only the **last** folder segment, not the full breadcrumb: it is what tells two same-named
     * notebooks apart, and a deep path would swallow the activity that follows it. The library is
     * where the whole tree is worth seeing.
     */
    private fun notebookMeta(notebook: TodayNotebook): String {
        val folder = notebook.folderPath.substringAfterLast(" › ")
        return notebook.activity?.let { "$folder · $it" } ?: folder
    }

    /**
     * The trailing label: a clock time for something touched today, a relative day for a Recent row
     * — and a date once "N days ago" stops being easier to read than the date itself.
     */
    private fun notebookTime(notebook: TodayNotebook): String {
        if (notebook.activity != null) return clockFmt.format(Date(notebook.timestamp))
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(notebook.timestamp).atZone(ZoneId.systemDefault()).toLocalDate(),
            LocalDate.now(),
        )
        return when {
            days <= 0L -> clockFmt.format(Date(notebook.timestamp))
            days == 1L -> "Yesterday"
            days <= 7L -> "${days}d ago"
            else -> dateFmt.format(Date(notebook.timestamp))
        }
    }

    // ── Event rows ─────────────────────────────────────────────────────────────

    /**
     * One event, in the same row layout the day window uses — minus its delete button. Nothing is
     * edited here: a tap opens the day window, which owns editing and the recurring-scope prompts
     * that go with it ("this occurrence / this and following / all"). Reproducing any of that on a
     * focus view would be a second place to get it wrong.
     */
    private fun eventRow(event: EventEntity): View {
        val item = ItemEventBinding.inflate(layoutInflater, binding.sectionEvents.sectionList, false)
        item.tvEventTime.text = eventFormat.time(event)
        item.tvEventTitle.text = event.title
        item.tvEventMeta.text = eventFormat.meta(event)
        item.btnEventDelete.isVisible = false
        item.eventRow.setOnClickListener { openDayWindow() }
        return item.root
    }

    /**
     * Today's day window — where an event can actually be edited.
     *
     * No view is passed: a normal open already lands on **Events**, and `EXTRA_VIEW` exists only for
     * launch restore. Nothing here is coupled to the day window's own view enum, which is private to
     * it.
     *
     * No source notebook either — the dashboard is not one, so Send-to-Notebook there falls back to
     * the picker rather than offering "this notebook".
     */
    private fun openDayWindow() {
        startActivity(
            DayDetailActivity.intent(
                this,
                LocalDate.now(),
                fromNotebookId = null,
                fromNotebookName = null,
            )
        )
    }

    // ── Task rows ──────────────────────────────────────────────────────────────

    /**
     * One task. The state box is the screen's only edit; the rest of the row jumps to wherever the
     * task actually lives, which for a routine step is its routine rather than the task list.
     */
    private fun taskRow(row: TodayTask): View {
        val task = row.task
        val state = TaskState.fromName(task.state)
        val item = ItemTaskBinding.inflate(layoutInflater, binding.sectionTasks.sectionList, false)

        item.btnTaskState.setImageResource(
            when (state) {
                TaskState.NOT_DONE -> R.drawable.ic_checkbox_empty
                TaskState.DONE -> R.drawable.ic_checkbox_checked
                TaskState.SKIPPED -> R.drawable.ic_checkbox_skipped
            }
        )
        item.btnTaskState.contentDescription = if (state.isResolved) "Mark not done" else "Mark done"
        item.btnTaskState.setOnClickListener {
            if (state.isResolved) reopenTask(task) else completeTask(row)
        }

        item.tvTaskTitle.text = task.title
        val meta = taskMeta(row, state)
        item.tvTaskMeta.text = meta.orEmpty()
        item.tvTaskMeta.isVisible = meta != null

        val overdue = overdueLabel(task)
        item.tvTaskDue.text = overdue.orEmpty()
        item.tvTaskDue.isVisible = overdue != null

        item.taskRow.setOnClickListener { openTaskHome(task) }
        return item.root
    }

    /**
     * The meta line, kept deliberately spare: the routine a step belongs to, and the word "Skipped"
     * where that is what happened. Recurrence summaries and reminder notes stay on the Tasks screen —
     * the dashboard's job is to say what is left, not to explain each row's rules.
     */
    private fun taskMeta(row: TodayTask, state: TaskState): String? {
        val parts = mutableListOf<String>()
        if (state == TaskState.SKIPPED) parts += state.label
        row.routineName?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** How late a row is. Nothing at all inside Today — the group header already said it. */
    private fun overdueLabel(task: TaskEntity): String? {
        val due = task.dueEpochDay ?: return null
        val delta = due - LocalDate.now().toEpochDay()
        return when {
            delta >= 0L -> null
            delta == -1L -> "Yesterday"
            else -> "${-delta}d ago"
        }
    }

    /** Where this task lives: a step belongs to its routine, everything else to the task list. */
    private fun openTaskHome(task: TaskEntity) {
        val routineId = task.parentId
        if (routineId != null) RoutineActivity.launch(this, routineId) else TasksActivity.launch(this)
    }

    /**
     * Check a task off. A routine step goes through [TasksRepository.resolveMember], so ticking the
     * last open step completes its routine and rolls it forward exactly as it would inside
     * [RoutineActivity] — a step is a step wherever it is ticked. Unlike that screen this one stays
     * put and simply refreshes; there is nothing to step out of.
     */
    private fun completeTask(row: TodayTask) {
        lifecycleScope.launch {
            val today = LocalDate.now()
            val task = row.task
            if (task.parentId != null) {
                val outcome = tasksRepo.resolveMember(task, TaskState.DONE, today)
                refreshTasks()
                if (outcome.routineCompleted) {
                    val next = outcome.nextRoutineDue
                        ?.let { " · next due ${LocalDate.ofEpochDay(it).format(dueFmt)}" }.orEmpty()
                    toast("${row.routineName ?: "Routine"} complete$next")
                }
            } else {
                val successor = tasksRepo.complete(task, today)
                refreshTasks()
                // Only recurring tasks produce one, and its date is the non-obvious part: the row
                // just ticked stays put, but its replacement is dated somewhere the dashboard may
                // not be showing at all.
                successor?.dueEpochDay?.let {
                    toast("Next due ${LocalDate.ofEpochDay(it).format(dueFmt)}")
                }
            }
        }
    }

    /** Un-tick a task resolved today — the undo for a mis-tap, available where the mis-tap happened. */
    private fun reopenTask(task: TaskEntity) {
        lifecycleScope.launch {
            when (tasksRepo.reopen(task)) {
                ReopenOutcome.REOPENED -> refreshTasks()
                ReopenOutcome.SERIES_MOVED_ON ->
                    toast("Later occurrences of this task have already been dealt with.")
                ReopenOutcome.LOCKED ->
                    toast("This routine is finished — its steps can't be changed.")
            }
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ── Create actions ─────────────────────────────────────────────────────────

    private fun newTask() {
        TaskEditorDialog.show(
            activity = this,
            existing = null,
            onSaved = { task -> lifecycleScope.launch { tasksRepo.save(task); refresh() } },
        )
    }

    private fun newEvent() {
        val today = LocalDate.now()
        EventEditorDialog.show(
            activity = this,
            date = today,
            existing = null,
            onSaved = { event -> lifecycleScope.launch { eventsRepo.save(event); refresh() } },
        )
    }

    /**
     * The library's own new-notebook flow: pick a destination folder, then name + template.
     *
     * The dashboard has no "current folder", so the folder picker is not optional here the way it is
     * on the library screen — which is exactly what [MainActivity.EXTRA_START_NEW_NOTEBOOK] already
     * does for the calendar's New Notebook button. Choosing that folder *is* a mode of the library's
     * grid, and this screen has no browsing by design, so the flow genuinely belongs over there.
     *
     * Handing it over costs this Activity: `CLEAR_TOP` onto the root library pops the dashboard, and
     * the `finish()` covers the case where the library isn't below us to be cleared to. So
     * [MainActivity.EXTRA_RETURN_TO_TODAY] asks for it back — the library rebuilds the dashboard
     * beneath the new notebook, and closing that notebook returns here, exactly as it does when an
     * existing notebook is opened from this list.
     *
     * **Cancelling the flow still ends in the library**, since nothing is created and there is no
     * notebook to sit under. Same as the calendar's button, which has always behaved this way.
     */
    private fun newNotebook() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START_NEW_NOTEBOOK, true)
                .putExtra(MainActivity.EXTRA_RETURN_TO_TODAY, true)
        )
        finish()
    }
}
