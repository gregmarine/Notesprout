package com.notesprout.android.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed cache for the GLOBAL passphrase only.
 *
 * This is device-local and intentionally does NOT sync across devices. A notebook encrypted with
 * the global passphrase on device A still opens on device B — the user is prompted once to enter
 * the passphrase, which is then cached on that device too. The portability guarantee comes from
 * the passphrase string being the SQLCipher key; Keystore only protects the local cache.
 *
 * NEVER cache a notebook-specific passphrase here. NEVER log passphrase values.
 */
object PassphraseStore {
    private const val PREFS_FILE = "notesprout_secure"
    private const val KEY_GLOBAL = "global_passphrase"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun hasGlobalPassphrase(context: Context): Boolean =
        prefs(context).contains(KEY_GLOBAL)

    fun getGlobalPassphrase(context: Context): String? =
        prefs(context).getString(KEY_GLOBAL, null)

    fun setGlobalPassphrase(context: Context, passphrase: String) {
        prefs(context).edit().putString(KEY_GLOBAL, passphrase).apply()
    }

    fun clearGlobalPassphrase(context: Context) {
        prefs(context).edit().remove(KEY_GLOBAL).apply()
    }
}
