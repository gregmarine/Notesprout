package com.notesprout.android.core

import android.util.Log
import com.notesprout.android.BuildConfig

/**
 * Debug-only logging facade (M-4).
 *
 * In release builds (`BuildConfig.DEBUG == false`) [d] compiles to a no-op: because
 * the message is a lambda and the function is `inline`, the message string is never
 * built — important on the e-ink per-stroke render path — and nothing leaks to logcat
 * (page UUIDs / layer IDs / sizes). `isMinifyEnabled` is false, so R8 cannot strip
 * `Log` calls; this guard is the actual stripping mechanism.
 *
 * Errors and warnings deliberately keep calling [Log] directly at their call sites —
 * those must survive into release (see the raw-DB logging rule in CLAUDE.md).
 */
object Slog {
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg())
    }
}
