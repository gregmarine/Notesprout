package com.notesprout.android.data.hwr

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Projection row for [TrainingPairDao.confirmedCorrections]. */
data class CorrectionRow(val originalText: String, val label: String)

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

    /**
     * (originalText, label) of confirmed corrections — a projection, deliberately NOT
     * [confirmedPairs], so rebuilding correction memory never loads every row's strokesJson.
     */
    @Query(
        """SELECT originalText AS originalText, label AS label FROM training_pairs
           WHERE confirmed = 1 AND originalText IS NOT NULL
             AND originalText <> '' AND originalText <> label"""
    )
    suspend fun confirmedCorrections(): List<CorrectionRow>

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
