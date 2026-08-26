package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

enum class SortField { NAME, MODIFIED }
enum class SortOrder { ASC, DESC }

/**
 * `SharedPreferences("sn_sort")` — how a card grid is ordered. Default Name ↑.
 *
 * Enum names only. Nothing in `data/prefs/` ever stores a display name: prefs are device-local
 * plaintext, and every name in this app lives in the encrypted index.
 *
 * The Templates screen (arc 13) keeps its **own** file rather than sharing the library's: they are
 * two different shelves of two different things, and re-sorting one must not silently re-sort the
 * other behind the user's back.
 */
class SortPrefs private constructor(context: Context, file: String) {

    constructor(context: Context) : this(context, FILE)

    private val prefs = context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    var field: SortField
        get() = prefs.getString(KEY_FIELD, null)
            ?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME
        set(value) { prefs.edit().putString(KEY_FIELD, value.name).apply() }

    var order: SortOrder
        get() = prefs.getString(KEY_ORDER, null)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ASC
        set(value) { prefs.edit().putString(KEY_ORDER, value.name).apply() }

    companion object {
        private const val FILE = "sn_sort"
        private const val TEMPLATES_FILE = "sn_sort_templates"
        private const val KEY_FIELD = "field"
        private const val KEY_ORDER = "order"

        /** The Templates screen's own sort (arc 13). */
        fun templates(context: Context): SortPrefs = SortPrefs(context, TEMPLATES_FILE)
    }
}
