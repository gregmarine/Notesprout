package com.notesprout.android.data.index

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.notesprout.android.crypto.GlobalKey
import com.notesprout.android.crypto.KeyMaterial
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilFileKind
import com.notesprout.android.crypto.SoilMigrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the global index (`notesprout.db`). Encrypted at rest under the global passphrase (Phase 1b)
 * and opened via the derive-once raw-key cache ([KeyMaterial]) so cold launch stays fast.
 *
 * Opening is now potentially async (a one-time plaintext→encrypted migration, or an unlock prompt on
 * a device with no cached key), so it can no longer complete synchronously in Application.onCreate.
 * [ensureReady] does the work; [awaitReady] lets index consumers suspend until it's open. The
 * BootstrapActivity gate drives this before the UI touches the index; MainActivity self-guards for
 * deep-link entries.
 */
object NotesproutIndex {

    enum class PrepareOutcome { READY, NEEDS_UNLOCK }

    @Volatile
    private var instance: NotesproutDatabase? = null

    @Volatile
    private var readyLatch = CompletableDeferred<Unit>()

    private val prepareMutex = Mutex()

    /**
     * Bring the index to an open state. Idempotent and safe to call concurrently.
     *  - missing/empty → create encrypted with a fresh/cached global key
     *  - plaintext (existing user upgrading) → migrate schema, encrypt in place, open
     *  - encrypted + resolvable global key → open via raw key
     *  - encrypted + no usable cached key → [PrepareOutcome.NEEDS_UNLOCK] (caller prompts, then [unlockAndOpen])
     */
    suspend fun ensureReady(context: Context): PrepareOutcome = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withContext PrepareOutcome.READY
            val app = context.applicationContext
            val dbFile = File(app.getExternalFilesDir(null), "notesprout.db")

            // A killed in-place migration (upgrade encrypt / rotation rekey) can leave the index
            // mid-swap. Repair before probing — a missing dbFile would otherwise read as Invalid
            // and a fresh empty index would silently replace the whole library structure.
            runCatching { SoilMigrator.recoverInterruptedMigration(dbFile) }

