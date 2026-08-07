package com.notesprout.android.notebook

import android.content.Context
import com.notesprout.android.core.InkColor

/**
 * The pen's ink colour, the palette it was chosen from, and the user's custom slots.
 *
 * **Global, one value for the whole app** — the same shape as [ToolPreferencesManager] and
 * [SnapPreferences], and for the same reason: all five drawing surfaces already share one active
 * tool, so they share one ink too. Not in `notesprout.db`, not in any `.soil`.
 */
object PenColorPreferences {

    private const val PREFS_NAME = "notesprout_pen_prefs"
    private const val KEY_COLOR = "pen_color"
    private const val KEY_PALETTE = "pen_palette"
    private const val KEY_SLOTS = "custom_slots"

    /** Empty slots are stored as this placeholder, so a slot's *position* is stable. */
    private const val EMPTY = "-"

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
     * Main-thread only, matching every caller. Hosts register in `onCreate`, unregister in
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

    /**
     * Which palette the panel opens on. **Greyscale is the default** — it is the set that renders
     * exactly on every panel in the fleet, so it is the safe thing to show someone who has not
     * expressed a preference. Once they switch, the choice sticks, like the colour itself.
     */
    fun loadPalette(context: Context): PenPalette.Kind =
        runCatching { PenPalette.Kind.valueOf(prefs(context).getString(KEY_PALETTE, "")!!) }
            .getOrDefault(PenPalette.Kind.GREYSCALE)

    fun savePalette(context: Context, kind: PenPalette.Kind) {
        prefs(context).edit().putString(KEY_PALETTE, kind.name).apply()
    }

    /**
     * The user's custom colours as **fixed, assignable slots** — always [PenPalette.CUSTOM_SLOTS]
     * long, with `null` for an empty one.
     *
     * Slots rather than a recents list on purpose: a recents list silently pushes out a colour you
     * rely on as soon as you experiment with a ninth. Here a colour stays exactly where you put it,
     * and position is itself information — the third slot is always the same ink.
     */
    fun loadSlots(context: Context): List<String?> {
        val stored = prefs(context).getString(KEY_SLOTS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?: emptyList()
        return List(PenPalette.CUSTOM_SLOTS) { i ->
            stored.getOrNull(i)?.takeIf { it.isNotEmpty() && it != EMPTY }
        }
    }

    /** Write [hex] (or `null` to clear) into [index], leaving every other slot untouched. */
    fun saveSlot(context: Context, index: Int, hex: String?) {
        if (index !in 0 until PenPalette.CUSTOM_SLOTS) return
        val updated = loadSlots(context).toMutableList().also { it[index] = hex }
        prefs(context).edit()
            .putString(KEY_SLOTS, updated.joinToString(",") { it ?: EMPTY })
            .apply()
    }

    /** The first empty slot, or null when all are taken. */
    fun firstEmptySlot(context: Context): Int? =
        loadSlots(context).indexOfFirst { it == null }.takeIf { it >= 0 }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
