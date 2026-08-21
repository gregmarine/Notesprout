package com.symmetricalpalmtree.notesprout.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Serialized, cached construction of the app's EncryptedSharedPreferences files.
 *
 * [PassphraseStore] + [AttemptLimiter] share one file; [DerivedKeyStore] has its own. All go through
 * here because androidx.security keyset creation is not safe across concurrent first-creations of
 * the same file, and Keystore can throw transiently right after boot (one retry absorbs that).
 */
internal object SecurePrefs {

    private val cache = mutableMapOf<String, SharedPreferences>()

    fun get(context: Context, fileName: String): SharedPreferences = synchronized(cache) {
        cache.getOrPut(fileName) {
            try {
                create(context, fileName)
            } catch (e: Exception) {
                Log.w("SecurePrefs", "EncryptedSharedPreferences create failed for $fileName — retrying", e)
                Thread.sleep(150)
                create(context, fileName)
            }
        }
    }

    private fun create(context: Context, fileName: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
