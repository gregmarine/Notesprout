package com.notesprout.android.data

import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TaskDao
import com.notesprout.android.data.index.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * The reads behind the **Today** dashboard.
 *
 * It owns no data of its own: every section is a differently-shaped question put to a store that
 * already exists ([TaskDao] here, events and the activity log in later sections). What it does own is
 * the dashboard's definition of "today", which is narrower than any other screen's and is the whole
 * reason this sits apart from [TasksRepository] rather than inside it.
 */
class TodayRepository(
    private val taskDao: TaskDao = NotesproutIndex.taskDao(),
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
    }
}

/**
 * One task on the dashboard. [routineName] is the routine this row is a step of, or null for a
 * standalone task — it is what lets a step appear outside its routine without losing where it came
 * from.
 */
data class TodayTask(val task: TaskEntity, val routineName: String?)

/**
 * One labelled run of rows in a dashboard section. The label is always supplied; the section decides
 * whether to draw it (a lone group needs no header — the section's own title already says what it
 * is).
 */
data class TodayGroup<T>(val label: String, val items: List<T>)
