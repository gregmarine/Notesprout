package com.notesprout.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SoilDatabase(context: Context, filePath: String) {

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(filePath, null)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    companion object {
        private const val SCHEMA_VERSION = 1
    }

    init {
        db.execSQL("PRAGMA journal_mode=WAL")
        db.execSQL("PRAGMA foreign_keys=OFF")
        openOrMigrateSchema()
    }

    private fun openOrMigrateSchema() {
        val version = db.version
        when {
            version == 0 -> createSchema()
            version == SCHEMA_VERSION -> Unit
            version > SCHEMA_VERSION -> throw IllegalStateException(
                "Database schema version $version is newer than this version of NoteSprout supports."
            )
            else -> runMigrations(version)
        }
    }

    private fun createSchema() {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS objects (
                id          TEXT    PRIMARY KEY,
                parentId    TEXT    NOT NULL,
                pluginId    TEXT    NOT NULL,
                boundingBox TEXT    NOT NULL,
                "order"     INTEGER NOT NULL DEFAULT 0,
                createdAt   INTEGER NOT NULL,
                updatedAt   INTEGER NOT NULL,
                deletedAt   INTEGER,
                syncVersion INTEGER NOT NULL DEFAULT 0,
                data        TEXT    NOT NULL
            )"""
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_objects_plugin ON objects(pluginId)"
        )
        db.execSQL(
            """CREATE INDEX IF NOT EXISTS idx_objects_parent_order
               ON objects(parentId, "order", deletedAt)"""
        )
        db.version = SCHEMA_VERSION
    }

    @Suppress("UNUSED_PARAMETER")
    private fun runMigrations(fromVersion: Int) {
        // Future migrations: add `when (fromVersion) { 1 -> migrate1To2() }` blocks here
    }

    // -------------------------------------------------------------------------
    // INSERT / UPDATE
    // -------------------------------------------------------------------------

    suspend fun saveObject(obj: BaseObject): Unit = withContext(dbDispatcher) {
        val now = System.currentTimeMillis()
        val exists = db.rawQuery(
            "SELECT id FROM objects WHERE id = ?", arrayOf(obj.id)
        ).use { it.moveToFirst() }

        db.beginTransaction()
        try {
            if (!exists) {
                db.execSQL(
                    """INSERT INTO objects
                       (id, parentId, pluginId, boundingBox, "order",
                        createdAt, updatedAt, deletedAt, syncVersion, data)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)""",
                    arrayOf<Any?>(
                        obj.id, obj.parentId, obj.pluginId,
                        obj.boundingBox.toJson(), obj.order,
                        obj.createdAt, now, obj.deletedAt, obj.data
                    )
                )
            } else {
                db.execSQL(
                    """UPDATE objects
                       SET parentId = ?, pluginId = ?, boundingBox = ?, "order" = ?,
                           updatedAt = ?, deletedAt = ?,
                           syncVersion = syncVersion + 1, data = ?
                       WHERE id = ?""",
                    arrayOf<Any?>(
                        obj.parentId, obj.pluginId, obj.boundingBox.toJson(), obj.order,
                        now, obj.deletedAt, obj.data, obj.id
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // -------------------------------------------------------------------------
    // SOFT DELETE
    // -------------------------------------------------------------------------

    suspend fun softDelete(id: String): Unit = withContext(dbDispatcher) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "UPDATE objects SET deletedAt = ?, updatedAt = ?, syncVersion = syncVersion + 1 WHERE id = ?",
            arrayOf<Any?>(now, now, id)
        )
    }

    // -------------------------------------------------------------------------
    // QUERIES
    // -------------------------------------------------------------------------

    suspend fun getObject(id: String): BaseObject? = withContext(dbDispatcher) {
        db.rawQuery(
            "SELECT * FROM objects WHERE id = ? AND deletedAt IS NULL",
            arrayOf(id)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toBaseObject() else null
        }
    }

    suspend fun getChildren(
        parentId: String,
        pluginId: String? = null
    ): List<BaseObject> = withContext(dbDispatcher) {
        val (sql, args) = if (pluginId != null) {
            """SELECT * FROM objects
               WHERE parentId = ? AND deletedAt IS NULL AND pluginId = ?
               ORDER BY "order" ASC""" to arrayOf(parentId, pluginId)
        } else {
            """SELECT * FROM objects
               WHERE parentId = ? AND deletedAt IS NULL
               ORDER BY "order" ASC""" to arrayOf(parentId)
        }
        db.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toBaseObject()) }
        }
    }

    suspend fun getNotebook(pluginId: String): BaseObject? = withContext(dbDispatcher) {
        db.rawQuery(
            """SELECT * FROM objects
               WHERE pluginId = ? AND deletedAt IS NULL LIMIT 1""",
            arrayOf(pluginId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toBaseObject() else null
        }
    }

    suspend fun getAllForSync(sinceVersion: Long): List<BaseObject> = withContext(dbDispatcher) {
        db.rawQuery(
            "SELECT * FROM objects WHERE syncVersion > ? ORDER BY syncVersion ASC",
            arrayOf(sinceVersion.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toBaseObject()) }
        }
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------

    fun close() {
        db.close()
    }
}
