package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * CRUD + list queries for the `tasks` table, which holds three kinds of row:
 *
 * | Row | Shape |
 * |---|---|
 * | **Standalone task** | `type = 'TASK'`, `parentId IS NULL` |
 * | **Routine** | `type = 'ROUTINE'` |
 * | **Routine member** | `type = 'TASK'`, `parentId = <routine id>` |
 *
 * The **main-list queries return the first two and never the third** — a routine's members belong to
 * that routine's own screen, and surfacing them alongside standalone tasks is exactly the mixing the
 * two-screen split exists to prevent. `MAIN_LIST` below is that predicate, written once so no query
 * can quietly forget half of it.
 *
 * Series queries stay `type = 'TASK'`: they serve task un-complete, and a routine is never reopened.
 */
@Dao
interface TaskDao {

    companion object {
        /** Standalone tasks and routines — everything the main list shows, and nothing it doesn't. */
        const val MAIN_LIST = "((type = 'TASK' AND parentId IS NULL) OR type = 'ROUTINE')"
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): TaskEntity?

    /** Every open main-list row. Undated rows sort last; the caller does the section grouping. */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND " + MAIN_LIST + " AND state = 'NOT_DONE' " +
            "ORDER BY dueEpochDay IS NULL, dueEpochDay ASC, createdAt ASC"
    )
    suspend fun openTasks(): List<TaskEntity>

    /**
     * Completed + skipped tasks, most recently resolved first — **every one ever**. Only the
     * explicit "show earlier" path uses this; the Done view's default is [resolvedTasksSince].
     */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND " + MAIN_LIST + " AND state <> 'NOT_DONE' " +
            "ORDER BY resolvedAt DESC, updatedAt DESC"
    )
    suspend fun resolvedTasks(): List<TaskEntity>

    /**
     * Resolved tasks from [sinceMillis] onwards — the Done view's default window.
     *
     * Resolved rows accumulate for the life of the library and recurring tasks make that fast: a
     * daily chore alone leaves 365 rows a year behind it. Unwindowed, the view eventually inflates
     * thousands of views on the main thread.
     *
     * `COALESCE` because rows resolved before `resolvedAt` existed carry only an `updatedAt`; the
     * grouping in `TasksRepository` falls back the same way, so the two agree on which day a row
     * belongs to.
     */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND " + MAIN_LIST + " AND state <> 'NOT_DONE' " +
            "AND COALESCE(resolvedAt, updatedAt) >= :sinceMillis " +
            "ORDER BY resolvedAt DESC, updatedAt DESC"
    )
    suspend fun resolvedTasksSince(sinceMillis: Long): List<TaskEntity>

    /** How many resolved main-list rows fall before [sinceMillis] — drives "show earlier". */
    @Query(
        "SELECT COUNT(*) FROM tasks WHERE deletedAt IS NULL AND " + MAIN_LIST +
            " AND state <> 'NOT_DONE' AND COALESCE(resolvedAt, updatedAt) < :sinceMillis"
    )
    suspend fun countResolvedBefore(sinceMillis: Long): Int

    // ── Routine members ────────────────────────────────────────────────────────

    /** Every live member of [routineId], in step order. Undated members sort last. */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' AND parentId = :routineId " +
            "ORDER BY dueEpochDay IS NULL, dueEpochDay ASC, \"order\" ASC, createdAt ASC"
    )
    suspend fun membersOf(routineId: String): List<TaskEntity>

    /**
     * How many members of [routineId] are still open. Zero is what completes a routine — but only
     * together with [countMembers], since a routine with no members at all has zero open ones too and
     * must not complete on the strength of being empty.
     */
    @Query(
        "SELECT COUNT(*) FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' " +
            "AND parentId = :routineId AND state = 'NOT_DONE'"
    )
    suspend fun countOpenMembers(routineId: String): Int

    /** How many live members [routineId] has at all, resolved or not. */
    @Query(
        "SELECT COUNT(*) FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' " +
            "AND parentId = :routineId"
    )
    suspend fun countMembers(routineId: String): Int

    /** The highest step order in [routineId], so a new member appends rather than collides. */
    @Query(
        "SELECT MAX(\"order\") FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' " +
            "AND parentId = :routineId"
    )
    suspend fun maxMemberOrder(routineId: String): Int?

    /**
     * Step counts for **every** routine at once, so the main list can show "2 of 5 done" without a
     * pair of queries per row.
     */
    @Query(
        "SELECT parentId, COUNT(*) AS total, " +
            "SUM(CASE WHEN state = 'NOT_DONE' THEN 0 ELSE 1 END) AS done " +
            "FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' AND parentId IS NOT NULL " +
            "GROUP BY parentId"
    )
    suspend fun routineProgress(): List<RoutineProgressRow>

    /** Soft-delete every member of [routineId] — used when the routine itself is deleted. */
    @Query(
        "UPDATE tasks SET deletedAt = :ts, updatedAt = :ts " +
            "WHERE parentId = :routineId AND deletedAt IS NULL"
    )
    suspend fun softDeleteMembers(routineId: String, ts: Long)

    /**
     * The still-open row of a series — the successor a completion generated. At most one exists at a
     * time, which is what makes "un-complete" able to withdraw it cleanly.
     */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' " +
            "AND seriesId = :seriesId AND state = 'NOT_DONE' ORDER BY seriesIndex ASC LIMIT 1"
    )
    suspend fun openInSeries(seriesId: String): TaskEntity?

    /**
     * The highest [TaskEntity.seriesIndex] any live row of the series holds. Lets "un-complete"
     * tell "my successor is still sitting there untouched" from "the series has already moved past
     * me", including the case where the successor was itself resolved and then the series ended.
     */
    @Query(
        "SELECT MAX(seriesIndex) FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' " +
            "AND seriesId = :seriesId"
    )
    suspend fun maxSeriesIndex(seriesId: String): Int?

    @Query("UPDATE tasks SET deletedAt = :ts, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    /**
     * Physically remove a row. Used only to withdraw a generated successor when its parent is
     * un-completed — that row was never user content, so tombstoning it would leave an invisible
     * duplicate in the series forever.
     */
    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun hardDelete(id: String)
}

/** One routine's step counts, as returned by [TaskDao.routineProgress]. */
data class RoutineProgressRow(
    val parentId: String,
    val total: Int,
    val done: Int,
)
