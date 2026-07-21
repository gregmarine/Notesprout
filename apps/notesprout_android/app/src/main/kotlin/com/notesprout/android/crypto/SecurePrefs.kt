package com.notesprout.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Serialized, cached construction of the app's EncryptedSharedPreferences files.
 *
 * All three crypto stores ([PassphraseStore]/[AttemptLimiter] share one file, [DerivedKeyStore]
 * has its own) route through here because:
 *  - androidx.security keyset creation is not safe across concurrent instances of the same prefs
 *    file — two threads first-creating the same file's keyset is a known crash/corruption mode,
 *    and the stores are hit from independent threads (bootstrap, KeyOpener warm, unlock prompts).
 *    One lock + one cached instance per file removes the race entirely.
 *  - Keystore can throw transiently (notably right after boot, before the user unlocks). One
 *    retry absorbs that; a persistent failure still propagates for the caller to surface.
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
