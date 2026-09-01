package com.symmetricalpalmtree.notesproutsn.data.extstore

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
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
     *  is in session. IO. */
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
            build(app, file, SoilCrypto.roomFactory(pass)).also {
                forceOpen(it) // creates file + schema (one native KDF)
                KeyOpener.warm(app, fileId, file, pass)
                Slog.d(TAG) { "created store for $pkg" }
            }
        } else {
            // Open — the SoilDatabase.open pattern.
            SoilCrypto.requireExisting(file)
            build(app, file, KeyOpener.roomFactoryFor(app, fileId, file, pass)).also {
                forceOpen(it)
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
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
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

    private fun build(context: Context, file: File, factory: SupportSQLiteOpenHelper.Factory): ExtensionStoreDatabase =
        Room.databaseBuilder(context.applicationContext, ExtensionStoreDatabase::class.java, file.absolutePath)
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
    private fun forceOpen(db: ExtensionStoreDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
    }
}
