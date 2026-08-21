package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

enum class SortField { NAME, MODIFIED }
enum class SortOrder { ASC, DESC }

/**
 * `SharedPreferences("sn_sort")` — how the library is ordered. Default Name ↑.
 *
 * Enum names only. Nothing in `data/prefs/` ever stores a display name: prefs are device-local
 * plaintext, and every name in this app lives in the encrypted index.
 */
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
        const val FILE = "sn_sort"
        const val KEY_FIELD = "field"
        const val KEY_ORDER = "order"
    }
}
