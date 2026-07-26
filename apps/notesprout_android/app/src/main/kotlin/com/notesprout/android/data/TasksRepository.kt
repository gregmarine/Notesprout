package com.notesprout.android.data

import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TaskDao
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.TaskRecurrence
import com.notesprout.android.data.tasks.TaskReminders
import com.notesprout.android.data.tasks.TaskRowType
import com.notesprout.android.data.tasks.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * CRUD + list queries for tasks, and the small amount of behaviour that makes a task a task:
 * resolving one (done / skipped) advances its recurrence series by **materializing the next row**,
 * and un-completing withdraws that row again.
 *
 * Storage is the plaintext-on-device [TaskDao] inside the global index — no encryption gate of its
 * own, exactly like the calendar and events (the index itself is SQLCipher-encrypted at rest).
 */
class TasksRepository(
    private val dao: TaskDao = NotesproutIndex.taskDao(),
) {

    // ── Reads ──────────────────────────────────────────────────────────────────

    /**
     * Open tasks, split into the four display sections relative to [today] and sorted within each.
     * Empty sections are omitted, so the caller can render headers unconditionally.
     *
     * With [gated] left true this is the main list: a future-dated task appears only once its
     * reminder window has opened (see [sectionFor]). Pass false for the **All** view, which drops
     * that filter so nothing can be out of reach.
     */
    suspend fun openSections(
        today: LocalDate,
        gated: Boolean = true,
    ): List<TaskSection> = withContext(Dispatchers.IO) {
        val todayDay = today.toEpochDay()
        val open = dao.openTasks()
        TaskSectionKind.entries.mapNotNull { kind ->
            val rows = open.filter { sectionFor(it, todayDay, gated) == kind }
            if (rows.isEmpty()) null else TaskSection(kind, rows.sortedWith(sectionOrder(kind)))
        }
    }

    /** Completed + skipped tasks grouped by the day they were resolved, most recent day first. */
    suspend fun resolvedGroups(): List<ResolvedGroup> = withContext(Dispatchers.IO) {
        dao.resolvedTasks()
            .groupBy { dayOf(it.resolvedAt ?: it.updatedAt) }
            .entries
            .sortedByDescending { it.key }
            .map { (date, rows) -> ResolvedGroup(date, rows) }
    }

    suspend fun get(id: String): TaskEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    // ── Writes ─────────────────────────────────────────────────────────────────

    /**
     * Insert or update [task], normalizing its series bookkeeping first (see [withSeriesFields]).
     * Callers build the entity; they never have to reason about `seriesId` / `seriesIndex` /
     * `seriesAnchorDay` themselves.
     */
    suspend fun save(task: TaskEntity) = withContext(Dispatchers.IO) {
        val existing = dao.get(task.id)
        dao.upsert(withSeriesFields(task, existing))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    /**
     * Mark [task] done as of [actionDate], generating the next occurrence of its series.
     * Returns the generated successor, or null when the task is one-time or the series ends here.
     */
    suspend fun complete(task: TaskEntity, actionDate: LocalDate): TaskEntity? =
        resolve(task, TaskState.DONE, actionDate)

    /**
     * Mark [task] skipped as of [actionDate]. A skip is **not** a deletion: the row stays as history
     * and advances the series exactly as a completion does, because "I chose not to do this one"
     * is still an answer to the occurrence.
     */
    suspend fun skip(task: TaskEntity, actionDate: LocalDate): TaskEntity? =
        resolve(task, TaskState.SKIPPED, actionDate)

    private suspend fun resolve(
        task: TaskEntity,
        state: TaskState,
        actionDate: LocalDate,
    ): TaskEntity? = withContext(Dispatchers.IO) {
        // Guard against a double tap on an e-ink screen resolving the same row twice and
        // generating two successors.
        if (TaskState.fromName(task.state).isResolved) return@withContext null

        val now = System.currentTimeMillis()
        dao.upsert(task.copy(state = state.name, resolvedAt = now, updatedAt = now))

        val nextDue = TaskRecurrence.nextDue(task, actionDate.toEpochDay())
            ?: return@withContext null
        val successor = task.copy(
            id = UUID.randomUUID().toString(),
            state = TaskState.NOT_DONE.name,
            dueEpochDay = nextDue,
            seriesIndex = (task.seriesIndex ?: 0) + 1,
            resolvedAt = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        dao.upsert(successor)
        successor
    }

    /**
     * Return a resolved [task] to Not done, withdrawing the successor its resolution generated.
     *
     * The successor is hard-deleted rather than tombstoned — it was machine-generated, never user
     * content, and a tombstone would leave an invisible duplicate in the series forever. It is only
     * withdrawn when it is **the direct, untouched next row**; if the user has already acted on it
     * (or on rows beyond it) the series has genuinely moved on and nothing is changed.
     */
    suspend fun reopen(task: TaskEntity): ReopenOutcome = withContext(Dispatchers.IO) {
        val seriesId = task.seriesId
        if (seriesId != null) {
            val myIndex = task.seriesIndex ?: 0
            val maxIndex = dao.maxSeriesIndex(seriesId) ?: myIndex
            if (maxIndex > myIndex + 1) return@withContext ReopenOutcome.SERIES_MOVED_ON
            if (maxIndex == myIndex + 1) {
                val successor = dao.openInSeries(seriesId)
                // Non-null only if that next row is still open; if it was resolved, openInSeries
                // skips it and we must not rewind past the user's own work.
                if (successor == null || successor.seriesIndex != myIndex + 1) {
                    return@withContext ReopenOutcome.SERIES_MOVED_ON
                }
                dao.hardDelete(successor.id)
            }
        }
        val now = System.currentTimeMillis()
        dao.upsert(task.copy(state = TaskState.NOT_DONE.name, resolvedAt = null, updatedAt = now))
        ReopenOutcome.REOPENED
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Fill in the series bookkeeping for a task about to be saved.
     *
     * - One-time task → all three series fields cleared.
     * - New recurring task → fresh `seriesId`, index 0, anchored on its due day.
     * - Existing recurring task → keeps its series, but **moving the due date re-anchors it**, so a
     *   rescheduled "every 3 days" continues from the new date rather than snapping back to the old
     *   phase grid.
     */
    private fun withSeriesFields(task: TaskEntity, existing: TaskEntity?): TaskEntity {
        if (!TaskRecurrence.isRecurring(task)) {
            return task.copy(seriesId = null, seriesIndex = null, seriesAnchorDay = null)
        }
        val due = task.dueEpochDay
        if (existing == null || existing.seriesId == null) {
            return task.copy(
                seriesId = task.seriesId ?: UUID.randomUUID().toString(),
                seriesIndex = task.seriesIndex ?: 0,
                seriesAnchorDay = due,
            )
        }
        val reAnchor = due != null && due != existing.dueEpochDay
        return task.copy(
            seriesId = existing.seriesId,
            seriesIndex = task.seriesIndex ?: existing.seriesIndex ?: 0,
            seriesAnchorDay = if (reAnchor) due else existing.seriesAnchorDay ?: due,
        )
    }

    private fun sectionOrder(kind: TaskSectionKind): Comparator<TaskEntity> =
        if (kind == TaskSectionKind.NO_DATE) {
            compareBy<TaskEntity> { it.createdAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        } else {
            compareBy<TaskEntity> { it.dueEpochDay ?: Long.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        }

    private fun dayOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    companion object {
        /** A blank Not-done task, ready for the editor to fill in. */
        fun blank(): TaskEntity {
            val now = System.currentTimeMillis()
            return TaskEntity(
                id = UUID.randomUUID().toString(),
                type = TaskRowType.TASK_NAME,
                title = "",
                state = TaskState.NOT_DONE.name,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

/**
 * Which section [task] belongs in on the day [todayEpochDay] — or **null when it should not be shown
 * at all yet**.
 *
 * Overdue, today's, and undated tasks are always visible. A **future-dated** task is gated on its
 * look-ahead reminder: it appears in *Upcoming* only from `due − leadDays` onwards, and a task with
 * no reminder therefore never appears there. That mirrors how calendar events behave — an event with
 * no reminder is simply not part of the look-ahead.
 *
 * The consequence is deliberate: a dated task with no reminder is in **no section at all** until its
 * due date arrives, and unlike an event it has no calendar grid to fall back on. The **All** view
 * ([gated] = false) is the escape hatch that keeps such a task reachable — without it a hidden task
 * could not be opened, edited, or deleted either.
 *
 * With [gated] false every open task lands in a section and this never returns null.
 *
 * A top-level function rather than a repository method so the rule can be exercised directly.
 */
fun sectionFor(
    task: TaskEntity,
    todayEpochDay: Long,
    gated: Boolean = true,
): TaskSectionKind? {
    val due = task.dueEpochDay ?: return TaskSectionKind.NO_DATE
    if (due < todayEpochDay) return TaskSectionKind.OVERDUE
    if (due == todayEpochDay) return TaskSectionKind.TODAY
    if (!gated) return TaskSectionKind.UPCOMING
    val lead = TaskReminders.leadDays(task) ?: return null
    return if (due - todayEpochDay <= lead) TaskSectionKind.UPCOMING else null
}

/**
 * The first day [task] will appear in the main (gated) list — its due day minus its reminder lead, or
 * null when it is always visible (undated). Drives the "hidden until …" note in the **All** view, so
 * a task the main list is holding back explains itself rather than looking misfiled.
 */
fun visibleFrom(task: TaskEntity): Long? {
    val due = task.dueEpochDay ?: return null
    return due - (TaskReminders.leadDays(task) ?: 0)
}

/** The four buckets the open-task list is grouped into, in display order. */
enum class TaskSectionKind(val label: String) {
    OVERDUE("Overdue"),
    TODAY("Today"),
    UPCOMING("Upcoming"),
    NO_DATE("No date"),
}

/** One non-empty section of the open-task list. */
data class TaskSection(val kind: TaskSectionKind, val tasks: List<TaskEntity>)

/** One day's worth of resolved tasks in the Done view. */
data class ResolvedGroup(val date: LocalDate, val tasks: List<TaskEntity>)

/** What [TasksRepository.reopen] actually did. */
enum class ReopenOutcome {
    /** The task is open again; any generated successor was withdrawn. */
    REOPENED,

    /** Left untouched — the user has already acted on later occurrences of this series. */
    SERIES_MOVED_ON,
}
