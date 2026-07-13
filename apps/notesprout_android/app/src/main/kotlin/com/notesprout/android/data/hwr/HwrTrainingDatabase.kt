package com.notesprout.android.data.hwr

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Plain (unencrypted) Room DB of handwriting training pairs at `filesDir/hwr/training.db`.
 * Deliberately NOT the global index and NOT inside any `.soil` — pairs cross notebooks and
 * must never enter `notesprout.db` (search-leak invariant). Capture is gated so no content
 * from encrypted notebooks lands here — see [TrainingPairEntity] and
 * [com.notesprout.android.recognition.personal.TrainingPairRepository].
 */
@Database(entities = [TrainingPairEntity::class], version = 1, exportSchema = false)
abstract class HwrTrainingDatabase : RoomDatabase() {

    abstract fun trainingPairDao(): TrainingPairDao

    companion object {
        @Volatile
        private var instance: HwrTrainingDatabase? = null

        fun dao(context: Context): TrainingPairDao {
            val existing = instance
            if (existing != null) return existing.trainingPairDao()
            synchronized(this) {
                val again = instance
                if (again != null) return again.trainingPairDao()
                val dbFile = File(context.applicationContext.filesDir, "hwr/training.db")
                dbFile.parentFile?.mkdirs()
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    HwrTrainingDatabase::class.java,
                    dbFile.absolutePath,
                ).build()
                instance = db
                return db.trainingPairDao()
            }
        }
    }
}
