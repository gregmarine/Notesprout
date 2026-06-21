package com.notesprout.android.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted per-notebook (and global) passphrase attempt counter.
 *
 * Keyed by notebook id for NOTEBOOK-scope notebooks; use [GLOBAL_KEY] for GLOBAL-scope prompts
 * so all global notebooks share one bucket. State survives process death (EncryptedSharedPreferences).
 *
 * NEVER store passphrase material here — only failure counts and lockout timestamps.
 */
object AttemptLimiter {

    const val GLOBAL_KEY = "GLOBAL"
    const val IMPORT_KEY = "IMPORT"

    private const val PREFS_FILE = "notesprout_secure"
    private const val PREFIX_FAILURES = "attempt_failures_"
    private const val PREFIX_LOCKOUT  = "attempt_lockout_"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Returns the epoch-ms timestamp when the lockout expires, or 0 if the user is allowed to try now.
     */
    fun check(context: Context, key: String): Long =
        prefs(context).getLong(PREFIX_LOCKOUT + key, 0L)

    /**
     * Record a failed attempt. Increments the failure counter and applies the escalating lockout schedule.
     *
     * Schedule: 1–2 failures → no delay; 3–4 → 30 s; 5–9 → 5 min; ≥10 → 1 hr.
     */
    fun recordFailure(context: Context, key: String) {
        val p = prefs(context)
        val failures = p.getInt(PREFIX_FAILURES + key, 0) + 1
        val delayMs = lockoutDelayMs(failures)
        val until = if (delayMs > 0L) System.currentTimeMillis() + delayMs else 0L
        p.edit()
            .putInt(PREFIX_FAILURES + key, failures)
            .putLong(PREFIX_LOCKOUT + key, until)
            .apply()
    }

    /**
     * Record a successful attempt — resets the failure counter and clears any lockout for [key].
     */
    fun recordSuccess(context: Context, key: String) {
        prefs(context).edit()
            .remove(PREFIX_FAILURES + key)
            .remove(PREFIX_LOCKOUT + key)
            .apply()
    }

    private fun lockoutDelayMs(failures: Int): Long = when {
        failures < 3  -> 0L
        failures < 5  -> 30_000L         // 30 s
        failures < 10 -> 300_000L        // 5 min
        else          -> 3_600_000L      // 1 hr
    }
}
