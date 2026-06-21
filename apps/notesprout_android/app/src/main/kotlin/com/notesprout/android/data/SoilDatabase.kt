package com.notesprout.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for a single `.soil` notebook file.
 *
 * **One instance per open notebook.** NotebookActivity owns the lifecycle:
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
@Database(entities = [NotebookObject::class], version = 3, exportSchema = false)
abstract class SoilDatabase : RoomDatabase() {

    abstract fun notebookDao(): NotebookDao

    companion object {
        /**
         * Adds the `undo_redo_state` single-row meta table used to persist undo/redo
         * history inside encrypted notebooks (P2.S3). Encrypted at rest for free via
         * SQLCipher; plaintext notebooks never write to this table.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS undo_redo_state " +
                    "(id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notebook_meta " +
                    "(id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)"
                )
            }
        }

        /**
         * Single factory that wires the open callback and full migration set onto every
         * SoilDatabase builder. Callers add `.openHelperFactory(SoilCrypto.roomFactory(key))`
         * on top where encryption is needed.
         */
        fun builder(context: Context, absolutePath: String): RoomDatabase.Builder<SoilDatabase> =
            Room.databaseBuilder(context.applicationContext, SoilDatabase::class.java, absolutePath)
                .addCallback(openCallback())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)

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

        // Soft-delete compaction runs automatically at seal time (NotebookActivity.sealNotebook):
        // rows soft-deleted before the current session are hard-deleted before incremental_vacuum,
        // so SQLite can actually reclaim those pages. Snapshots are kept indefinitely by design
        // (fast page-load on e-ink outweighs file-size cost).
    }
}
