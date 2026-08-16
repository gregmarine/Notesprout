package com.symmetricalpalmtree.notesprout.data.index

import androidx.room.Database
import androidx.room.RoomDatabase

/** Room database for the global index `notesprout.db`. `user_version` = [VERSION]. Opened only by [PaperIndex]. */
@Database(entities = [ObjectEntity::class], version = IndexDatabase.VERSION, exportSchema = false)
abstract class IndexDatabase : RoomDatabase() {
    abstract fun objectDao(): ObjectDao

    companion object {
        const val VERSION = 1
    }
}
