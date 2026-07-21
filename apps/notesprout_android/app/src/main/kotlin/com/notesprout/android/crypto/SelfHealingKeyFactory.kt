package com.notesprout.android.crypto

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Falls back from a cached raw key to the passphrase when the raw key no longer fits the file.
 *
 * The raw key is **only a cache** — PBKDF2 over the passphrase and *that file's 16-byte salt*
 * ([RawKeyDerivation.deriveKey]). Anything that swaps the file behind a notebook id gives it a new
 * salt, which makes the cached key wrong even though the passphrase is perfectly correct:
 *
 *  - restoring a notebook from backup (in-app restore, or a file dropped in place),
 *  - a notebook re-encrypted by global conversion / rotation on another device,
 *  - any import that reuses an existing id.
 *
 * Before this, a stale cached key was terminal: [KeyOpener] took the raw-key path, SQLCipher failed,
 * and the user was locked out of a file they held the key to — with no way to even *try* the
 * passphrase. A cache miss must never be fatal. Here a failed raw-key open drops the cached key,
 * reopens with the passphrase (re-running the KDF against the file's real salt), and re-warms so the
 * next open is fast again.
 *
 * Only the *first* failure heals; if the passphrase open fails too, that is a genuine wrong-key or
 * damaged-file case and the exception propagates. Key material is never logged.
 */
class SelfHealingKeyFactory(
    private val context: Context,
    private val fileId: String,
    private val rawKeyFactory: SupportSQLiteOpenHelper.Factory,
    private val passphraseFactory: () -> SupportSQLiteOpenHelper.Factory,
    private val onHealed: () -> Unit = {},
) : SupportSQLiteOpenHelper.Factory {

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration,
    ): SupportSQLiteOpenHelper = object : SupportSQLiteOpenHelper {

        private var delegate: SupportSQLiteOpenHelper = rawKeyFactory.create(configuration)
        private var healed = false

        /** Run [get] against the raw-key helper; on first failure re-key to the passphrase helper. */
        private fun <T> withHealing(get: (SupportSQLiteOpenHelper) -> T): T =
            try {
                get(delegate)
            } catch (e: Exception) {
                if (healed) throw e
                healed = true
                Log.e(TAG, "raw-key open failed for $fileId — dropping cached key, retrying with passphrase")
                runCatching { delegate.close() }
                // The cached key was derived against a different salt; it can never open this file.
                KeyMaterial.invalidate(context, fileId)
                delegate = passphraseFactory().create(configuration)
                get(delegate).also { onHealed() }
            }

        override val databaseName: String?
            get() = delegate.databaseName

        override val writableDatabase: SupportSQLiteDatabase
            get() = withHealing { it.writableDatabase }

        override val readableDatabase: SupportSQLiteDatabase
            get() = withHealing { it.readableDatabase }

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            delegate.setWriteAheadLoggingEnabled(enabled)
        }

        override fun close() {
            delegate.close()
        }
    }

    private companion object {
        const val TAG = "SelfHealingKeyFactory"
    }
}
