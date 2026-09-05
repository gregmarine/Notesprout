package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context

/**
 * Keystore-backed cache for the GLOBAL passphrase (the recovery key) — plus the one-time
 * "recovery key acknowledged" flag.
 *
 * Device-local, never synced. The passphrase string is the SQLCipher key; the Keystore only
 * protects this local cache. NEVER log passphrase values.
 */
object PassphraseStore {
    internal const val PREFS_FILE = "sn_secure"
    private const val KEY_GLOBAL = "global_passphrase"
    private const val KEY_ACK = "recovery_key_acknowledged"
    private const val KEY_ROTATION = "rotation_marker"

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

    /** A minted rotation clears the acknowledgement so Bootstrap shows the NEW key once through
     *  `RecoveryKeyActivity` (arc 26 / U3, decision 1). */
    fun clearRecoveryKeyAcknowledged(context: Context) {
        prefs(context).edit().remove(KEY_ACK).apply()
    }

    // ── The rotation journal (arc 26 / U3) ───────────────────────────────────

    /** The in-progress rotation, or null. Same file as the cached global — the same posture. */
    fun getRotationMarker(context: Context): RotationMarker? =
        RotationMarker.decode(prefs(context).getString(KEY_ROTATION, null))

    /** `commit()`, not `apply()`: the journal must be on disk before the file it describes is touched. */
    fun setRotationMarker(context: Context, marker: RotationMarker) {
        prefs(context).edit().putString(KEY_ROTATION, RotationMarker.encode(marker)).commit()
    }

    fun clearRotationMarker(context: Context) {
        prefs(context).edit().remove(KEY_ROTATION).commit()
    }
}
