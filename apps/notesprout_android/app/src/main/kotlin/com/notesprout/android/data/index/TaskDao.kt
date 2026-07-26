package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * CRUD + list queries for the `tasks` table.
 *
 * **Every query filters `type = 'TASK'`.** Routine rows will share this table (a routine's member
 * tasks carry `parentId = <routine id>`), and without the filter they would surface as ordinary
 * list items the moment that feature lands.
 */
@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): TaskEntity?

    /** Every open task. Undated rows sort last; the caller does the section grouping. */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' AND state = 'NOT_DONE' " +
            "ORDER BY dueEpochDay IS NULL, dueEpochDay ASC, createdAt ASC"
    )
    suspend fun openTasks(): List<TaskEntity>

    /**
     * Completed + skipped tasks, most recently resolved first — **every one ever**. Only the
     * explicit "show earlier" path uses this; the Done view's default is [resolvedTasksSince].
     */
    @Query(
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' AND state <> 'NOT_DONE' " +
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
        "SELECT * FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' AND state <> 'NOT_DONE' " +
            "AND COALESCE(resolvedAt, updatedAt) >= :sinceMillis " +
            "ORDER BY resolvedAt DESC, updatedAt DESC"
    )
    suspend fun resolvedTasksSince(sinceMillis: Long): List<TaskEntity>

    /** How many resolved tasks fall before [sinceMillis] — drives the "show earlier" affordance. */
    @Query(
        "SELECT COUNT(*) FROM tasks WHERE deletedAt IS NULL AND type = 'TASK' " +
            "AND state <> 'NOT_DONE' AND COALESCE(resolvedAt, updatedAt) < :sinceMillis"
    )
    suspend fun countResolvedBefore(sinceMillis: Long): Int

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
