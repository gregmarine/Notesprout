package com.symmetricalpalmtree.notesprout.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps a [SupportSQLiteOpenHelper.Factory] so a "database is corrupt" event NEVER deletes the file.
 *
 * Room's default corruption handler deletes and recreates a corrupt database. For an encrypted
 * `.soil` (or the index) that is catastrophic: opening with the wrong key makes SQLite see
 * ciphertext as a corrupt database, and the default handler would then wipe the user's notebook and
 * leave an empty file behind. Notesprout lost a notebook exactly this way.
 *
 * Here [SupportSQLiteOpenHelper.Callback.onCorruption] logs and throws instead of deleting, so a
 * mis-keyed open fails loudly and the file is left completely intact. Every other callback is
 * delegated unchanged. **Every Room open in Paper goes through this wrapper** — see
 * [com.symmetricalpalmtree.notesprout.crypto.SoilCrypto].
 */
class NonDestructiveOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val inner = configuration.callback
        val safeCallback = object : SupportSQLiteOpenHelper.Callback(inner.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                inner.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                inner.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // DO NOT delete. A wrong-key open of an encrypted file surfaces here; deleting would
                // destroy the user's data. Fail loudly and leave the file untouched.
                Log.e(TAG, "Corruption reported on open — refusing to delete, file left intact")
                throw SQLiteDatabaseCorruptException(
                    "Database reported corruption on open; refusing to delete the file"
                )
            }
        }
        val safeConfig = SupportSQLiteOpenHelper.Configuration
            .builder(configuration.context)
            .name(configuration.name)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .callback(safeCallback)
            .build()
        return delegate.create(safeConfig)
    }

    private companion object {
        const val TAG = "NonDestructiveOpen"
    }
}
