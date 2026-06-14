package com.notesprout.android.notebook

import android.content.Context

object SnapPreferences {

    private const val PREFS_NAME = "notesprout_snap_prefs"
    private const val KEY_SNAP_ENABLED = "snap_enabled"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SNAP_ENABLED, false)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SNAP_ENABLED, enabled)
            .apply()
    }
}
