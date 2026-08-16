package com.symmetricalpalmtree.notesprout.data.prefs

import android.content.Context

enum class SortField { NAME, MODIFIED }
enum class SortOrder { ASC, DESC }

class SortPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var field: SortField
        get() = prefs.getString(KEY_FIELD, null)
            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME
        set(value) { prefs.edit().putString(KEY_FIELD, value.name).apply() }

    var order: SortOrder
        get() = prefs.getString(KEY_ORDER, null)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ASC
        set(value) { prefs.edit().putString(KEY_ORDER, value.name).apply() }

    private companion object {
        const val FILE = "paper_sort"
        const val KEY_FIELD = "field"
        const val KEY_ORDER = "order"
    }
}
