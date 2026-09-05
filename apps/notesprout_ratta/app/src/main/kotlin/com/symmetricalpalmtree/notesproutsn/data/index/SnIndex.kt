package com.symmetricalpalmtree.notesproutsn.data.index

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.GlobalKey
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilFileKind
import com.symmetricalpalmtree.notesproutsn.crypto.SoilRekey
import com.symmetricalpalmtree.notesproutsn.data.indexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the global index (`notesprout.db`) — encrypted under the global key from the first byte,
 * opened via the derive-once raw-key cache so cold launch stays fast.
 *
 * **`BootstrapActivity` is the only caller of [ensureReady] / [unlockAndOpen].** Every other
 * screen checks [isReady] through `IndexGuard` in `onCreate` and bounces back to Bootstrap when
 * the answer is no (a task Android rebuilt after a background process kill lands there).
 * **Nothing closes it in ordinary life** — it opens once per process and stays open — which is
 * what makes a single `onCreate` check sufficient. The one exception is [closeForRotation]
 * (arc 26 / U2): the global rotation must re-key the index file, which cannot happen under a live
 * Room connection; after it the Encryption screen touches nothing but dialogs and the only way out
 * is a relaunch through Bootstrap (`IndexGuard` bounces every other screen).
 *
 * Open state machine (probe the file header, never open to find out):
 *  - `Invalid` + no file (or zero bytes) → mint (or reuse) the global key → create encrypted →
 *    derive + cache the raw key → [PrepareOutcome.FIRST_LAUNCH] (the caller shows the recovery key
 *    once). `Invalid` over an existing non-empty file is a damaged index —
 *    [PrepareOutcome.DAMAGED_FILE], never created over, never deleted.
 *  - `Encrypted` → cached passphrase? → raw key **verified** against the file → open → READY.
 *    No cached passphrase, or the cached one no longer fits → [PrepareOutcome.NEEDS_UNLOCK].
 *  - `Plaintext` → impossible for SN (there is no plaintext mode). Never opened: it would either
 *    be a foreign file or a damaged one, and the framework's corruption path deletes.
 *    [PrepareOutcome.FOREIGN_FILE].
 */
object SnIndex {

    enum class PrepareOutcome { READY, FIRST_LAUNCH, NEEDS_UNLOCK, FOREIGN_FILE, DAMAGED_FILE }

    private const val TAG = "SnIndex"

    @Volatile
    private var instance: IndexDatabase? = null

    private val prepareMutex = Mutex()

    fun isReady(): Boolean = instance != null

    fun db(): IndexDatabase =
        instance ?: throw IllegalStateException("SnIndex is not open — BootstrapActivity must run first")

    fun dao(): ObjectDao = db().objectDao()

    /** Bring the index to an open state. Idempotent; safe to call concurrently. IO. */
    suspend fun ensureReady(context: Context): PrepareOutcome = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withContext PrepareOutcome.READY
            val app = context.applicationContext
            val file = indexFile(app)

            // Arc 26 / U2: an index missing because a rekey commit died between its two renames is
            // NOT a fresh install — its bytes are `notesprout.db.rekey.tmp` / `.old.bak` beside it.
            // Recover with the cached passphrase before the probe can ever answer "create". With no
            // cached passphrase nothing can be verified and nothing is touched: DAMAGED_FILE, and
            // both files stay for a person to look at.
            if ((!file.exists() || file.length() == 0L) && SoilRekey.hasLeftovers(file)) {
                val pass = PassphraseStore.getGlobalPassphrase(app)
                    ?: return@withContext PrepareOutcome.DAMAGED_FILE
                val result = SoilRekey.recoverOne(file) { SoilCrypto.verifyPassphrase(it, pass) }
                Log.w(TAG, "index rekey leftovers: $result")
                if (!file.exists() || file.length() == 0L) return@withContext PrepareOutcome.DAMAGED_FILE
            }

            when (SoilCrypto.probe(file)) {
                SoilFileKind.Invalid -> {
                    // `Invalid` covers missing/empty AND unreadable/truncated. Only a genuinely
                    // absent (or zero-byte) file is a fresh install; an existing remnant — say an
                    // interrupted restore's 12 bytes — must never be built over: a create-capable
                    // open here would initialize a brand-new empty index on top of it (library gone,
                    // every notebook orphaned), the never-delete-on-corruption rule's whole point.
                    if (file.exists() && file.length() > 0L) return@withContext PrepareOutcome.DAMAGED_FILE
                    // Fresh install: create the index encrypted from the start.
                    val pass = GlobalKey.ensure(app)
                    file.parentFile?.mkdirs()
                    val db = build(app, file, SoilCrypto.roomFactory(pass))
                    forceOpen(db) // creates file + schema (the one native KDF)
                    finishOpen(db, pass)
                    // The file now has a salt — cache its raw key so later launches skip the KDF.
                    runCatching { KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass) }
                        .onFailure { Log.w(TAG, "raw-key warm failed after create", it) }
                    PrepareOutcome.FIRST_LAUNCH
                }

