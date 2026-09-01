package com.symmetricalpalmtree.notesproutsn.data.extstore

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * One extension's open store — `Garden/<pkg>.db` (arc 11 / J2; a thin `SupportSQLiteOpenHelper`
 * wrapper since arc 22 / X1, when Room left the store file: the extension's tables are unknown at
 * compile time, so Room's entity machinery bought nothing). Opened only by [ExtensionStores], over
 * the **same** `SoilCrypto` / `KeyOpener` factories every other SQLCipher open takes, so every open
 * is `NonDestructiveOpenHelperFactory`-wrapped — a wrong key reports corruption without deleting.
 *
 * Its own file and its own format version ([StoreFormat]): nothing here touches the global index or
 * any `.soil`, so neither one's version moves when this one does.
 */
class ExtensionStoreDatabase internal constructor(private val helper: SupportSQLiteOpenHelper) {

    /** The one connection (WAL, foreign keys ON, `busy_timeout` 5 s). Blocking; never Main. */
    fun writable(): SupportSQLiteDatabase = helper.writableDatabase

    /** A [StoreExecutor] over [writable] — what the gate and the host's own reads run on. */
    fun executor(): StoreExecutor = SupportStoreExecutor(writable())

    /** Whether the extension has a table called [name] (the host's one sanctioned peek — the
     *  `prefs` read of arc 19 / M9 checks before it selects, so it never mints anything). */
    fun hasTable(name: String): Boolean =
        writable().query("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.moveToFirst() && it.getInt(0) > 0 }

    fun close() = helper.close()
}
