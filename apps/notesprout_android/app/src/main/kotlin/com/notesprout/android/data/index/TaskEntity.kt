package com.notesprout.android.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One **task** — an item on the user's "things I need to do" list.
 *
 * Lives in the global index (`notesprout.db`, encrypted at rest), never in a `.soil` file. Unlike
 * [EventEntity] this row is **fully columnar**: there is no `data` payload and nothing here is ever
 * serialized to JSON. That is affordable because a task series materializes its occurrences as real
 * rows (see below), so the one genuinely set-shaped field — the weekly weekday set — collapses to an
 * integer bitmask and no exception list is needed.
 *
 * ### Series model — materialized, not expanded
 *
 * An event stores one anchor row and expands occurrences in memory. A task series instead keeps
 * **exactly one open row at a time**: resolving the open row (DONE / SKIPPED) inserts its successor
 * with the same [seriesId] / [seriesAnchorDay] / rule and `seriesIndex + 1`. Resolved rows stay as
 * the series' history. So there is no in-memory expansion, no exception dates, and no
 * occurrence-scoped edit prompts.
 *
 * ### Routines (deferred)
 *
 * [type] and [parentId] are the reservation for a future **routine** — a named set of tasks whose
 * members carry `parentId = <routine id>`. In this version every row is written with
 * `type = TASK` and `parentId = null`, and **every query filters `type = 'TASK'`** so routine rows
 * can never leak into the task list once they exist.
 *
 * Dates are local `epochDay` (device-default zone); [dueEpochDay] is null for an undated task.
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["state", "dueEpochDay"]),
        Index(value = ["seriesId"]),
        Index(value = ["parentId"]),
        Index(value = ["deletedAt"]),
    ],
)
data class TaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Owning routine's id, or null for a standalone task. Always null in this version. */
    @ColumnInfo(name = "parentId")
    val parentId: String? = null,

    /** `TASK` | `ROUTINE` — see [com.notesprout.android.data.tasks.TaskRowType]. */
    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** `NOT_DONE` | `DONE` | `SKIPPED` — see [com.notesprout.android.data.tasks.TaskState]. */
    @ColumnInfo(name = "state")
    val state: String,

    /** Local epoch-day the task is due, or null when the task carries no date. */
    @ColumnInfo(name = "dueEpochDay")
    val dueEpochDay: Long? = null,

    /** Reserved: step order within a routine. Unused (always 0) in this version. */
    @ColumnInfo(name = "order", defaultValue = "0")
    val sortOrder: Int = 0,

    // ── Recurrence series ──────────────────────────────────────────────────────

    /** Shared by every row generated from one recurrence rule; null for a one-time task. */
    @ColumnInfo(name = "seriesId")
    val seriesId: String? = null,

    /** 0-based position in the series. Drives the COUNT end mode. */
    @ColumnInfo(name = "seriesIndex")
    val seriesIndex: Int? = null,

    /**
     * The series' **original** first due day. Defines the recurrence phase grid, so a task completed
     * late still lands on the series' own rhythm rather than re-phasing to the completion date.
     */
    @ColumnInfo(name = "seriesAnchorDay")
    val seriesAnchorDay: Long? = null,

    // ── Recurrence rule (null [recurFreq] = one-time) ──────────────────────────

    /** `DAILY` | `WEEKLY` | `MONTHLY` | `YEARLY`, matching [com.notesprout.android.data.events.Freq]. */
    @ColumnInfo(name = "recurFreq")
    val recurFreq: String? = null,

    /** "every N" units; bi-weekly is WEEKLY with interval 2. */
    @ColumnInfo(name = "recurInterval")
    val recurInterval: Int? = null,

    /**
     * WEEKLY only: ISO weekday set as a bitmask — Mon = bit 0 … Sun = bit 6. 0 / null means "the
     * anchor's own weekday". Pack and unpack via
     * [com.notesprout.android.data.tasks.TaskWeekdays].
     */
    @ColumnInfo(name = "recurWeekdays")
    val recurWeekdays: Int? = null,

    /** MONTHLY only: `DAY_OF_MONTH` | `ORDINAL_WEEKDAY`. */
    @ColumnInfo(name = "recurMonthlyMode")
    val recurMonthlyMode: String? = null,

    /** `NEVER` | `UNTIL` | `COUNT`. */
    @ColumnInfo(name = "recurEndMode")
    val recurEndMode: String? = null,

    /** UNTIL: the last day an occurrence may fall on. */
    @ColumnInfo(name = "recurEndEpochDay")
    val recurEndEpochDay: Long? = null,

    /** COUNT: total number of occurrences the series ever produces. */
    @ColumnInfo(name = "recurEndCount")
    val recurEndCount: Int? = null,

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Wall-clock ms the row went DONE or SKIPPED; null while NOT_DONE. */
    @ColumnInfo(name = "resolvedAt")
    val resolvedAt: Long? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null,
)
