package com.symmetricalpalmtree.notesprout.crypto

import android.content.Context

/**
 * Keystore-backed cache for the GLOBAL passphrase (the recovery key) — plus the one-time
 * "recovery key acknowledged" flag.
 *
 * Device-local, never synced. The passphrase string is the SQLCipher key; the Keystore only protects
 * this local cache. NEVER log passphrase values.
 */
object PassphraseStore {
    internal const val PREFS_FILE = "paper_secure"
    private const val KEY_GLOBAL = "global_passphrase"
    private const val KEY_ACK = "recovery_key_acknowledged"

    private fun prefs(context: Context) = SecurePrefs.get(context, PREFS_FILE)

    fun getGlobalPassphrase(context: Context): String? =
        prefs(context).getString(KEY_GLOBAL, null)

    fun setGlobalPassphrase(context: Context, passphrase: String) {
        prefs(context).edit().putString(KEY_GLOBAL, passphrase).apply()
    }

    fun clearGlobalPassphrase(context: Context) {
        prefs(context).edit().remove(KEY_GLOBAL).apply()
    }

    /** True once the user has ticked "I've saved it" on the recovery-key screen. */
    fun isRecoveryKeyAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACK, false)

    fun setRecoveryKeyAcknowledged(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACK, true).apply()
    }
}
