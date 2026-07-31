package com.notesprout.android.notebook

import android.content.Context
import com.notesprout.android.core.InkColor

/**
 * The pen's ink colour, persisted across notebook switches and app restarts.
 *
 * **Global, one value for the whole app** — the same shape as [ToolPreferencesManager] and
 * [SnapPreferences], and for the same reason: all five drawing surfaces (notebook, scratch pad,
 * calendar, day-detail note, sticky-note editor) already share one active tool, so they share one
 * ink too. Not in `notesprout.db`, not in any `.soil`.
 *
 * [recentCustom] holds colours the user mixed in the custom picker, newest first, capped at
 * [MAX_RECENT]. Palette colours never enter it — only mixed ones, since the palette is always one
 * tap away regardless.
 */
object PenColorPreferences {

    private const val PREFS_NAME = "notesprout_pen_prefs"
    private const val KEY_COLOR = "pen_color"
    private const val KEY_RECENT = "recent_custom"

    /** How many mixed colours the panel offers back. Small — this is a shortcut, not a library. */
    const val MAX_RECENT = 4

    fun load(context: Context): String =
        prefs(context).getString(KEY_COLOR, null)?.takeIf { it.isNotBlank() } ?: InkColor.DEFAULT

    fun save(context: Context, hex: String) {
        prefs(context).edit().putString(KEY_COLOR, hex).apply()
    }

    /** Mixed colours, newest first. */
    fun loadRecent(context: Context): List<String> =
        prefs(context).getString(KEY_RECENT, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** Promote [hex] to the front of the recents, de-duplicating and trimming to [MAX_RECENT]. */
    fun addRecent(context: Context, hex: String) {
        val updated = (listOf(hex) + loadRecent(context).filter { !it.equals(hex, ignoreCase = true) })
            .take(MAX_RECENT)
        prefs(context).edit().putString(KEY_RECENT, updated.joinToString(",")).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
