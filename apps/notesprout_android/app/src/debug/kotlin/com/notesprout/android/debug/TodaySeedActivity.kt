package com.notesprout.android.debug

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.CalendarActivity
import com.notesprout.android.MainActivity
import com.notesprout.android.TasksActivity
import com.notesprout.android.TodayActivity
import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.EventPayload
import com.notesprout.android.data.events.EventType
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.events.RecurrenceRule
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.RoutinePeriod
import com.notesprout.android.data.tasks.TaskRowType
import com.notesprout.android.data.tasks.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * Debug-only: seeds the `tasks` table with dashboard test data, and removes it again.
 *
 * The Today dashboard's interesting cases — pagination, routine steps surfacing outside their
 * routine, a routine rolling forward when its last step is ticked, a row resolved *today* staying in
 * place — need a shape of data that a real library rarely has on any given day. This makes that
 * shape on demand.
 *
 * **Every row it writes is titled `[TEST] …`, and Clear removes exactly those and nothing else** —
 * matched on the prefix, hard-deleted rather than tombstoned, so a cleared library is back to
 * where it started with no invisible leftovers in the series history.
 *
 * It writes through the app's own open index, so encryption, WAL and the schema are handled by the
 * code that owns them; nothing here touches the database file directly. Debug source set — never
 * ships.
 *
 * adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.TodaySeedActivity
 */
