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
data class RotationMarker(val pendingIds: List<String>, val newPassphrase: String)

object PassphraseStore {
    private const val PREFS_FILE = "notesprout_secure"
    private const val KEY_GLOBAL = "global_passphrase"
    private const val KEY_ROTATION_PENDING = "rotation_pending_ids"
    private const val KEY_ROTATION_NEW = "rotation_new_passphrase"

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

    // ── Rotation marker ───────────────────────────────────────────────────────
    // Stores the in-progress rotation state so a crash or cancel can be resumed.
    // The new passphrase is kept here (in EncryptedSharedPreferences) for the duration
    // of the rotation so a resume can complete without re-prompting.

    fun getRotationMarker(context: Context): RotationMarker? {
        val p = prefs(context)
        val pending = p.getString(KEY_ROTATION_PENDING, null) ?: return null
        val newPassphrase = p.getString(KEY_ROTATION_NEW, null) ?: return null
        val ids = pending.split(",").filter { it.isNotEmpty() }
        return RotationMarker(ids, newPassphrase)
    }

    fun setRotationMarker(context: Context, marker: RotationMarker) {
        prefs(context).edit()
            .putString(KEY_ROTATION_PENDING, marker.pendingIds.joinToString(","))
            .putString(KEY_ROTATION_NEW, marker.newPassphrase)
            .apply()
    }

    fun updateRotationPending(context: Context, remaining: List<String>) {
        prefs(context).edit()
            .putString(KEY_ROTATION_PENDING, remaining.joinToString(","))
            .apply()
    }

    fun clearRotationMarker(context: Context) {
        prefs(context).edit()
            .remove(KEY_ROTATION_PENDING)
            .remove(KEY_ROTATION_NEW)
            .apply()
    }
}
