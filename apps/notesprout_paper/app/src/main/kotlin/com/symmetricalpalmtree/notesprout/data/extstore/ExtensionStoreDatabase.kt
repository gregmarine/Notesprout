package com.symmetricalpalmtree.notesprout.data.extstore

import androidx.room.Database
import androidx.room.RoomDatabase

/** Room database for one extension's store (`Garden/<pkg>.db`). Opened only by [ExtensionStores]. */
@Database(entities = [KvEntity::class], version = ExtensionStoreDatabase.VERSION, exportSchema = false)
abstract class ExtensionStoreDatabase : RoomDatabase() {
    abstract fun kv(): KvDao

    companion object {
        const val VERSION = 1
    }
}
