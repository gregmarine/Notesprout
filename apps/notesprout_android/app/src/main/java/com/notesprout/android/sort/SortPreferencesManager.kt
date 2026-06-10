package com.notesprout.android.sort

import android.content.Context

object SortPreferencesManager {

    private const val PREFS_NAME = "notesprout_sort_prefs"
    private const val KEY_FIELD = "sort_field"
    private const val KEY_ORDER = "sort_order"
    private const val KEY_FOLDER_SORT = "folder_sort"

    fun load(context: Context): SortPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val field = prefs.getString(KEY_FIELD, null)
            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() }
            ?: SortField.NAME
        val order = prefs.getString(KEY_ORDER, null)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
            ?: SortOrder.ASCENDING
        val folderSort = prefs.getString(KEY_FOLDER_SORT, null)
            ?.let { runCatching { FolderSort.valueOf(it) }.getOrNull() }
            ?: FolderSort.FOLDERS_FIRST
        return SortPreferences(field, order, folderSort)
    }

    fun save(context: Context, prefs: SortPreferences) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FIELD, prefs.field.name)
            .putString(KEY_ORDER, prefs.order.name)
            .putString(KEY_FOLDER_SORT, prefs.folderSort.name)
            .apply()
    }
}
