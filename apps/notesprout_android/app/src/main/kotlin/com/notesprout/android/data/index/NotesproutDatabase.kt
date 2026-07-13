package com.notesprout.android.data.index

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notesprout.android.data.SoilSchema

@Database(
    entities = [
        ObjectEntity::class,
        ScratchpadEntity::class,
        CalendarEntity::class,
        NotebookActivityEntity::class,
        EventEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class NotesproutDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao
    abstract fun scratchpadDao(): ScratchpadDao
    abstract fun calendarDao(): CalendarDao
    abstract fun notebookActivityDao(): NotebookActivityDao
    abstract fun eventDao(): EventDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS calendar (
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
                    CREATE INDEX IF NOT EXISTS idx_calendar_parent_order
                        ON calendar(parentId, "order", deletedAt)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notebook_activity (
                        id           TEXT    NOT NULL PRIMARY KEY,
                        notebookId   TEXT    NOT NULL,
                        activityType TEXT    NOT NULL,
                        timestamp    INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_notebook_activity_activityType_timestamp
                        ON notebook_activity(activityType, timestamp)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_notebook_activity_notebookId
                        ON notebook_activity(notebookId)
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        id            TEXT    NOT NULL PRIMARY KEY,
                        type          TEXT    NOT NULL,
                        title         TEXT    NOT NULL,
                        startEpochDay INTEGER NOT NULL,
                        endEpochDay   INTEGER NOT NULL,
                        allDay        INTEGER NOT NULL,
                        startMinute   INTEGER,
                        endMinute     INTEGER,
                        recurring     INTEGER NOT NULL,
                        data          TEXT    NOT NULL,
                        createdAt     INTEGER NOT NULL,
                        updatedAt     INTEGER NOT NULL,
                        deletedAt     INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_events_startEpochDay_endEpochDay " +
                        "ON events(startEpochDay, endEpochDay)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_recurring ON events(recurring)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_deletedAt ON events(deletedAt)")
            }
        }

        /**
         * data-model-optimization Phase 2: widen the `scratchpad` + `calendar` tables with the same
         * columnar columns + binary `blob` as the `.soil` `notebook` table (see [SoilSchema]).
         * Additive nullable ALTERs — legacy rows keep their JSON in `data` and read via the
         * format-agnostic mappings; new rows are columnar.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("scratchpad", "calendar")) {
                    for ((name, sqlType) in SoilSchema.ADDED_COLUMNS_V4) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN \"$name\" $sqlType")
                    }
                }
            }
        }

        /**
         * data-model-optimization Phase A (index columnar): widen the global-index `objects` table
         * with typed payload columns + a binary `blob`, so notebook/template/folder rows can move off
         * the opaque `data` JSON (see [ObjectEntity]). Additive nullable ALTERs — legacy rows keep
         * their JSON in `data` and read via the format-agnostic [IndexObjectColumns] mappings; new
         * rows are columnar.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = listOf(
                    "pageCount" to "INTEGER", "flags" to "INTEGER", "keyScope" to "TEXT",
                    "lastBackedUpLocal" to "INTEGER", "lastBackedUpDrive" to "INTEGER",
                    "width" to "INTEGER", "height" to "INTEGER", "blob" to "BLOB",
                )
                for ((name, sqlType) in columns) {
                    db.execSQL("ALTER TABLE objects ADD COLUMN \"$name\" $sqlType")
                }
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
