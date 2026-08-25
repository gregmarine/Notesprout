package com.symmetricalpalmtree.notesproutsn.data.extstore

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for one extension's store (`Garden/<pkg>.db`, arc 11 / J2). Opened only by
 * [ExtensionStores]. Its own file and its own schema: nothing here touches the global index or any
 * `.soil`, so neither one's version moves when this one does.
 */
@Database(entities = [KvEntity::class], version = ExtensionStoreDatabase.VERSION, exportSchema = false)
abstract class ExtensionStoreDatabase : RoomDatabase() {
    abstract fun kv(): KvDao

    companion object {
        const val VERSION = 1
    }
}
