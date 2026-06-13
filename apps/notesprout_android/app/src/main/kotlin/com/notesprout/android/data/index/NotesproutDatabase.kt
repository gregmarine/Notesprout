package com.notesprout.android.data.index

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ObjectEntity::class], version = 1, exportSchema = false)
abstract class NotesproutDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao

    companion object {
        fun openCallback(): Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.query("PRAGMA journal_mode = WAL").use { it.moveToFirst() }
                db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
                val autoVacuumMode = db.query("PRAGMA auto_vacuum").use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                if (autoVacuumMode != 2) {
                    db.query("PRAGMA auto_vacuum = INCREMENTAL").use { it.moveToFirst() }
                    db.execSQL("VACUUM")
                }
            }
        }
    }
}
