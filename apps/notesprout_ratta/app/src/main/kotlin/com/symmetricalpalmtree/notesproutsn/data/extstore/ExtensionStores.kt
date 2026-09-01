package com.symmetricalpalmtree.notesproutsn.data.extstore

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.KeyOpener
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilLockedException
import com.symmetricalpalmtree.notesproutsn.data.extensionStoreFile
import java.io.File

/**
 * Open-or-create the host-owned encrypted store of one extension package — `Garden/<pkg>.db`,
 * SQLCipher under the **global** passphrase (`KeySession`, set once the index is open; every caller
 * is behind `IndexGuard`). Raw-key cache id = [fileIdFor] (`ext:<pkg>`, which can never collide
 * with a notebook UUID or the index's own id).
 *
 * This is SN's **second named create entry point** (after `SoilDatabase.create`), and it obeys the
 * same two doors: a missing / empty file is created the way `SoilDatabase.create` creates a `.soil`;
 * an existing file is opened the `SoilDatabase.open` way (`requireExisting` → cached raw key
 * verified against the file, passphrase fallback + warm). Every factory comes from [SoilCrypto], so
 * every open is `NonDestructiveOpenHelperFactory`-wrapped — a wrong key reports corruption without
 * deleting the file.
 *
 * **The format ladder (arc 22 / X1)** rides `PRAGMA user_version` through the open helper's own
 * lifecycle, so it is transactional for free: the helper's callback version is
 * [StoreFormat.VERSION]; a `0` file gets `onCreate` (fresh), a `1` file — the Room-era key/value
 * store — gets `onUpgrade` (**the wipe**: `kv` + `room_master_table` dropped, no migration, the
 * user's call), a file above it gets `onDowngrade`, which **throws** — a newer host wrote it, the
 * file is left exactly as found, and the extension is "unavailable". [StoreFormat.decide] is the
 * pure table those callbacks act on.
 *
 * Process-lifetime cache: one open DB per package, never closed except by [closeAll] (tests /
 * debug). The `.db` outlives the extension — uninstalling or disabling one leaves its store in
 * place, because removing an extension's data is a deliberate act, not a side effect.
 *
 * **Pre-open rule:** callers open the store on IO **before** binding the extension, so a cold open
 * (KDF ≈ 0.5–1.5 s on e-ink when the raw key is not cached yet) is never inside a call's timeout
 * window. Blocking; IO thread only.
 */
object ExtensionStores {

    private const val TAG = "ExtensionStores"

    private val cache = HashMap<String, ExtensionStoreDatabase>()

    /** Raw-key cache id for [pkg]'s store. */
    fun fileIdFor(pkg: String): String = "ext:$pkg"

    /** The open store for [pkg] (created if absent). Throws [SoilLockedException] when no global key
     *  is in session, and `IllegalStateException` for a store a newer host wrote. IO. */
    @Synchronized
    fun open(context: Context, pkg: String): ExtensionStoreDatabase {
        cache[pkg]?.let { return it }
        val app = context.applicationContext
        val file = extensionStoreFile(app, pkg)
        val pass = KeySession.get() ?: throw SoilLockedException("no global key in session")
        val fileId = fileIdFor(pkg)
        val db = if (!file.exists() || file.length() == 0L) {
            // Create — the SoilDatabase.create pattern.
            file.parentFile?.mkdirs()
            build(app, file, SoilCrypto.roomFactory(pass), pkg).also {
                forceOpen(it) // creates file + host_schema (one native KDF)
                KeyOpener.warm(app, fileId, file, pass)
                Slog.d(TAG) { "created store for $pkg" }
            }
        } else {
            // Open — the SoilDatabase.open pattern.
            SoilCrypto.requireExisting(file)
            build(app, file, KeyOpener.roomFactoryFor(app, fileId, file, pass), pkg).also {
                try {
                    forceOpen(it)
                } catch (t: Throwable) {
                    runCatching { it.close() }
                    throw t
                }
                Slog.d(TAG) { "opened store for $pkg" }
            }
        }
        cache[pkg] = db
        return db
    }

