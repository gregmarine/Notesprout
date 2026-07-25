package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** CRUD + day-scoped queries for the `notebook_activity` table. */
@Dao
interface NotebookActivityDao {

    @Insert
    suspend fun insert(row: NotebookActivityEntity)

    /**
     * All rows of [activityType] whose [NotebookActivityEntity.timestamp] falls in
     * `[startMillis, endMillis)`, newest-first. Callers group by notebook + dedup.
     */
    @Query(
        "SELECT * FROM notebook_activity " +
            "WHERE activityType = :activityType AND timestamp >= :startMillis AND timestamp < :endMillis " +
            "ORDER BY timestamp DESC"
    )
    suspend fun inRange(activityType: String, startMillis: Long, endMillis: Long): List<NotebookActivityEntity>

    /** Distinct millis timestamps for [activityType] — used to compute which years have data. */
    @Query("SELECT DISTINCT timestamp FROM notebook_activity WHERE activityType = :activityType")
    suspend fun allTimestamps(activityType: String): List<Long>
}
