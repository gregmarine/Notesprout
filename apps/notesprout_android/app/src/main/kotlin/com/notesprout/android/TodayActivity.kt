package com.notesprout.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.TopGuard
import com.notesprout.android.data.EventsRepository
import com.notesprout.android.data.TasksRepository
import com.notesprout.android.databinding.ActivityTodayBinding
import com.notesprout.android.databinding.ViewTodaySectionBinding
import com.notesprout.android.state.AppSurface
import com.notesprout.android.state.SurfaceEntry
import com.notesprout.android.state.SurfaceStack
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /** True when every section is on screen at once — see `R.bool.today_single_screen`. */
    private var singleScreen = false

    private var tab = Tab.TASKS

    /** This Activity instance's identity on the [SurfaceStack]. */
    private var surfaceToken: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    /**
     * Name each section and wire its create action. The lists themselves arrive in later phases;
     * for now every section renders its empty state.
     */
    private fun setupSections() {
        title(binding.sectionTasks, "Tasks", "New task", "Nothing due today") { newTask() }
        title(binding.sectionEvents, "Events", "New event", "Nothing on today") { newEvent() }
        title(binding.sectionNotebooks, "Notebooks", "New notebook", "Nothing opened today") {
            newNotebook()
        }
    }

    private fun title(
        section: ViewTodaySectionBinding,
        name: String,
        addHint: String,
        empty: String,
        onAdd: () -> Unit,
    ) {
        section.tvSectionTitle.text = name
        // Doubles as the long-press hint — Android surfaces contentDescription as a tooltip, which
        // is what keeps a bare glyph learnable on e-ink (see the design system).
        section.btnSectionAdd.contentDescription = addHint
        section.btnSectionAdd.setOnClickListener { onAdd() }
        section.sectionEmpty.text = empty
        section.sectionEmpty.isVisible = true
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

        // The three section lists land in phases 2–4. Until then each renders its empty state, set
        // once in setupSections.
    }

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
     * does for the calendar's New Notebook button. Reused verbatim, including its `finish()`: the
     * flow ends in the library with the new notebook, not back on a dashboard behind it.
     */
    private fun newNotebook() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START_NEW_NOTEBOOK, true)
        )
        finish()
    }
}