            when (SoilCrypto.probe(dbFile)) {
                SoilFileKind.Invalid -> {
                    // Fresh install (or empty file): create the index encrypted from the start.
                    val pass = GlobalKey.ensure(app)
                    val db = buildRoom(app, dbFile, SoilCrypto.roomFactory(pass))
                    forceOpen(db) // creates the file + schema (one-time native KDF)
                    instance = db
                    // File now has a salt — cache its raw key so subsequent launches skip the KDF.
                    runCatching { KeyMaterial.rawKeyGlobal(app, KeyMaterial.INDEX_FILE_ID, dbFile, pass) }
                    readyLatch.complete(Unit)
                    PrepareOutcome.READY
                }

                SoilFileKind.Plaintext -> {
                    // Existing user upgrading: bring the schema current while still plaintext, then encrypt.
                    val pdb = buildRoom(app, dbFile, null)
                    forceOpen(pdb)
                    runCatching { pdb.close() }
                    val pass = GlobalKey.ensure(app)
                    SoilMigrator.encryptInPlace(dbFile, pass)
                    val key = KeyMaterial.rawKeyGlobal(app, KeyMaterial.INDEX_FILE_ID, dbFile, pass)
                    val db = buildRoom(app, dbFile, SoilCrypto.roomFactoryRawKey(key))
                    forceOpen(db)
                    instance = db
                    readyLatch.complete(Unit)
                    PrepareOutcome.READY
                }

                SoilFileKind.Encrypted -> {
                    val pass = PassphraseStore.getGlobalPassphrase(app)
                        ?: return@withContext PrepareOutcome.NEEDS_UNLOCK
                    val key = KeyMaterial.rawKeyGlobal(app, KeyMaterial.INDEX_FILE_ID, dbFile, pass)
                    if (!SoilCrypto.verifyRawKey(dbFile, key)) {
                        // Cached global key no longer opens this index (rotated on another device, etc.).
                        KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
                        return@withContext PrepareOutcome.NEEDS_UNLOCK
                    }
                    val db = buildRoom(app, dbFile, SoilCrypto.roomFactoryRawKey(key))
                    forceOpen(db)
                    instance = db
                    readyLatch.complete(Unit)
                    PrepareOutcome.READY
                }
            }
        }
    }

    /**
     * Unlock an encrypted index with a user-supplied passphrase (the [PrepareOutcome.NEEDS_UNLOCK]
     * path). Verifies, caches it as the global passphrase, opens. Returns false on a wrong passphrase.
     */
    suspend fun unlockAndOpen(context: Context, passphrase: String): Boolean = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withContext true
            val app = context.applicationContext
            val dbFile = File(app.getExternalFilesDir(null), "notesprout.db")
            if (!SoilCrypto.verifyPassphrase(dbFile, passphrase)) return@withContext false
            PassphraseStore.setGlobalPassphrase(app, passphrase)
            val key = KeyMaterial.rawKeyGlobal(app, KeyMaterial.INDEX_FILE_ID, dbFile, passphrase)
            val db = buildRoom(app, dbFile, SoilCrypto.roomFactoryRawKey(key))
            forceOpen(db)
            instance = db
            readyLatch.complete(Unit)
            true
        }
    }

    /** Suspend until the index is open (drives index-dependent startup work behind the gate). */
    suspend fun awaitReady() = readyLatch.await()

    fun isReady(): Boolean = instance != null

    private fun buildRoom(
        context: Context,
        dbFile: File,
        factory: SupportSQLiteOpenHelper.Factory?,
    ): NotesproutDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext, NotesproutDatabase::class.java, dbFile.absolutePath
        )
            .addCallback(NotesproutDatabase.openCallback())
            .addMigrations(
                NotesproutDatabase.MIGRATION_1_2,
                NotesproutDatabase.MIGRATION_2_3,
                NotesproutDatabase.MIGRATION_3_4,
                NotesproutDatabase.MIGRATION_4_5,
                NotesproutDatabase.MIGRATION_5_6,
                NotesproutDatabase.MIGRATION_6_7,
                NotesproutDatabase.MIGRATION_7_8,
                NotesproutDatabase.MIGRATION_8_9,
            )
        // Plaintext opens (factory == null — the one-time upgrade path) must be wrapped too:
        // Room's default framework helper DELETES the database on a corruption report, and this
        // file is the entire library structure. Mirrors SoilDatabase.builder's default.
        builder.openHelperFactory(
            factory ?: com.notesprout.android.data.NonDestructiveOpenHelperFactory(
                androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            )
        )
        return builder.build()
    }

    /** Force the underlying connection open (runs create/migrations) via a trivial PRAGMA. */
    private fun forceOpen(db: NotesproutDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
    }

    fun db(): NotesproutDatabase =
        instance ?: throw IllegalStateException("NotesproutIndex is not open — call ensureReady() first")

    fun dao(): ObjectDao = db().objectDao()

    fun scratchpadDao(): ScratchpadDao = db().scratchpadDao()

    fun calendarDao(): CalendarDao = db().calendarDao()

    fun notebookActivityDao(): NotebookActivityDao = db().notebookActivityDao()

    fun eventDao(): EventDao = db().eventDao()

    fun taskDao(): TaskDao = db().taskDao()

    suspend fun checkpointAndVacuum() = withContext(Dispatchers.IO) {
        try {
            val raw = db().openHelper.writableDatabase
            raw.query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
            raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e("NotesproutIndex", "checkpointAndVacuum failed", e)
        }
    }

    /**
     * Re-key the encrypted index from [oldPassphrase] to [newPassphrase] as part of a global
     * rotation. Closes the live connection (required — the codebase re-keys via file round-trip,
     * not the on-device-unreliable `PRAGMA rekey`), re-keys the file, refreshes the cached raw key
     * to the new salt, and reopens. Idempotent: a file already readable with [newPassphrase] (from a
     * crash-interrupted prior run) skips the round-trip.
     *
     * Holds [prepareMutex] so it can't race the open paths. There is a brief window where the index
     * is closed — callers run this only from the modal, quiesced rotation flow. Does NOT touch the
     * cached global passphrase (the rotation orchestrator updates that last, after every target).
     */
    suspend fun rekey(context: Context, oldPassphrase: String, newPassphrase: String) = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val app = context.applicationContext
            val dbFile = File(app.getExternalFilesDir(null), "notesprout.db")
            val alreadyNew = SoilCrypto.verifyPassphrase(dbFile, newPassphrase)

            // Close the live connection so the file can be replaced (or reopened cleanly under the new key).
            instance?.let { db ->
                runCatching {
                    db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }
                db.close()
                instance = null
                readyLatch = CompletableDeferred()
            }

            if (!alreadyNew) {
                try {
                    SoilMigrator.rekeyInPlace(dbFile, oldPassphrase, newPassphrase)
                } catch (e: Throwable) {
                    // rekeyInPlace is atomic — on failure the file is still under the OLD key. Reopen
                    // with it so the index is never left closed, then surface the failure.
                    KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
                    val oldKey = KeyMaterial.rawKeyGlobal(app, KeyMaterial.INDEX_FILE_ID, dbFile, oldPassphrase)
                    val restored = buildRoom(app, dbFile, SoilCrypto.roomFactoryRawKey(oldKey))
                    forceOpen(restored)
                    instance = restored
                    if (!readyLatch.isCompleted) readyLatch.complete(Unit)
                    throw e
                }
            }

            // Point the raw-key cache at the new salt, then reopen.
            KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
            val key = KeyMaterial.rawKeyGlobal(app, KeyMaterial.INDEX_FILE_ID, dbFile, newPassphrase)
            val reopened = buildRoom(app, dbFile, SoilCrypto.roomFactoryRawKey(key))
            forceOpen(reopened)
            instance = reopened
            if (!readyLatch.isCompleted) readyLatch.complete(Unit)
        }
    }

    suspend fun seal() = withContext(Dispatchers.IO) {
        // prepareMutex like every other open/close path — sealing mid-ensureReady (or vice versa)
        // could interleave a close with a fresh open and throw on the closed instance.
        prepareMutex.withLock {
            val db = instance ?: return@withLock
            db.openHelper.writableDatabase.let { raw ->
                raw.query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
                raw.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
            db.close()
            instance = null
            readyLatch = CompletableDeferred()
        }
    }
}
