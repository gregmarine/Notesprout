package com.notesprout.android.data.index

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ObjectEntity::class, ScratchpadEntity::class], version = 2, exportSchema = false)
abstract class NotesproutDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao
    abstract fun scratchpadDao(): ScratchpadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scratchpad (
                        id          TEXT    NOT NULL PRIMARY KEY,
                        parentId    TEXT    NOT NULL,
                        boundingBox TEXT    NOT NULL,
                        "order"     INTEGER NOT NULL DEFAULT 0,
                        createdAt   INTEGER NOT NULL,
                        updatedAt   INTEGER NOT NULL,
                        deletedAt   INTEGER,
                        type        TEXT    NOT NULL,
                        data        TEXT    NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS idx_scratchpad_parent_order
                        ON scratchpad(parentId, "order", deletedAt)
                    """.trimIndent()
                )
            }
        }

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
