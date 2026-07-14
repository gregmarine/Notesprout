package com.notesprout.android.state

import android.content.Context

data class AppViewState(
    val folderId: String?,
    val pinnedMode: Boolean,
    val recentsMode: Boolean = false,
    val searchMode: Boolean = false,
    val searchQuery: String = "",
)

/**
 * Browse state only — which screen the user was last *on* is [SurfaceStack]'s job (same prefs file,
 * separate keys, so these whole-state writes can't clobber it).
 */
object AppStateManager {

    private const val PREFS_NAME = "notesprout_view_state"
    private const val KEY_FOLDER_ID = "folder_id"
    private const val KEY_PINNED_MODE = "pinned_mode"
    private const val KEY_RECENTS_MODE = "recents_mode"
    private const val KEY_SEARCH_MODE = "search_mode"
    private const val KEY_SEARCH_QUERY = "search_query"

    fun load(context: Context): AppViewState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppViewState(
            folderId    = prefs.getString(KEY_FOLDER_ID, null),
            pinnedMode  = prefs.getBoolean(KEY_PINNED_MODE, false),
            recentsMode = prefs.getBoolean(KEY_RECENTS_MODE, false),
            searchMode  = prefs.getBoolean(KEY_SEARCH_MODE, false),
            searchQuery = prefs.getString(KEY_SEARCH_QUERY, "") ?: "",
        )
    }

    fun save(context: Context, state: AppViewState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_ID, state.folderId)
            .putBoolean(KEY_PINNED_MODE, state.pinnedMode)
            .putBoolean(KEY_RECENTS_MODE, state.recentsMode)
            .putBoolean(KEY_SEARCH_MODE, state.searchMode)
            .putString(KEY_SEARCH_QUERY, state.searchQuery)
            .apply()
    }
}
