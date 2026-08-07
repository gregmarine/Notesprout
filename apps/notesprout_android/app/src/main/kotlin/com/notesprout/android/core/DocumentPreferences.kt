package com.notesprout.android.core

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Reading and writing comfort for the document editor, remembered across sessions.
 *
 * Global rather than per-notebook: this is about the user's eyes and their device, not about one
 * document. Same shape as `SnapPreferences`.
 */
object DocumentPreferences {

    private const val PREFS_NAME = "notesprout_document_prefs"
    private const val KEY_TEXT_SIZE = "text_size_sp"
    private const val KEY_CARETS = "caret_offsets"

    /**
     * How many pages' caret positions to remember. Old entries fall off the front, so the store cannot
     * grow without bound in a notebook of a thousand pages.
     */
    private const val CARET_LIMIT = 100

    /** Editing-surface text size in sp. The Preview surface renders [PREVIEW_BUMP] larger. */
    const val DEFAULT_TEXT_SIZE = 16f

    /**
     * Preview reads a little larger than the source it came from: source is monospace Markdown where
     * columns carry meaning, preview is prose meant to be read.
     */
    const val PREVIEW_BUMP = 2f

    /** The offered sizes, smallest first. Labels are what the picker shows. */
    val SIZES: List<Pair<String, Float>> = listOf(
        "Small" to 14f,
        "Medium" to 16f,
        "Large" to 18f,
        "Larger" to 21f,
        "Largest" to 25f,
    )

    fun textSize(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)
            // A value from a future build with a wider range must not render the editor unusable.
            .coerceIn(SIZES.first().second, SIZES.last().second)

    fun saveTextSize(context: Context, sizeSp: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TEXT_SIZE, sizeSp)
            .apply()
    }

    // ── Where the writer left off ─────────────────────────────────────────────
    // Kept here rather than in the `.soil` on purpose: where a caret sits is this device's view state,
    // not part of the document, and it would otherwise need a column in a format written to be handed
    // to other projects. The cost is that it does not travel with an exported notebook, which is the
    // right trade for something the next keystroke overwrites anyway.

    /** The caret offset last seen on [pageId]'s document, or 0 — the top — if we have never seen it. */
    fun caret(context: Context, pageId: String): Int = carets(context)[pageId] ?: 0

    /** Remember where the caret was on [pageId], evicting the oldest page once past [CARET_LIMIT]. */
    fun saveCaret(context: Context, pageId: String, offset: Int) {
        if (pageId.isEmpty()) return
        val map = carets(context)
        // Remove first so a re-saved page moves to the back: eviction is then least-recently-written.
        map.remove(pageId)
        map[pageId] = offset.coerceAtLeast(0)
        while (map.size > CARET_LIMIT) map.remove(map.keys.first())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CARETS, codec.encodeToString(MapSerializer(String.serializer(), Int.serializer()), map))
            .apply()
    }

    private fun carets(context: Context): LinkedHashMap<String, Int> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CARETS, null) ?: return LinkedHashMap()
        // A store this disposable must never be the reason a screen fails to open.
        return runCatching {
            LinkedHashMap(codec.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), raw))
        }.getOrElse { LinkedHashMap() }
    }

    private val codec = Json { ignoreUnknownKeys = true }
}
