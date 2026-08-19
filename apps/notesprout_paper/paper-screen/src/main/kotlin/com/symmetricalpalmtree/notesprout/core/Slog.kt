package com.symmetricalpalmtree.notesprout.core

import android.util.Log
import com.symmetricalpalmtree.notesprout.paperscreen.BuildConfig

object Slog {
    inline fun d(tag: String, msg: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg())
    }
}
