package com.symmetricalpalmtree.notesprout.data.extstore

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.crypto.KeyOpener
import com.symmetricalpalmtree.notesprout.crypto.KeySession
import com.symmetricalpalmtree.notesprout.crypto.SoilCrypto
import com.symmetricalpalmtree.notesprout.crypto.SoilLockedException
import com.symmetricalpalmtree.notesprout.data.extensionStoreFile
import java.io.File

/**
 * Open-or-create the host-owned encrypted store of one extension package — `Garden/<pkg>.db`,
 * SQLCipher under the **global** passphrase (`KeySession`, set once the index is open; every caller
 * is behind `IndexGuard`). Raw-key cache file id = [fileIdFor] (`ext:<pkg>`, so it can never collide
 * with a notebook UUID or the index id).
 *
 * This is the **third named create entry point** (after `PaperIndex.ensureReady`'s `Invalid` branch
 * and `SoilDatabase.create`): a missing / empty file is created byte-for-byte the way
 * `SoilDatabase.create` creates a `.soil`; an existing file is opened the `SoilDatabase.open` way
 * (`requireExisting` → cached raw key verified against the file, passphrase fallback + warm). Every
 * factory comes from `SoilCrypto`, so every open is `NonDestructiveOpenHelperFactory`-wrapped.
 *
 * Process-lifetime cache: one open DB per package, never closed except [closeAll] (tests / debug).
 * The `.db` outlives the extension — uninstall / disable leave it in place (removing an extension's
 * data is Extensions-UI territory).
 *
 * **Pre-open rule:** callers open the store on IO **before** binding the extension, so a cold open
 * (KDF ≈ 0.5–1.5 s on e-ink when the raw key isn't cached) is never inside the call's timeout window.
 * Blocking; IO thread only.
 */
object ExtensionStores {

    private const val TAG = "ExtensionStores"

    private val cache = HashMap<String, ExtensionStoreDatabase>()

    /** Raw-key cache id for [pkg]'s store. */
    fun fileIdFor(pkg: String): String = "ext:$pkg"

    /** The open store for [pkg] (created if absent). Throws [SoilLockedException] when no global key is in session. IO. */
    @Synchronized
    fun open(context: Context, pkg: String): ExtensionStoreDatabase {
        cache[pkg]?.let { return it }
        val app = context.applicationContext
        val file = extensionStoreFile(app, pkg)
        val pass = KeySession.get() ?: throw SoilLockedException("no global key in session")
        val fileId = fileIdFor(pkg)
        val db = if (!file.exists() || file.length() == 0L) {
            // Create — the SoilDatabase.create pattern.
            require(!file.exists() || file.length() == 0L) { "refusing to create over an existing file: ${file.name}" }
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
