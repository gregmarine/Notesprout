package com.notesprout.android.data.backup

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for the Drive OAuth refresh token.
 * Uses EncryptedSharedPreferences (AES-256-GCM); the master key lives in AndroidKeystore.
 * Never log or leak the stored value — treat it with the same hygiene as encryption passphrases.
 */
object DriveTokenStore {
    private const val PREFS_FILE = "drive_token_store"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun storeRefreshToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_REFRESH_TOKEN).apply()
    }
}
