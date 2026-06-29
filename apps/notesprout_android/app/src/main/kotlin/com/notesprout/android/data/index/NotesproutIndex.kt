package com.notesprout.android.data.index

import android.content.Context
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object NotesproutIndex {

    @Volatile
    private var instance: NotesproutDatabase? = null

    fun open(context: Context) {
        if (instance != null) return
        synchronized(this) {
            if (instance != null) return
            val dbFile = File(context.getExternalFilesDir(null), "notesprout.db")
            instance = Room.databaseBuilder(
                context.applicationContext,
                NotesproutDatabase::class.java,
                dbFile.absolutePath
            )
                .addCallback(NotesproutDatabase.openCallback())
                .addMigrations(
                    NotesproutDatabase.MIGRATION_1_2,
                    NotesproutDatabase.MIGRATION_2_3,
                )
                .build()
        }
    }

    fun db(): NotesproutDatabase =
        instance ?: throw IllegalStateException("NotesproutIndex is not open — call open() first")

    fun dao(): ObjectDao = db().objectDao()

    fun scratchpadDao(): ScratchpadDao = db().scratchpadDao()

    fun calendarDao(): CalendarDao = db().calendarDao()

    suspend fun checkpointAndVacuum() = withContext(Dispatchers.IO) {
        try {
            val raw = db().openHelper.writableDatabase
            raw.query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
            raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e("NotesproutIndex", "checkpointAndVacuum failed", e)
        }
    }

    suspend fun seal() = withContext(Dispatchers.IO) {
        val db = db()
        db.openHelper.writableDatabase.let { raw ->
            raw.query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
            raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        db.close()
        instance = null
    }
}
