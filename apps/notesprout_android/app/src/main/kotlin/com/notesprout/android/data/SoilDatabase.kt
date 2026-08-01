package com.notesprout.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

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
@Database(entities = [NotebookObject::class], version = 5, exportSchema = false)
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
         * Data-model-optimization Phase 1: widen the `notebook` table with the columnar payload
         * columns + binary `blob` (see [SoilSchema]). Additive only — every column is nullable and no
         * existing data is rewritten, so this is a fast, safe upgrade. Rows keep their legacy `data`
         * JSON; content is converted to the columnar form lazily (on open/save, via NotebookCompactor).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for ((name, sqlType) in SoilSchema.ADDED_COLUMNS_V4) {
                    db.execSQL("""ALTER TABLE notebook ADD COLUMN "$name" $sqlType""")
                }
            }
        }

        /**
         * Adds `srcUpdatedAt` — the page-state watermark a `document` row was drafted from (see
         * [SoilSchema.ADDED_COLUMNS_V5]). Additive and nullable, like the v4 widening: no existing
         * row is rewritten.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for ((name, sqlType) in SoilSchema.ADDED_COLUMNS_V5) {
                    db.execSQL("""ALTER TABLE notebook ADD COLUMN "$name" $sqlType""")
                }
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // Default (plaintext) open path: never let a corrupt/mis-read file be deleted. An
                // encrypted .soil opened without a key looks corrupt here; the default handler would
                // wipe it. Keyed callers override this with the SQLCipher factory (also wrapped).
                .openHelperFactory(NonDestructiveOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))

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
                // Wait for a busy write lock instead of failing instantly.
                //
                // A `.soil` can legitimately have TWO connections open at once: the sticky-note
                // editor opens its own for its debounced real-time persist while the notebook host's
                // is still live. WAL allows one writer at a time, so their transactions collide —
                // and with no busy handler SQLite raises SQLITE_BUSY the moment it cannot take the
                // lock, which surfaced as `SQLiteDatabaseLockedException: database is locked (code 5)`
                // killing the app mid-writing. These transactions are milliseconds long, so waiting
                // resolves the contention outright; five seconds is far longer than any of them and
                // still short enough that a genuine deadlock surfaces rather than hanging.
                //
                // This is the containment, not the cure — the editor should not be opening a second
                // connection at all (see BACKLOG.md). Writes run off the main thread, so the wait
                // cannot ANR.
                db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
            }
        }

        // Soft-delete compaction runs automatically at seal time (NotebookActivity.sealNotebook):
        // rows soft-deleted before the current session are hard-deleted before incremental_vacuum,
        // so SQLite can actually reclaim those pages. Snapshots are kept indefinitely by design
        // (fast page-load on e-ink outweighs file-size cost).
    }
}
