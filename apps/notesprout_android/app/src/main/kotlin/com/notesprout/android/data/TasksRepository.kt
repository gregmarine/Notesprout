package com.notesprout.android.data

import com.notesprout.android.data.events.EndMode
import com.notesprout.android.data.events.Freq
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.TaskDao
import com.notesprout.android.data.index.TaskEntity
import com.notesprout.android.data.tasks.RoutinePeriod
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

    /**
     * Completed + skipped tasks grouped by the day they were resolved, most recent day first.
     *
     * Windowed to the last [DONE_WINDOW_DAYS] days unless [showAll]. Resolved rows are never pruned —
     * they are a recurring series' history — so the set grows for the life of the library, and the
     * view renders one inflated row each with no recycling. The window keeps the common case cheap;
     * [ResolvedPage.olderCount] tells the caller whether anything is being held back, so the escape
     * hatch only appears when it would do something.
     */
    suspend fun resolvedGroups(
        today: LocalDate,
        showAll: Boolean = false,
    ): ResolvedPage = withContext(Dispatchers.IO) {
        val rows: List<TaskEntity>
        val olderCount: Int
        if (showAll) {
            rows = dao.resolvedTasks()
            olderCount = 0
        } else {
            val since = today.minusDays(DONE_WINDOW_DAYS)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            rows = dao.resolvedTasksSince(since)
            olderCount = dao.countResolvedBefore(since)
        }
        val groups = rows
            .groupBy { dayOf(it.resolvedAt ?: it.updatedAt) }
            .entries
            .sortedByDescending { it.key }
            .map { (date, tasks) -> ResolvedGroup(date, tasks) }
        ResolvedPage(groups, olderCount)
    }

    suspend fun get(id: String): TaskEntity? = withContext(Dispatchers.IO) { dao.get(id) }

    /**
     * A routine's member tasks for the occurrence [routine], grouped and sorted like the main list.
     *
     * Grouping runs **ungated** on purpose: members carry dates but never reminders, so the normal
     * look-ahead rule would hide every future step of the very routine the user just opened.
     */
    suspend fun memberSections(
        routine: TaskEntity,
        today: LocalDate,
    ): List<TaskSection> = withContext(Dispatchers.IO) {
        val todayDay = today.toEpochDay()
        val members = dao.membersOf(routine.id)
        TaskSectionKind.entries.mapNotNull { kind ->
            val rows = members.filter { sectionFor(it, todayDay, gated = false) == kind }
            if (rows.isEmpty()) null else TaskSection(kind, rows.sortedWith(sectionOrder(kind)))
        }
    }

    suspend fun members(routineId: String): List<TaskEntity> =
        withContext(Dispatchers.IO) { dao.membersOf(routineId) }

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
        // A routine is never reopened: once its last step is answered it completes, rolls forward,
        // and the occurrence is history. Its members are locked with it — un-checking one would
        // leave a live step inside a finished occurrence that had already spawned its successor.
        if (task.type == TaskRowType.ROUTINE.name) return@withContext ReopenOutcome.LOCKED
        task.parentId?.let { routineId ->
            val routine = dao.get(routineId)
            if (routine != null && TaskState.fromName(routine.state).isResolved) {
                return@withContext ReopenOutcome.LOCKED
            }
            // A member of a still-open routine: a plain flip. Members never carry a series, so
            // there is no successor to withdraw.
            val ts = System.currentTimeMillis()
            dao.upsert(task.copy(state = TaskState.NOT_DONE.name, resolvedAt = null, updatedAt = ts))
            return@withContext ReopenOutcome.REOPENED
        }

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

    // ── Routines ───────────────────────────────────────────────────────────────

    /**
     * A new routine, live for the period containing [today]. Routines always recur, so the series is
     * created here rather than inferred later, and the due date is **derived** from the frequency —
     * a weekly routine is due its Saturday, a monthly one its last day (see [RoutinePeriod]).
     */
    suspend fun createRoutine(
        title: String,
        freq: Freq,
        today: LocalDate,
    ): TaskEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val due = RoutinePeriod.dueFor(freq, today.toEpochDay())
        val routine = TaskEntity(
            id = UUID.randomUUID().toString(),
            type = TaskRowType.ROUTINE.name,
            title = title,
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
        dao.upsert(routine)
        routine
    }

    /** Rename a routine. Frequency is fixed at creation, so only the title can change. */
    suspend fun renameRoutine(routine: TaskEntity, title: String) = withContext(Dispatchers.IO) {
        dao.upsert(routine.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    /** Delete a routine and everything in it — an orphaned member is reachable from nowhere. */
    suspend fun deleteRoutine(routineId: String) = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        dao.softDeleteMembers(routineId, ts)
        dao.softDelete(routineId, ts)
    }

    /**
     * Insert or update a member of [routine]. The date is clamped into the routine's period — a step
     * outside it would roll forward to a nonsense position, and there is no reading of "next Tuesday"
     * that belongs to this week's routine.
     */
    suspend fun saveMember(routine: TaskEntity, member: TaskEntity) = withContext(Dispatchers.IO) {
        val existing = dao.get(member.id)
        val order = existing?.sortOrder ?: ((dao.maxMemberOrder(routine.id) ?: -1) + 1)
        dao.upsert(
            member.copy(
                type = TaskRowType.TASK_NAME,
                parentId = routine.id,
                dueEpochDay = clampToPeriod(routine, member.dueEpochDay),
                sortOrder = order,
                // A member never recurs and never reminds: the routine's series drives repetition,
                // and reminders gate main-list visibility, which a member does not have.
                seriesId = null, seriesIndex = null, seriesAnchorDay = null,
                recurFreq = null, recurInterval = null, recurWeekdays = null,
                recurMonthlyMode = null, recurEndMode = null,
                recurEndEpochDay = null, recurEndCount = null,
                remindAmount = null, remindUnit = null,
            ),
        )
    }

    /**
     * Resolve a member (done or skipped) and, if that was the last open step, complete the routine
     * and roll it forward. Returns what happened so the caller can say so.
     */
    suspend fun resolveMember(
        member: TaskEntity,
        state: TaskState,
        actionDate: LocalDate,
    ): MemberOutcome = withContext(Dispatchers.IO) {
        if (TaskState.fromName(member.state).isResolved) return@withContext MemberOutcome(false, null)
        val now = System.currentTimeMillis()
        dao.upsert(member.copy(state = state.name, resolvedAt = now, updatedAt = now))

        val routineId = member.parentId ?: return@withContext MemberOutcome(false, null)
        val routine = dao.get(routineId) ?: return@withContext MemberOutcome(false, null)
        if (TaskState.fromName(routine.state).isResolved) return@withContext MemberOutcome(false, null)
        // An empty routine has no open members either — completing on that basis would finish a
        // routine the moment it was created, before it ever had a step in it.
        if (dao.countMembers(routineId) == 0) return@withContext MemberOutcome(false, null)
        if (dao.countOpenMembers(routineId) > 0) return@withContext MemberOutcome(false, null)

        dao.upsert(routine.copy(state = TaskState.DONE.name, resolvedAt = now, updatedAt = now))
        val next = rollForward(routine, actionDate.toEpochDay(), now)
        MemberOutcome(routineCompleted = true, nextRoutineDue = next?.dueEpochDay)
    }

    /**
     * Skip a whole routine: every still-open step becomes `SKIPPED`, the routine itself becomes
     * `SKIPPED`, and the next occurrence is generated. Unlike automatic completion this works on an
     * empty routine — the user asked for it explicitly, rather than it happening by omission.
     */
    suspend fun skipRoutine(
        routine: TaskEntity,
        actionDate: LocalDate,
    ): TaskEntity? = withContext(Dispatchers.IO) {
        if (TaskState.fromName(routine.state).isResolved) return@withContext null
        val now = System.currentTimeMillis()
        for (m in dao.membersOf(routine.id)) {
            if (!TaskState.fromName(m.state).isResolved) {
                dao.upsert(m.copy(state = TaskState.SKIPPED.name, resolvedAt = now, updatedAt = now))
            }
        }
        dao.upsert(routine.copy(state = TaskState.SKIPPED.name, resolvedAt = now, updatedAt = now))
        rollForward(routine, actionDate.toEpochDay(), now)
    }

    /**
     * Generate the occurrence after [routine], carrying its steps forward.
     *
     * The new period is the first one ending strictly after `max(due, actionDay)` — the same lateness
     * rule tasks use, so finishing a weekly routine three days late lands on the following Saturday
     * rather than one already past. Each member keeps its **position within the period**, so
     * "Monday: bins" stays Monday, clamped so a step on the 31st survives February.
     */
    private suspend fun rollForward(
        routine: TaskEntity,
        actionDay: Long,
        now: Long,
    ): TaskEntity? {
        val freq = TaskRecurrence.freqOf(routine.recurFreq) ?: return null
        val oldDue = routine.dueEpochDay ?: return null
        val oldStart = RoutinePeriod.startFor(freq, oldDue)
        val newDue = RoutinePeriod.nextDueAfter(freq, maxOf(oldDue, actionDay))
        val newStart = RoutinePeriod.startFor(freq, newDue)

        val next = routine.copy(
            id = UUID.randomUUID().toString(),
            state = TaskState.NOT_DONE.name,
            dueEpochDay = newDue,
            seriesIndex = (routine.seriesIndex ?: 0) + 1,
            resolvedAt = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        dao.upsert(next)

        // Copied from the occurrence just finished, so editing this week's steps carries forward.
        for (m in dao.membersOf(routine.id)) {
            val offset = m.dueEpochDay?.let { RoutinePeriod.offsetOf(oldStart, oldDue, it) } ?: 0
            dao.upsert(
                m.copy(
                    id = UUID.randomUUID().toString(),
                    parentId = next.id,
                    state = TaskState.NOT_DONE.name,
                    dueEpochDay = RoutinePeriod.applyOffset(newStart, newDue, offset),
                    resolvedAt = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }
        return next
    }

    /** Pull [day] inside [routine]'s period; null stays null (an undated step is allowed). */
    private fun clampToPeriod(routine: TaskEntity, day: Long?): Long? {
        val due = routine.dueEpochDay ?: return day
        val freq = TaskRecurrence.freqOf(routine.recurFreq) ?: return day
        return day?.coerceIn(RoutinePeriod.startFor(freq, due), due)
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
        /**
         * How far back the Done view reaches before asking. Long enough that "what did I get through
         * lately" is always answered without a tap, short enough that the row count stays trivial
         * even with several daily recurring tasks.
         */
        const val DONE_WINDOW_DAYS = 30L

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
    if (task.type == TaskRowType.ROUTINE.name) return routineSection(task, todayEpochDay)
    val due = task.dueEpochDay ?: return TaskSectionKind.NO_DATE
    if (due < todayEpochDay) return TaskSectionKind.OVERDUE
    if (due == todayEpochDay) return TaskSectionKind.TODAY
    if (!gated) return TaskSectionKind.UPCOMING
    val lead = TaskReminders.leadDays(task) ?: return null
    return if (due - todayEpochDay <= lead) TaskSectionKind.UPCOMING else null
}

/**
 * Where a routine sits. A routine is **live for its whole period**, not gated on a reminder — it is
 * a body of work you chip away at, not a single dated item, so hiding it until its deadline would
 * make a yearly routine invisible for 364 days.
 *
 * | Routine | Section |
 * |---|---|
 * | Period not started yet | **Upcoming** |
 * | Period live | **Today** |
 * | Past its due date | **Overdue** |
 *
 * The first row is not hypothetical: finishing a weekly routine on Monday generates its successor
 * immediately, and that successor's week has not begun. It is real work, but not *yet* — so it waits
 * in Upcoming rather than claiming a place in today's list.
 *
 * A routine always lands somewhere; it can never be filtered out of sight the way a task can.
 */
private fun routineSection(routine: TaskEntity, todayEpochDay: Long): TaskSectionKind {
    val due = routine.dueEpochDay ?: return TaskSectionKind.TODAY
    if (due < todayEpochDay) return TaskSectionKind.OVERDUE
    val freq = TaskRecurrence.freqOf(routine.recurFreq)
    val start = if (freq != null) RoutinePeriod.startFor(freq, due) else due
    return if (todayEpochDay >= start) TaskSectionKind.TODAY else TaskSectionKind.UPCOMING
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

/**
 * A page of the Done view: the [groups] to render, and how many resolved tasks fall before the
 * window ([olderCount], 0 when nothing is held back or everything is already shown).
 */
data class ResolvedPage(val groups: List<ResolvedGroup>, val olderCount: Int)

/** What [TasksRepository.reopen] actually did. */
enum class ReopenOutcome {
    /** The task is open again; any generated successor was withdrawn. */
    REOPENED,

    /** Left untouched — the user has already acted on later occurrences of this series. */
    SERIES_MOVED_ON,

    /** Left untouched — a finished routine, or a step inside one, is immutable. */
    LOCKED,
}

/**
 * What resolving a routine member did. [routineCompleted] is true when that step was the last one
 * open, which completes the routine and rolls it forward to [nextRoutineDue].
 */
data class MemberOutcome(val routineCompleted: Boolean, val nextRoutineDue: Long?)
