package com.notesprout.android.data

import android.content.Context
import com.notesprout.android.data.index.EventEntity
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TaskDao
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.recents.RecentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The reads behind the **Today** dashboard.
 *
 * It owns no data of its own: every section is a differently-shaped question put to a store that
 * already exists — [TaskDao], [EventsRepository], the `notebook_activity` log behind
 * [DayHistoryRepository], and the device-local [RecentsManager]. What it does own is the dashboard's
 * definition of "today", which is narrower than any other screen's and is the whole reason this sits
 * apart from [TasksRepository] rather than inside it.
 */
class TodayRepository(
    private val taskDao: TaskDao = NotesproutIndex.taskDao(),
    private val eventsRepo: EventsRepository = EventsRepository(),
    private val dayHistory: DayHistoryRepository = DayHistoryRepository(),
) {

    /**
     * The day's tasks: everything **overdue or due today** that is still open, plus everything
     * resolved today, grouped [TaskSectionKind.OVERDUE] then [TaskSectionKind.TODAY].
     *
     * Two things make this different from the Tasks screen's own Today view:
     *
     * - **Routine steps are here, standalone, and routines are not.** A step is real work due today
     *   whether or not its routine is open, so it surfaces on its own — carrying [TodayTask.routineName]
     *   so it still reads as part of something. Its parent routine is deliberately absent: it would
     *   be the same work counted twice.
     * - **Resolved rows stay, exactly where they were.** A task ticked today keeps its position —
     *   see [ROW_ORDER] — so the day's progress is visible and a mis-tap is undone where it
     *   happened rather than a page away. They are gone tomorrow.
     *
     * Undated and future-dated tasks are not here at all; the Tasks screen is one tap away and holds
     * the whole picture.
     */
    suspend fun tasks(today: LocalDate): List<TodayGroup<TodayTask>> = withContext(Dispatchers.IO) {
        val day = today.toEpochDay()
        val zone = ZoneId.systemDefault()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val routineNames = taskDao.routineTitles().associate { it.id to it.title }
        val rows = (taskDao.openDueBy(day) + taskDao.resolvedOnDay(dayStart, dayEnd, day))
            .map { TodayTask(it, it.parentId?.let(routineNames::get)) }

        listOf(TaskSectionKind.OVERDUE, TaskSectionKind.TODAY).mapNotNull { kind ->
            val items = rows.filter { kindOf(it.task, day) == kind }.sortedWith(ROW_ORDER)
            if (items.isEmpty()) null else TodayGroup(kind.label, items)
        }
    }

    /**
     * The day's events — those actually **on** today, direct and recurring alike, all-day first then
     * by start time (the ordering [EventsRepository.eventsForDay] already applies).
     *
     * **No look-ahead.** The day window's Events view also carries reminder-gated *Upcoming* rows;
     * the dashboard deliberately does not. "Today" here means today, and an event you asked to be
     * warned about a week early is exactly the sort of thing that would crowd out what is actually
     * happening now. The look-ahead stays where it already lives, one tap away.
     *
     * One group, so [com.notesprout.android.TodaySection] draws no header for it — the section is
     * already called Events.
     */
    suspend fun events(today: LocalDate): List<TodayGroup<EventEntity>> {
        val rows = eventsRepo.eventsForDay(today)
        return if (rows.isEmpty()) emptyList() else listOf(TodayGroup("Today", rows))
    }

    /**
     * The day's notebooks: everything touched today under **Today**, then the most recent notebooks
     * that weren't, under **Recent**.
     *
     * The second group is the part that isn't obvious. A dashboard that only listed today's activity
     * would be blank every morning — precisely when a jump point is most useful. So the list is
     * topped up from [RecentsManager] to [RECENT_LIMIT] entries, **deduped against today's rows** so
     * a notebook opened this morning appears once, in Today, and not again below.
     *
     * The two groups are always labelled (see [com.notesprout.android.TodaySection]'s
     * `alwaysLabelGroups`): "Notebooks" names a category, not a time, so an unlabelled lone Recent
     * group would read as work done today.
     *
     * [context] is here because recents are device-local `SharedPreferences`, not index data — the
     * one store on this screen that isn't a database.
     */
    suspend fun notebooks(context: Context, today: LocalDate): List<TodayGroup<TodayNotebook>> =
        withContext(Dispatchers.IO) {
            val todayRows = dayHistory.notebooksForDay(today).map {
                TodayNotebook(
                    id = it.notebookId,
                    name = it.notebookName,
                    folderPath = it.folderPath,
                    timestamp = it.timestamp,
                    activity = it.activityLabel,
                    locked = dayHistory.coverFor(it.notebookId).locked,
                )
            }
            val seen = todayRows.map { it.id }.toSet()
            val recentRows = RecentsManager.resolve(context)
                .filter { it.notebookId !in seen }
                .take(RECENT_LIMIT)
                .map {
                    TodayNotebook(
                        id = it.notebookId,
                        name = it.notebookName,
                        folderPath = it.folderPath,
                        timestamp = it.timestamp,
                        // Null is what marks a row as *not* today's — the renderer shows a relative
                        // day where a Today row shows what happened to it.
                        activity = null,
                        locked = dayHistory.coverFor(it.notebookId).locked,
                    )
                }

            listOfNotNull(
                todayRows.takeIf { it.isNotEmpty() }?.let { TodayGroup("Today", it) },
                recentRows.takeIf { it.isNotEmpty() }?.let { TodayGroup("Recent", it) },
            )
        }

    private fun kindOf(task: TaskEntity, todayEpochDay: Long): TaskSectionKind =
        if ((task.dueEpochDay ?: todayEpochDay) < todayEpochDay) TaskSectionKind.OVERDUE
        else TaskSectionKind.TODAY

    private companion object {
        /**
         * Due day, then title. **State is deliberately not a sort key**, so ticking a task changes
         * its checkbox and nothing else.
         *
         * Sorting resolved rows to the bottom of their group reads well on paper and is wrong here.
         * The list is paginated, so a row that sinks on being ticked can sink onto *another page* —
         * and being able to un-tick a mis-tap where it happened is the entire reason resolved rows
         * stay on this screen at all. Sending them somewhere else to do it gives up the only thing
         * the behaviour was for.
         *
         * It is also how paper behaves: a checklist does not reflow when you tick something.
         */
        val ROW_ORDER: Comparator<TodayTask> =
            compareBy<TodayTask> { it.task.dueEpochDay ?: Long.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.task.title }

        /**
         * How many *Recent* notebooks top up the list. Half of [RecentsManager.MAX_ENTRIES], because
         * this is a jump point rather than a history: the notebooks you actually return to sit at the
         * top of that store, and the tail is better served by the library's own Recents mode.
         */
        const val RECENT_LIMIT = 10
    }
}

/**
 * One task on the dashboard. [routineName] is the routine this row is a step of, or null for a
 * standalone task — it is what lets a step appear outside its routine without losing where it came
 * from.
 */
data class TodayTask(val task: TaskEntity, val routineName: String?)

/**
 * One notebook on the dashboard.
 *
 * @property activity what happened to it today — `"created · opened · edited"` — or **null** for a
 *   *Recent* row, which is the flag distinguishing the two groups.
 * @property timestamp the newest activity today, or when it was last opened for a Recent row.
 * @property locked NOTEBOOK-scope (private) encryption, so opening it will ask for a passphrase.
 *   GLOBAL-scope notebooks are not locked — the index key already covers them.
 */
data class TodayNotebook(
    val id: String,
    val name: String,
    val folderPath: String,
    val timestamp: Long,
    val activity: String?,
    val locked: Boolean,
)

/**
 * One labelled run of rows in a dashboard section. The label is always supplied; the section decides
 * whether to draw it (a lone group needs no header — the section's own title already says what it
 * is).
 */
data class TodayGroup<T>(val label: String, val items: List<T>)
