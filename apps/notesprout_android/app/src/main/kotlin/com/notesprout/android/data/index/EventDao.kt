package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** CRUD + day-scoped queries for the `events` table. */
@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: EventEntity)

    @Query("SELECT * FROM events WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): EventEntity?

    /**
     * Non-recurring events whose inclusive [startEpochDay, endEpochDay] span covers [day].
     * Recurring events are fetched separately ([recurring]) and expanded in Kotlin.
     */
    @Query(
        "SELECT * FROM events WHERE deletedAt IS NULL AND recurring = 0 " +
            "AND startEpochDay <= :day AND endEpochDay >= :day"
    )
    suspend fun nonRecurringOnDay(day: Long): List<EventEntity>

    /** Non-recurring events whose span overlaps the inclusive day range `[rangeStart, rangeEnd]`. */
    @Query(
        "SELECT * FROM events WHERE deletedAt IS NULL AND recurring = 0 " +
            "AND startEpochDay <= :rangeEnd AND endEpochDay >= :rangeStart"
    )
    suspend fun nonRecurringInRange(rangeStart: Long, rangeEnd: Long): List<EventEntity>

    /** All live recurring events (the expansion engine decides which cover a given day). */
    @Query("SELECT * FROM events WHERE deletedAt IS NULL AND recurring = 1")
    suspend fun allRecurring(): List<EventEntity>

    @Query("UPDATE events SET deletedAt = :ts, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)
}
