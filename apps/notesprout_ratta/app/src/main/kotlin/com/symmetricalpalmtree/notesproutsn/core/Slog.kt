package com.symmetricalpalmtree.notesproutsn.core

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.BuildConfig

/** Debug-gated logging — the lambda is never evaluated in release. `Log.e`/`Log.w` stay direct. */
object Slog {
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg())
    }
}
