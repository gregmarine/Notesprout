package com.notesprout.android.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Device-local, Keystore-encrypted cache of per-file SQLCipher raw keys.
 *
 * Stores the 32-byte key derived from the GLOBAL passphrase for each global-scope file (keyed by the
 * file's stable id — notebook id, or a fixed id for the index / training db). This is the persistence
 * that makes every open fast across sessions and cold launches without re-running the KDF.
 *
 * Security posture is equivalent to [PassphraseStore] caching the global passphrase: both are
 * Keystore-protected, device-local, never synced, never logged — and both unlock global content. A
 * NOTEBOOK-scope (private) file's key is **never** stored here; those re-derive on every open so the
 * key never rests on the device (the whole point of a notebook passphrase). Sibling to
 * [PassphraseStore]; cleared on "Forget on this device" and on global rotation.
 */
object DerivedKeyStore {
    private const val PREFS_FILE = "notesprout_dkeys"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(context: Context, fileId: String): ByteArray? =
        prefs(context).getString(fileId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun put(context: Context, fileId: String, rawKey: ByteArray) {
        prefs(context).edit().putString(fileId, Base64.encodeToString(rawKey, Base64.NO_WRAP)).apply()
    }

    fun remove(context: Context, fileId: String) {
        prefs(context).edit().remove(fileId).apply()
    }

    /** Drop every cached key — after global rotation (salts change) or "Forget on this device". */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
