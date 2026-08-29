package com.symmetricalpalmtree.notesproutsn.data.soil

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesproutsn.crypto.KeyOpener
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room database for one `.soil` file. **One instance per open notebook**, owned by the notebook
 * session (R3); never a singleton. `user_version` = [SoilSchema.SOIL_VERSION].
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
            // Claimed only once the connection is really up (arc 15 / E1) — a failed open holds
            // nothing, and a claim it never made is a claim nothing would ever release.
            return build(context, file, factory).also { forceOpen(it); SoilOpenFiles.claim(file) }
        }

        /** Create a brand-new notebook file with the schema in place. New-notebook flow only. IO thread. */
        fun create(context: Context, notebookId: String, file: File, passphrase: String): SoilDatabase {
            require(!file.exists() || file.length() == 0L) { "refusing to create over an existing file: ${file.name}" }
            file.parentFile?.mkdirs()
            val db = build(context, file, SoilCrypto.roomFactory(passphrase))
            forceOpen(db) // creates file + schema (one native KDF)
            SoilOpenFiles.claim(file)
            KeyOpener.warm(context, notebookId, file, passphrase)
            return db
        }

        /**
         * One-shot **read-only** visit to a notebook that is open nowhere else: open through the
         * one [open] door (global key from [KeySession]), run [block] over the DAO, and **always**
         * seal — an unsealed open strands the connection and its WAL sidecar for the process
         * lifetime (the R6 lesson). This is the single owner of that ritual (K5 review) — never
         * hand-roll the open → read → seal-in-finally shape at a call site.
         *
         * Null on any failure at all (no key session, file missing/empty, unreadable, [block]
         * threw): callers treat null as "cannot answer", never as data. MUST NOT be pointed at a
         * notebook whose `.soil` is already open — one file, one connection, family-wide.
         */
        suspend fun <T> readOnce(context: Context, notebookId: String, block: suspend (SoilDao) -> T): T? =
            withContext(Dispatchers.IO) {
                val passphrase = KeySession.get() ?: return@withContext null
                val file = soilFile(context, notebookId)
                if (!file.exists() || file.length() == 0L) return@withContext null
                val db = try {
                    open(context, notebookId, file, passphrase)
                } catch (e: Exception) {
                    Log.w(TAG, "readOnce could not open $notebookId", e)
                    return@withContext null
                }
                try {
                    block(db.dao())
                } catch (e: Exception) {
                    Log.w(TAG, "readOnce could not read $notebookId", e)
                    null
                } finally {
                    db.seal(file)   // never throws (its own contract)
                }
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

    /** Whether this handle has given its [SoilOpenFiles] claim back — at most once per handle. */
    private val claimReleased = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Close sequence: checkpoint the WAL back into the main file (TRUNCATE), close, and remove a
     * stray empty `-journal`. Meta refresh is the caller's step before seal (it needs the index).
     * Each step guarded; never throws. IO thread.
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
        // Released *after* the close, and whatever the close did (arc 15 / E1): the file is only
        // free for an export's copy once no connection is left, and a checkpoint that threw must
        // still not leave the claim standing — an export would refuse forever. The release is
        // handle-scoped (arc-15 review): two seal launches on one handle are constructible
        // (close() on appScope racing sealAbandonedOpen), and a bare second decrement would take
        // down a *new* session's claim — isOpen() answering false under a live writer is exactly
        // what this registry exists to prevent.
        if (claimReleased.compareAndSet(false, true)) SoilOpenFiles.release(file)
        val journal = File(file.path + "-journal")
        if (journal.exists() && journal.length() == 0L) journal.delete()
        // Arc 17 / K1: with no connection left, a fully-checkpointed WAL and its -shm are noise —
        // the Garden holds only .soil files after a clean close. A non-empty WAL (the checkpoint
        // above failed) is live data and stays. Gated on the registry: if the one-file-one-
        // connection rule is ever broken, deleting a -shm under the surviving connection's map is
        // exactly the kind of damage this pass must not add.
        if (!SoilOpenFiles.isOpen(file)) SoilCompactor.sweepSidecars(file)
    }
}
