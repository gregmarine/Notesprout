package com.notesprout.android.data

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps a [SupportSQLiteOpenHelper.Factory] so a "database is corrupt" event NEVER deletes the file.
 *
 * The framework/Room default corruption handler deletes and recreates a corrupt database. That is
 * catastrophic for a `.soil`: opening an *encrypted* notebook with the wrong key (or with no key at
 * all, i.e. as plaintext) makes SQLite see ciphertext as a corrupt database — and the default handler
 * then wipes the user's notebook and leaves an empty file behind. (This is exactly how a notebook was
 * lost in the link picker.)
 *
 * Here [SupportSQLiteOpenHelper.Callback.onCorruption] logs and throws instead of deleting, so a
 * mis-keyed open fails loudly and the file is left completely intact for recovery. Every other
 * callback is delegated unchanged to the wrapped (Room) callback.
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
                // DO NOT delete. A wrong-key / plaintext open of an encrypted .soil surfaces here;
                // deleting would destroy the notebook. Fail loudly and leave the file untouched.
                Log.e("SoilDatabase", "Corruption reported on open — refusing to delete, file left intact")
                throw SQLiteDatabaseCorruptException(
                    "SoilDatabase reported corruption on open; refusing to delete the file"
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
}
