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
        TaskEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class NotesproutDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao
    abstract fun scratchpadDao(): ScratchpadDao
    abstract fun calendarDao(): CalendarDao
    abstract fun notebookActivityDao(): NotebookActivityDao
    abstract fun eventDao(): EventDao
    abstract fun taskDao(): TaskDao

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

        /**
         * data-model-optimization Phase B (index list membership → child rows): add the relational
         * `refId` + `sortOrder` columns so a `list_item` row can point at its member with a position.
         * Additive nullable ALTERs; the legacy inline `ListObject`/`TemplateListObject` JSON on the list
         * row is converted to child rows lazily (see IndexRepository.ensure*ListExists / mutators).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE objects ADD COLUMN \"refId\" TEXT")
                db.execSQL("ALTER TABLE objects ADD COLUMN \"sortOrder\" INTEGER")
            }
        }

        /**
         * The `tasks` table (see [TaskEntity]) — the task manager's store. Unlike `events` it is
         * **fully columnar**: the recurrence rule lives in typed columns and there is no `data`
         * payload, so nothing in this table is ever JSON.
         *
         * `type` + `parentId` are the reservation for routines; only `TASK` rows are written today.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tasks (
                        id               TEXT    NOT NULL PRIMARY KEY,
                        parentId         TEXT,
                        type             TEXT    NOT NULL,
                        title            TEXT    NOT NULL,
                        state            TEXT    NOT NULL,
                        dueEpochDay      INTEGER,
                        "order"          INTEGER NOT NULL DEFAULT 0,
                        seriesId         TEXT,
                        seriesIndex      INTEGER,
                        seriesAnchorDay  INTEGER,
                        recurFreq        TEXT,
                        recurInterval    INTEGER,
                        recurWeekdays    INTEGER,
                        recurMonthlyMode TEXT,
                        recurEndMode     TEXT,
                        recurEndEpochDay INTEGER,
                        recurEndCount    INTEGER,
                        resolvedAt       INTEGER,
                        createdAt        INTEGER NOT NULL,
                        updatedAt        INTEGER NOT NULL,
                        deletedAt        INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tasks_state_dueEpochDay " +
                        "ON tasks(state, dueEpochDay)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_seriesId ON tasks(seriesId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_parentId ON tasks(parentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_deletedAt ON tasks(deletedAt)")
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
