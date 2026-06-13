package com.notesprout.android.state

import android.content.Context

data class AppViewState(val folderId: String?, val pinnedMode: Boolean)

object AppStateManager {

    private const val PREFS_NAME = "notesprout_view_state"
    private const val KEY_FOLDER_ID = "folder_id"
    private const val KEY_PINNED_MODE = "pinned_mode"

    fun load(context: Context): AppViewState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folderId = prefs.getString(KEY_FOLDER_ID, null)
        val pinnedMode = prefs.getBoolean(KEY_PINNED_MODE, false)
        return AppViewState(folderId, pinnedMode)
    }

    fun save(context: Context, state: AppViewState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_ID, state.folderId)
            .putBoolean(KEY_PINNED_MODE, state.pinnedMode)
            .apply()
    }
}
