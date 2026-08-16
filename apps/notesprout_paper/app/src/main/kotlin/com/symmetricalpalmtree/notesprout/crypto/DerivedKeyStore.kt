package com.symmetricalpalmtree.notesprout.crypto

import android.content.Context
import android.util.Base64

/**
 * Device-local, Keystore-encrypted cache of per-file SQLCipher raw keys, keyed by file id
 * ([KeyMaterial.INDEX_FILE_ID] for the index, the notebook UUID for a `.soil`).
 *
 * Same security posture as [PassphraseStore]: Keystore-protected, device-local, never logged.
 */
object DerivedKeyStore {
    private const val PREFS_FILE = "paper_dkeys"

    private fun prefs(context: Context) = SecurePrefs.get(context, PREFS_FILE)

    fun get(context: Context, fileId: String): ByteArray? =
        prefs(context).getString(fileId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun put(context: Context, fileId: String, rawKey: ByteArray) {
        prefs(context).edit().putString(fileId, Base64.encodeToString(rawKey, Base64.NO_WRAP)).apply()
    }

    fun remove(context: Context, fileId: String) {
        prefs(context).edit().remove(fileId).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