                SoilFileKind.Encrypted -> {
                    val pass = PassphraseStore.getGlobalPassphrase(app)
                        ?: return@withContext PrepareOutcome.NEEDS_UNLOCK
                    val key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass)
                    if (!SoilCrypto.verifyRawKey(file, key)) {
                        // The cached material no longer opens this file (restored from elsewhere,
                        // etc.). Drop the derived key; if the passphrase itself is right, re-derive.
                        KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
                        if (!SoilCrypto.verifyPassphrase(file, pass)) return@withContext PrepareOutcome.NEEDS_UNLOCK
                        val fresh = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, pass)
                        val db = build(app, file, SoilCrypto.roomFactoryRawKey(fresh))
                        forceOpen(db)
                        finishOpen(db, pass)
                        return@withContext PrepareOutcome.READY
                    }
                    val db = build(app, file, SoilCrypto.roomFactoryRawKey(key))
                    forceOpen(db)
                    finishOpen(db, pass)
                    PrepareOutcome.READY
                }

                SoilFileKind.Plaintext -> PrepareOutcome.FOREIGN_FILE
            }
        }
    }

    /**
     * Unlock with a user-supplied passphrase (the NEEDS_UNLOCK path). Verifies against the file
     * first — never opens Room with an unverified key — caches it as the global passphrase, opens.
     * False on a wrong passphrase; the file is untouched either way. IO.
     */
    suspend fun unlockAndOpen(context: Context, passphrase: String): Boolean = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            if (instance != null) return@withContext true
            val app = context.applicationContext
            val file = indexFile(app)
            if (!SoilCrypto.verifyPassphrase(file, passphrase)) return@withContext false
            PassphraseStore.setGlobalPassphrase(app, passphrase)
            KeyMaterial.invalidate(app, KeyMaterial.INDEX_FILE_ID)
            val key = KeyMaterial.rawKey(app, KeyMaterial.INDEX_FILE_ID, file, passphrase)
            val db = build(app, file, SoilCrypto.roomFactoryRawKey(key))
            forceOpen(db)
            finishOpen(db, passphrase)
            true
        }
    }

    /**
     * Arc 26 / U2 — the one door that closes the index: checkpoint, close, forget the instance.
     * For the global rotation only, which must re-key the file and cannot while Room holds it.
     * Every other screen is behind `IndexGuard` and bounces to Bootstrap from here on; the caller
     * exits through a relaunch and never calls [dao] again. Idempotent; never throws. IO.
     */
    suspend fun closeForRotation() = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val db = instance ?: return@withLock
            try {
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            } catch (e: Exception) {
                Log.w(TAG, "checkpoint before close failed", e)
            }
            runCatching { db.close() }.onFailure { Log.w(TAG, "close failed", it) }
            instance = null
            Slog.d(TAG) { "index closed for rotation" }
        }
    }

    private fun finishOpen(db: IndexDatabase, passphrase: String) {
        instance = db
        KeySession.set(passphrase)
    }

    private fun build(context: Context, file: java.io.File, factory: SupportSQLiteOpenHelper.Factory): IndexDatabase =
        Room.databaseBuilder(context.applicationContext, IndexDatabase::class.java, file.absolutePath)
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
                    db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
                }
            })
            .build()

    /** Force the connection open (runs create) via a trivial PRAGMA. */
    private fun forceOpen(db: IndexDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
    }

    /** Flush the WAL into the main file (before a debug pull, or a future backup). Never throws. IO. */
    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        try {
            db().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "checkpoint failed", e)
        }
    }

    /**
     * Arc 17 / K1: the opportunistic index purge. Bootstrap calls this once the index is open —
     * the one moment it has no other reader, so the `VACUUM` cannot lose to a busy library screen.
     * The `EXISTS` gate keeps the ordinary launch at one trivial query. On the launch path the
     * `VACUUM` also waits for [BOOT_MIN_RECLAIM_BYTES] of freelist (K3 review — one soft-deleted
     * clipboard row must not cost a whole-index rewrite before the library appears); the backup
     * run passes 0, because a pre-copy compaction wants every byte back. Never throws. IO.
     */
    suspend fun compactIfNeeded(minReclaimBytes: Long = BOOT_MIN_RECLAIM_BYTES) = withContext(Dispatchers.IO) {
        try {
            val raw = db().openHelper.writableDatabase
            if (IndexCompactor.hasSoftDeletedRows(raw)) IndexCompactor.compact(raw, minReclaimBytes)
        } catch (e: Exception) {
            Log.w(TAG, "compact skipped", e)
        }
    }

    /** The bootstrap `VACUUM` floor: below this much reclaimable freelist, deleting the rows is
     *  enough and the rewrite waits for the backup run (or for the freelist to grow past it). */
    private const val BOOT_MIN_RECLAIM_BYTES = 256L * 1024
}