    /**
     * Fold [pkg]'s WAL back into its main file **if this process has the store open** (arc 21 / W5,
     * the backup run's pre-copy step). Best effort — the WAL's length afterwards is the honest
     * verdict, and the backup's WAL-alongside rule covers whatever the checkpoint could not absorb.
     *
     * A store this process never opened is deliberately left alone: opening one costs a cold KDF
     * (seconds on e-ink) and takes a connection on a file the run is about to read, to buy nothing
     * a copy of the `-wal` alongside does not already buy. Never throws. IO.
     */
    @Synchronized
    fun checkpointIfOpen(pkg: String) {
        val db = cache[pkg] ?: return
        runCatching {
            db.writable().query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }.onFailure { Slog.d(TAG) { "checkpoint failed for $pkg: ${it.message}" } }
    }

    /** Close every cached store (tests / debug). Never throws. */
    @Synchronized
    fun closeAll() {
        for ((pkg, db) in cache) {
            runCatching { db.close() }.onFailure { Slog.d(TAG) { "close failed for $pkg: ${it.message}" } }
        }
        cache.clear()
    }

    private fun build(context: Context, file: File, factory: SupportSQLiteOpenHelper.Factory, pkg: String): ExtensionStoreDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
            .name(file.absolutePath)
            .callback(FormatCallback(pkg))
            .build()
        val helper = factory.create(configuration)
        helper.setWriteAheadLoggingEnabled(true)
        return ExtensionStoreDatabase(helper)
    }

    /** Force the connection open (runs the format ladder) via a trivial PRAGMA. */
    private fun forceOpen(db: ExtensionStoreDatabase) {
        db.writable().query("PRAGMA user_version").use { it.moveToFirst() }
    }

    /** The helper's lifecycle, acting on [StoreFormat.decide]. */
    private class FormatCallback(private val pkg: String) : SupportSQLiteOpenHelper.Callback(StoreFormat.VERSION) {

        override fun onConfigure(db: SupportSQLiteDatabase) {
            // A pool-level setting, so every connection the pool opens enforces the extension's
            // declared `REFERENCES … ON DELETE` — documented as a promise of the seam.
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            settle(db, 0)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            settle(db, oldVersion)
        }

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // A newer host wrote this file. Never-delete-on-corruption: throw, leave it as found.
            throw IllegalStateException("store for $pkg is format version $oldVersion — this host writes $newVersion")
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query("PRAGMA wal_autocheckpoint = 100").use { it.moveToFirst() }
            db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
        }

        /** Inside the helper's own version transaction: fresh create, or the legacy wipe. */
        private fun settle(db: SupportSQLiteDatabase, from: Int) {
            val legacy = StoreFormat.LEGACY_TABLES.filter { hasTable(db, it) }
            when (StoreFormat.decide(from, legacy.isNotEmpty())) {
                StoreFormat.Decision.FRESH -> Slog.d(TAG) { "fresh store for $pkg" }
                StoreFormat.Decision.WIPE -> {
                    val rows = if ("kv" in legacy) count(db, "kv") else 0
                    for (t in legacy) db.execSQL("DROP TABLE IF EXISTS $t")
                    Slog.d(TAG) { "wiped legacy store for $pkg (format $from, $rows kv row(s) dropped)" }
                }
                StoreFormat.Decision.OPEN, StoreFormat.Decision.REFUSE ->
                    throw IllegalStateException("unexpected format state $from for $pkg")
            }
            db.execSQL(StoreFormat.CREATE_HOST_SCHEMA)
            db.execSQL(StoreFormat.SEED_HOST_SCHEMA)
        }

        private fun hasTable(db: SupportSQLiteDatabase, name: String): Boolean =
            db.query("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
                .use { it.moveToFirst() && it.getInt(0) > 0 }

        private fun count(db: SupportSQLiteDatabase, table: String): Long =
            db.query("SELECT count(*) FROM $table").use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
}
