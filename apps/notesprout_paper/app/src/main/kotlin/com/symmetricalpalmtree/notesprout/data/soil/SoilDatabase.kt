package com.symmetricalpalmtree.notesprout.data.soil

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesprout.crypto.KeyOpener
import com.symmetricalpalmtree.notesprout.crypto.SoilCrypto
import java.io.File

/**
 * Room database for one `.soil` file. **One instance per open notebook**, owned by the notebook
 * session; never a singleton. `user_version` = [SoilSchema.SOIL_VERSION].
 *
 * Two ways in, both keyed, both non-destructive:
 *  - [open] — the file must already exist (throws otherwise; never fabricates a stub).
 *  - [create] — the new-notebook path only; refuses an existing non-empty file.
 * Leave via [seal].
 */
@Database(entities = [SoilObjectEntity::class], version = SoilSchema.SOIL_VERSION, exportSchema = false)
abstract class SoilDatabase : RoomDatabase() {

    abstract fun dao(): SoilDao

    /** The raw connection, for `notebook_meta` and PRAGMAs. */
    fun raw(): SupportSQLiteDatabase = openHelper.writableDatabase

    companion object {
        private const val TAG = "SoilDatabase"

        /** Open an existing notebook file. Fast raw-key path when cached; see [KeyOpener]. IO thread. */
        fun open(context: Context, notebookId: String, file: File, passphrase: String): SoilDatabase {
            SoilCrypto.requireExisting(file)
            val factory = KeyOpener.roomFactoryFor(context, notebookId, file, passphrase)
            return build(context, file, factory).also { forceOpen(it) }
        }

        /** Create a brand-new notebook file with the schema in place. New-notebook flow only. IO thread. */
        fun create(context: Context, notebookId: String, file: File, passphrase: String): SoilDatabase {
            require(!file.exists() || file.length() == 0L) { "refusing to create over an existing file: ${file.name}" }
            file.parentFile?.mkdirs()
            val db = build(context, file, SoilCrypto.roomFactory(passphrase))
            forceOpen(db) // creates file + schema (one native KDF)
            KeyOpener.warm(context, notebookId, file, passphrase)
            return db
        }

        private fun build(context: Context, file: File, factory: SupportSQLiteOpenHelper.Factory): SoilDatabase =
            Room.databaseBuilder(context.applicationContext, SoilDatabase::class.java, file.absolutePath)
                .openHelperFactory(factory)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(openCallback())
                .build()

        private fun forceOpen(db: SoilDatabase) {
            db.raw().query("PRAGMA user_version").use { it.moveToFirst() }
        }

        /** Connection-level PRAGMAs (not persisted in the file) + the non-entity meta table. */
        private fun openCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(SoilSchema.CREATE_META)
                // auto_vacuum must be set before any table has pages; onCreate is that moment.
                db.query("PRAGMA auto_vacuum = INCREMENTAL").use { it.moveToFirst() }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL(SoilSchema.CREATE_META)
                db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
                db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
            }
        }
    }

    /**
     * Close sequence: checkpoint the WAL back into the main file (TRUNCATE), close, and remove a
     * stray `-journal`. Meta refresh is the caller's job (it needs the index). Each step guarded;
     * never throws. IO thread.
     */
    fun seal(file: File) {
        try {
            raw().query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint failed for ${file.name}", e)
        }
        try {
            close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed for ${file.name}", e)
        }
        val journal = File(file.path + "-journal")
        if (journal.exists() && journal.length() == 0L) journal.delete()
    }
}
