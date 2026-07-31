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

    /**
     * Live listeners, so a colour chosen on one surface reaches the others **while they are still
     * alive**.
     *
     * The value is global, but each host reads it once when it opens — and the drawing surfaces
     * overlap: the sticky-note editor and scratch pad are windows floating over a notebook or
     * calendar that is merely paused, not destroyed. Without this, picking a colour in the overlay
     * left the host behind it showing a stale pen tint until it was closed and reopened. The colour
     * was always persisted correctly; only the chrome lied.
     *
     * Main-thread only, matching every caller. Hosts register in `onCreate` and unregister in
     * `onDestroy`.
     */
    private val listeners = mutableSetOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) { listeners += listener }

    fun removeListener(listener: (String) -> Unit) { listeners -= listener }

    fun load(context: Context): String =
        prefs(context).getString(KEY_COLOR, null)?.takeIf { it.isNotBlank() } ?: InkColor.DEFAULT

    /** Persist [hex] and notify every live surface, including the one that chose it. */
    fun save(context: Context, hex: String) {
        prefs(context).edit().putString(KEY_COLOR, hex).apply()
        // Copy: a listener that unregisters itself mid-notify would otherwise mutate the set.
        listeners.toList().forEach { it(hex) }
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
