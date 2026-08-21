package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

/**
 * Library browse modes. [PINNED] and [RECENTS] are declared now because [BrowseState.mode]
 * persists across the R5 release that wires them up — R2 only ever writes [NORMAL].
 */
enum class BrowseMode { NORMAL, PINNED, RECENTS }

/**
 * `SharedPreferences("sn_view_state")` — where the library was when the user left it, so a cold
 * launch reopens the same shelf instead of dumping them at the root.
 *
 * **Ids and enum names only, never a display name.** [folderId] is validated against the index on
 * restore (a folder deleted since falls back to root); nothing here is trusted as still existing.
 */
class BrowseState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Last browse folder id; null = root. */
    var folderId: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) { prefs.edit().putString(KEY_FOLDER, value).apply() }

    var mode: BrowseMode
        get() = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { BrowseMode.valueOf(it) }.getOrNull() } ?: BrowseMode.NORMAL
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    /**
     * Reserved for R3: the notebook that was open when the app last died, so a cold launch can put
     * it back on top of the library. R2 neither writes nor reads it — the slot exists so the pref
     * file's shape does not change under a user when R3 lands.
     */
    var lastOpenNotebookId: String?
        get() = prefs.getString(KEY_LAST_OPEN, null)
        set(value) { prefs.edit().putString(KEY_LAST_OPEN, value).apply() }

    private companion object {
        const val FILE = "sn_view_state"
        const val KEY_FOLDER = "folderId"
        const val KEY_MODE = "mode"
        const val KEY_LAST_OPEN = "lastOpenNotebookId"
    }
}
