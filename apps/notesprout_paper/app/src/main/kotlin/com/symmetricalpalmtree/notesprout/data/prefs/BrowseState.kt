package com.symmetricalpalmtree.notesprout.data.prefs

import android.content.Context

/** Library browse modes. */
enum class BrowseMode { NORMAL, PINNED, RECENTS }

/**
 * `SharedPreferences("paper_view_state")` — where the library was (folder id + mode) and whether a
 * notebook was open when the app last died/closed. **Ids and enum names only — never names.**
 */
class BrowseState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last browse folder id; null = root. */
    var folderId: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) { prefs.edit().putString(KEY_FOLDER, value).apply() }

    var mode: BrowseMode
        get() = prefs.getString(KEY_MODE, null)?.let { runCatching { BrowseMode.valueOf(it) }.getOrNull() } ?: BrowseMode.NORMAL
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    /** Set in `NotebookActivity.onCreate`, cleared on normal close; read once on cold launch. */
    var lastOpenNotebookId: String?
        get() = prefs.getString(KEY_LAST_OPEN, null)
        set(value) { prefs.edit().putString(KEY_LAST_OPEN, value).apply() }

    private companion object {
        const val FILE = "paper_view_state"
        const val KEY_FOLDER = "folderId"
        const val KEY_MODE = "mode"
        const val KEY_LAST_OPEN = "lastOpenNotebookId"
    }
}