class TodaySeedActivity : AppCompatActivity() {

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            text = "Today dashboard — test data\n"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            // No TopGuard on debug screens, so keep the first button off the status bar — on BOOX a
            // tap against the top edge pulls the shade down instead of hitting the control.
            setPadding(0, (48 * resources.displayMetrics.density).toInt(), 0, 0)
            addView(button("SEED — full (pagination + routines)") { seedFull() })
            addView(button("SEED — minimal (one group, no header)") { seedMinimal() })
            addView(button("CLEAR — remove every [TEST] row") { clear() })
            addView(ScrollView(this@TodaySeedActivity).apply { addView(log) })
        }
        setContentView(root)

        // Also driveable head-less, which is how it gets used from a shell:
        //   adb shell am start -n com.notesprout.android.dev/…TodaySeedActivity --es action full
        when (intent.getStringExtra("action")) {
            "full" -> seedFull()
            "minimal" -> seedMinimal()
            "clear" -> clear()
            // TodayActivity is exported="false", so a shell `am start` can't reach it. Launching it
            // from here can: this is the same UID, so the export check doesn't apply. Lets the
            // dashboard be opened straight from adb for layout work, without weakening the real
            // manifest.
            // Deliberately does NOT open the index — reproduces the state Android leaves a surface
            // in when it rebuilds the task after a background process kill, BootstrapActivity having
            // finished itself long ago.
            //   --es action raw --es target tasks|calendar|main|today
            "raw" -> {
                val target = when (intent.getStringExtra("target")) {
                    "tasks" -> TasksActivity::class.java
                    "calendar" -> CalendarActivity::class.java
                    "main" -> MainActivity::class.java
                    else -> TodayActivity::class.java
                }
                // NEW_TASK|CLEAR_TASK so the target really is created fresh as the task root —
                // without it `am start` just resumes whatever task already exists and the probe
                // measures the wrong activity.
                startActivity(
                    Intent(this, target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            // Which repositories throw when the index is closed? Tests the shared root cause
            // directly, with none of the task/activity noise a launch probe fights.
            "probe" -> probe()
            "open" -> lifecycleScope.launch {
                // Open the index first. Without this the dashboard finds it closed and (correctly)
                // bounces through BootstrapActivity, which lands on the library instead.
                withContext(Dispatchers.IO) { NotesproutIndex.ensureReady(this@TodaySeedActivity) }
                startActivity(TodayActivity.intent(this@TodaySeedActivity))
                finish()
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    // ── Seeds ──────────────────────────────────────────────────────────────────

    /**
     * The full set: enough rows to force several pages, two routines, and one of every state the
     * dashboard renders differently.
     */
    private fun seedFull() = run("Seeded") {
        val dao = NotesproutIndex.taskDao()
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        var written = 0

        suspend fun task(
            title: String,
            due: Long,
            state: TaskState = TaskState.NOT_DONE,
            parentId: String? = null,
            daily: Boolean = false,
        ): TaskEntity {
            val id = UUID.randomUUID().toString()
            val row = TaskEntity(
                id = id,
                parentId = parentId,
                type = TaskRowType.TASK_NAME,
                title = "$PREFIX $title",
                state = state.name,
                dueEpochDay = due,
                // A daily recurrence gives the "next due …" toast something to say when the row is
                // ticked — the one part of checking a task off that isn't self-evident.
                seriesId = if (daily) UUID.randomUUID().toString() else null,
                seriesIndex = if (daily) 0 else null,
                seriesAnchorDay = if (daily) due else null,
                recurFreq = if (daily) "DAILY" else null,
                recurInterval = if (daily) 1 else null,
                recurEndMode = if (daily) "NEVER" else null,
                resolvedAt = if (state.isResolved) now else null,
                createdAt = now,
                updatedAt = now,
            )
            dao.upsert(row)
            written++
            return row
        }

        // ── Overdue standalone ──
        task("Change the furnace filter", today - 3)
        task("Return the library books", today - 1)
        task("Call the plumber about the upstairs radiator that keeps knocking", today - 5)
        task("Renew the car tax", today - 2)
        task("Email Sam the quarterly numbers", today - 1)

        // ── Due today, standalone ──
        task("Pay the water bill", today)
        task("Water the ferns", today, daily = true)
        task("Book the dentist", today)
        task("Review the open pull request", today)
        task("Buy milk, bread, and something for Tuesday's dinner", today)
        task("Stretch for ten minutes", today)
        task("Back up the laptop", today)

        // ── Resolved today — these should stay in place, ticked, until midnight ──
        task("Morning pages", today, state = TaskState.DONE)
        task("Tidy the shed", today - 1, state = TaskState.SKIPPED)

        // ── A routine with several steps: they surface standalone, labelled, with no routine row ──
        // Due dates come from RoutinePeriod rather than being hardcoded, so re-seeding on any day of
        // the week still produces a routine whose deadline is the one the app would derive (a weekly
        // routine is due its Saturday). A step is clamped into the period the same way saveMember
        // would clamp it — seeding on a Sunday would otherwise put "yesterday" in the week before.
        val weeklyDue = RoutinePeriod.dueFor(Freq.WEEKLY, today)
        val weeklyStart = RoutinePeriod.startFor(Freq.WEEKLY, weeklyDue)
        val weekly = routine("Weekly reset", Freq.WEEKLY, weeklyDue, now)
        dao.upsert(weekly); written++
        task("Put the bins out", today, parentId = weekly.id)
        task("Hoover downstairs", today, parentId = weekly.id)
        task("Wipe down the counters", (today - 1).coerceAtLeast(weeklyStart), parentId = weekly.id)
        task("Sort the post", today, state = TaskState.DONE, parentId = weekly.id)

        // ── A routine with exactly ONE open step: ticking it completes and rolls the routine ──
        val daily = routine("Evening wind-down", Freq.DAILY, RoutinePeriod.dueFor(Freq.DAILY, today), now)
        dao.upsert(daily); written++
        task("Lights out by eleven", today, parentId = daily.id)

        val events = seedEvents(today, now)
        written += events

        "$written rows — ${written - events} tasks (2 routines + 5 steps + 14 standalone), " +
            "$events events"
    }

    /**
     * Today's events, in two parts.
     *
     * **The shapes** — one of each thing the row wording has to handle: all-day, timed with and
     * without an end, a multi-day span, and each recurrence frequency that renders a different
     * summary.
     *
     * **The bulk** — an ordinary over-full day, there only to make pages. Real dashboards will
     * rarely look like this, but a section that only ever holds two pages never exercises the middle
     * of the pager (where both arrows are live) and never shows a part-full last page. The times
     * deliberately interleave with the shape events above, so the render order is proof the sort ran
     * and not just the insert order.
     */
    private suspend fun seedEvents(today: Long, now: Long): Int {
        val dao = NotesproutIndex.eventDao()
        var written = 0

        suspend fun event(
            title: String,
            type: EventType,
            start: Long = today,
            end: Long = today,
            allDay: Boolean = false,
            startMinute: Int? = null,
            endMinute: Int? = null,
            rule: RecurrenceRule? = null,
        ) {
            dao.upsert(
                EventEntity(
                    id = UUID.randomUUID().toString(),
                    type = type.name,
                    title = "$PREFIX $title",
                    startEpochDay = start,
                    endEpochDay = end,
                    allDay = allDay,
                    startMinute = startMinute,
                    endMinute = endMinute,
                    // Mirrors data.recurrence != null — the DAO pulls only these for expansion.
                    recurring = rule != null,
                    data = EventPayload(recurrence = rule).toJson(),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                )
            )
            written++
        }

        // ── The shapes ──
        event("Standup", EventType.MEETING, startMinute = 9 * 60, endMinute = 9 * 60 + 15)
        event("Lunch with Sam", EventType.APPOINTMENT, startMinute = 12 * 60 + 30)
        event("Dentist", EventType.APPOINTMENT, startMinute = 14 * 60 + 30, endMinute = 15 * 60 + 15)
        event("Team retro", EventType.MEETING, startMinute = 16 * 60, endMinute = 17 * 60)
        event("Deploy window", EventType.OTHER, startMinute = 22 * 60)
        event("Pick up the parcel", EventType.OTHER, allDay = true)
        // Yearly, anchored decades back — the common birthday shape, and an O(1) recurrence check.
        // Anchored with real date arithmetic, NOT `today - 365 * 40`: forty 365-day years land ten
        // days short of the calendar date, and a YEARLY rule matches on month+day, so the event
        // would simply never occur today.
        val birthday = LocalDate.now().minusYears(40).toEpochDay()
        event(
            "Mum's birthday", EventType.BIRTHDAY, start = birthday, end = birthday,
            allDay = true, rule = RecurrenceRule(freq = Freq.YEARLY),
        )
        // Multi-day span straddling today, so the meta line has a date range to render.
        event(
            "Conference", EventType.VACATION, start = today - 1, end = today + 2, allDay = true,
        )
        // Weekly on today's own weekday (empty weekdays = the anchor's own day).
        event(
            "Bin day", EventType.OTHER, start = today - 70, end = today - 70, allDay = true,
            rule = RecurrenceRule(freq = Freq.WEEKLY),
        )
        // Monthly on today's own day-of-month, and an anniversary — the two remaining summaries.
        // Anchored with real month arithmetic for the same reason the birthday is: a DAY_OF_MONTH
        // rule matches on the calendar day, which fixed-length arithmetic drifts off.
        val lastQuarter = LocalDate.now().minusMonths(3).toEpochDay()
        event(
            "Month-end close", EventType.OTHER, start = lastQuarter, end = lastQuarter,
            allDay = true, rule = RecurrenceRule(freq = Freq.MONTHLY),
        )
        val anniversary = LocalDate.now().minusYears(12).toEpochDay()
        event(
            "Wedding anniversary", EventType.ANNIVERSARY, start = anniversary, end = anniversary,
            allDay = true, rule = RecurrenceRule(freq = Freq.YEARLY),
        )

        // ── The bulk ── an ordinary crowded day, purely to fill pages.
        event("Swim", EventType.OTHER, startMinute = 6 * 60 + 30, endMinute = 7 * 60 + 15)
        event("School run", EventType.OTHER, startMinute = 7 * 60 + 45)
        event("Coffee with Priya", EventType.APPOINTMENT, startMinute = 8 * 60 + 45, endMinute = 9 * 60)
        event("One-to-one with Alex", EventType.MEETING, startMinute = 10 * 60, endMinute = 10 * 60 + 30)
        event("Design review", EventType.MEETING, startMinute = 11 * 60, endMinute = 12 * 60)
        event("Physio", EventType.APPOINTMENT, startMinute = 13 * 60, endMinute = 13 * 60 + 45)
        event("Call the bank about the mortgage renewal", EventType.OTHER, startMinute = 13 * 60 + 30)
        event("Pick up the kids", EventType.OTHER, startMinute = 15 * 60 + 30)
        event("Guitar lesson", EventType.APPOINTMENT, startMinute = 17 * 60 + 30, endMinute = 18 * 60 + 15)
        event("Book club", EventType.MEETING, startMinute = 18 * 60 + 30)
        event("Dinner at the Nawabs'", EventType.APPOINTMENT, startMinute = 19 * 60)
        event("Call Mum", EventType.OTHER, startMinute = 20 * 60)
        event("Read a chapter", EventType.OTHER, startMinute = 21 * 60)
        return written
    }

    /** Just enough to land in one group, so the single-group "no header" case is visible. */
    private fun seedMinimal() = run("Seeded") {
        val dao = NotesproutIndex.taskDao()
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        for (title in listOf("Pay the water bill", "Book the dentist")) {
            dao.upsert(
                TaskEntity(
                    id = UUID.randomUUID().toString(),
                    type = TaskRowType.TASK_NAME,
                    title = "$PREFIX $title",
                    state = TaskState.NOT_DONE.name,
                    dueEpochDay = today,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
        "2 rows, both due today"
    }

    private fun routine(title: String, freq: Freq, due: Long, now: Long) = TaskEntity(
        id = UUID.randomUUID().toString(),
        type = TaskRowType.ROUTINE.name,
        title = "$PREFIX $title",
        state = TaskState.NOT_DONE.name,
        dueEpochDay = due,
        seriesId = UUID.randomUUID().toString(),
        seriesIndex = 0,
        seriesAnchorDay = due,
        recurFreq = freq.name,
        recurInterval = 1,
        recurEndMode = EndMode.NEVER.name,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Does each surface's repository survive a **closed** index?
     *
     * Every one of these resolves its DAO in a default constructor argument, which is evaluated at
     * construction — inside the activities' `by lazy`, i.e. on their first read, i.e. in `onResume`.
     * If that throws, the surface dies. Run this in a fresh process, before anything opens the
     * index, to see which are exposed.
     */
    private fun probe() {
        check(!NotesproutIndex.isReady()) { "index already open — force-stop first for a valid probe" }
        val results = listOf<Pair<String, () -> Any>>(
            "TasksRepository" to { com.notesprout.android.data.TasksRepository() },
            "TodayRepository" to { com.notesprout.android.data.TodayRepository() },
            "EventsRepository" to { com.notesprout.android.data.EventsRepository() },
            // These two take their DAO explicitly; built exactly as CalendarActivity:350 and
            // ScratchpadActivity:242 build them — in onCreate, not lazily.
            "CalendarRepository" to {
                com.notesprout.android.data.CalendarRepository(
                    NotesproutIndex.db(), NotesproutIndex.calendarDao(),
                )
            },
            "ScratchpadRepository" to {
                com.notesprout.android.data.ScratchpadRepository(
                    NotesproutIndex.db(), NotesproutIndex.scratchpadDao(),
                )
            },
            "DayHistoryRepository" to { com.notesprout.android.data.DayHistoryRepository() },
            "IndexRepository" to {
                com.notesprout.android.data.index.IndexRepository(NotesproutIndex.dao())
            },
        ).map { (name, make) ->
            val verdict = runCatching { make() }
                .fold({ "ok" }, { "THROWS: ${it::class.simpleName}" })
            "$name: $verdict"
        }
        log.append("\n" + results.joinToString("\n"))
        results.forEach { android.util.Log.w("IndexProbe", it) }
    }

    // ── Clear ──────────────────────────────────────────────────────────────────

    /**
     * Remove every seeded row, matched on the `[TEST]` title prefix.
     *
     * **Hard delete, not a soft one.** These rows were never user content, and a tombstone would sit
     * in the table forever — including inside a recurring series, where an invisible row is exactly
     * the thing [com.notesprout.android.data.TasksRepository.reopen] takes such care to avoid
     * leaving behind.
     *
     * Rows a routine generated by rolling forward during testing are caught too: their titles are
     * copied from the steps they came from, so they carry the prefix as well.
     */
    private fun clear() = run("Cleared") {
        val raw = NotesproutIndex.db().openHelper.writableDatabase
        val taskIds = mutableListOf<String>()
        raw.query("SELECT id FROM tasks WHERE title LIKE ?", arrayOf("$PREFIX %")).use { c ->
            while (c.moveToNext()) taskIds += c.getString(0)
        }
        val dao = NotesproutIndex.taskDao()
        for (id in taskIds) dao.hardDelete(id)

        // Events are hard-deleted straight through the raw connection: EventDao offers only a soft
        // delete (correct for user content — the recurrence engine needs tombstones to behave), and
        // a soft-deleted seed row would linger in the table forever.
        val events = raw.delete("events", "title LIKE ?", arrayOf("$PREFIX %"))

        "${taskIds.size} task rows, $events event rows"
    }

    // ── Plumbing ───────────────────────────────────────────────────────────────

    private fun run(verb: String, block: suspend () -> String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // The index may not be open if this screen was launched straight from adb.
                    NotesproutIndex.ensureReady(this@TodaySeedActivity)
                    if (!NotesproutIndex.isReady()) {
                        return@runCatching "index is locked — open the app and unlock first"
                    }
                    "$verb: ${block()}"
                }.getOrElse { "FAILED: ${it.message}" }
            }
            log.append("\n$result")
        }
    }

    private companion object {
        /** Every seeded title starts with this, and Clear matches on nothing else. */
        const val PREFIX = "[TEST]"
    }
}
