package com.symmetricalpalmtree.notesprout.crypto

import android.content.Context

/**
 * Persisted unlock-attempt counter with an escalating lockout. Survives process death
 * (EncryptedSharedPreferences). NEVER stores passphrase material — only counts and timestamps.
 *
 * Schedule (ported verbatim from Notesprout): failures 1–2 → no delay; 3–4 → 30 s; 5–9 → 5 min;
 * ≥ 10 → 1 h. Success resets.
 */
object AttemptLimiter {

    const val GLOBAL_KEY = "GLOBAL"

    private const val PREFIX_FAILURES = "attempt_failures_"
    private const val PREFIX_LOCKOUT = "attempt_lockout_"

    private fun prefs(context: Context) = SecurePrefs.get(context, PassphraseStore.PREFS_FILE)

    /** Epoch-ms when the lockout expires, or 0 if an attempt is allowed now. */
    fun check(context: Context, key: String = GLOBAL_KEY): Long =
        prefs(context).getLong(PREFIX_LOCKOUT + key, 0L)

    fun recordFailure(context: Context, key: String = GLOBAL_KEY) {
        val p = prefs(context)
        val failures = p.getInt(PREFIX_FAILURES + key, 0) + 1
        val delayMs = lockoutDelayMs(failures)
        val until = if (delayMs > 0L) System.currentTimeMillis() + delayMs else 0L
        p.edit()
            .putInt(PREFIX_FAILURES + key, failures)
            .putLong(PREFIX_LOCKOUT + key, until)
            .apply()
    }

    fun recordSuccess(context: Context, key: String = GLOBAL_KEY) {
        prefs(context).edit()
            .remove(PREFIX_FAILURES + key)
            .remove(PREFIX_LOCKOUT + key)
            .apply()
    }

    /** Pure schedule — unit-tested. */
    fun lockoutDelayMs(failures: Int): Long = when {
        failures < 3 -> 0L
        failures < 5 -> 30_000L
        failures < 10 -> 300_000L
        else -> 3_600_000L
    }
}
