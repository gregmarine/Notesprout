package com.notesprout.android.state

import android.content.Context

data class AppViewState(
    val folderId: String?,
    val pinnedMode: Boolean,
    val recentsMode: Boolean = false,
    val searchMode: Boolean = false,
    val searchQuery: String = "",
    val lastOpenedNotebookId: String? = null,
)

object AppStateManager {

    private const val PREFS_NAME = "notesprout_view_state"
    private const val KEY_FOLDER_ID = "folder_id"
    private const val KEY_PINNED_MODE = "pinned_mode"
    private const val KEY_RECENTS_MODE = "recents_mode"
    private const val KEY_SEARCH_MODE = "search_mode"
    private const val KEY_SEARCH_QUERY = "search_query"
    private const val KEY_LAST_NOTEBOOK_ID = "last_notebook_id"

    fun load(context: Context): AppViewState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppViewState(
            folderId             = prefs.getString(KEY_FOLDER_ID, null),
            pinnedMode           = prefs.getBoolean(KEY_PINNED_MODE, false),
            recentsMode          = prefs.getBoolean(KEY_RECENTS_MODE, false),
            searchMode           = prefs.getBoolean(KEY_SEARCH_MODE, false),
            searchQuery          = prefs.getString(KEY_SEARCH_QUERY, "") ?: "",
            lastOpenedNotebookId = prefs.getString(KEY_LAST_NOTEBOOK_ID, null),
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
            .putString(KEY_LAST_NOTEBOOK_ID, state.lastOpenedNotebookId)
            .apply()
    }
}
