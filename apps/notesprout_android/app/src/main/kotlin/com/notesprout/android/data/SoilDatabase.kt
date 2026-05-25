package com.notesprout.android.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for a single `.soil` notebook file.
 *
 * **One instance per open notebook.** DrawingActivity owns the lifecycle:
 * it opens this database when the notebook is entered and calls [close] when
 * the user leaves. Do NOT use a singleton pattern here.
 *
 * Open via:
 * ```kotlin
 * Room.databaseBuilder(context, SoilDatabase::class.java, absoluteFilePath)
 *     .addCallback(SoilDatabase.openCallback())
 *     .allowMainThreadQueries()   // temporary — step 5 moves queries off main thread
 *     .build()
 * ```
 */
@Database(entities = [NotebookObject::class], version = 1, exportSchema = false)
abstract class SoilDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao

    companion object {
        /**
         * Room callback that (re-)applies connection-level PRAGMAs every time the
         * database is opened. These settings are NOT stored in the database file —
         * they must be re-applied on each new connection.
         *
         * All PRAGMA queries use the rawQuery + moveToFirst() pattern required by
         * Android's SQLite layer (see CLAUDE.md pruning notes).
         */
        fun openCallback(): Callback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Re-apply wal_autocheckpoint on every open — it is connection-level only
                // and is not persisted in the database file.
                db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
            }
        }

        // TODO(compaction): Future cleanup pass — when the user triggers "Compact Notebook",
        // this is where we will: (1) hard-delete all soft-deleted objects, (2) strip stale
        // snapshots from non-current pages or rebuild them fresh, (3) run PRAGMA incremental_vacuum
        // + PRAGMA wal_checkpoint(TRUNCATE) to reclaim disk space. Do not implement now.
    }
}
