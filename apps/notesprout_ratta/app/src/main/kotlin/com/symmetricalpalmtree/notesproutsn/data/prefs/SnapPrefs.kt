package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

/**
 * `SharedPreferences("sn_snap")` — whether a lasso drag snaps to guides. Default **off**, so a
 * drag is the pen's own path until the user asks for otherwise.
 *
 * One flag, deliberately global rather than per-notebook or per-session: snapping is a way of
 * working, not a property of a page. Turn it on once and every later selection, page, notebook and
 * relaunch honours it — on e-ink, where a process kill is routine, a session-only memory would
 * read as the setting forgetting itself.
 */
class SnapPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    private companion object {
        const val FILE = "sn_snap"
        const val KEY_ENABLED = "enabled"
    }
}
