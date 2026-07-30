package com.notesprout.android.core

import android.content.Context

/**
 * Reading and writing comfort for the document editor, remembered across sessions.
 *
 * Global rather than per-notebook: this is about the user's eyes and their device, not about one
 * document. Same shape as `SnapPreferences`.
 */
object DocumentPreferences {

    private const val PREFS_NAME = "notesprout_document_prefs"
    private const val KEY_TEXT_SIZE = "text_size_sp"

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
}
