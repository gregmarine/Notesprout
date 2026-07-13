package com.notesprout.android.data.hwr

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrainingPairDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pair: TrainingPairEntity)

    @Query("SELECT * FROM training_pairs WHERE objectId = :objectId LIMIT 1")
    suspend fun getByObjectId(objectId: String): TrainingPairEntity?

    @Query("SELECT * FROM training_pairs WHERE confirmed = 1 ORDER BY createdAt ASC")
    suspend fun confirmedPairs(): List<TrainingPairEntity>

    @Query("SELECT COUNT(*) FROM training_pairs WHERE confirmed = 1")
    suspend fun confirmedCount(): Int

    @Query("SELECT COUNT(*) FROM training_pairs")
    suspend fun count(): Int

    @Query("SELECT label FROM training_pairs WHERE confirmed = 1")
    suspend fun confirmedLabels(): List<String>

    @Query("DELETE FROM training_pairs")
    suspend fun deleteAll()

    /** Keep the newest [cap] rows; drop the oldest overflow (unconfirmed evicted first). */
    @Query(
        """DELETE FROM training_pairs WHERE id IN (
             SELECT id FROM training_pairs ORDER BY confirmed ASC, createdAt ASC
             LIMIT MAX(0, (SELECT COUNT(*) FROM training_pairs) - :cap)
           )"""
    )
    suspend fun pruneToCap(cap: Int)
}
