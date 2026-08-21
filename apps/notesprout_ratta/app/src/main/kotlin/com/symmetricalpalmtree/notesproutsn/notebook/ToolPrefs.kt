package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.InkColorCodec

/**
 * `SharedPreferences("sn_tool")` — the armed pen and eraser, remembered app-wide.
 *
 * The first-ever defaults are the R3 phase decisions: **PEN · black · 3 px**, eraser **15 px**
 * (Paper-v0 parity). After that the panel is the only writer: whatever the user last picked is
 * what the next notebook opens with, in every notebook.
 *
 * Device-local, never format data — a tool choice is how *this* Supernote is set up, so it lives
 * in plaintext prefs and never touches the encrypted index or a `.soil`. Every read validates
 * against the set the panel actually offers, so a stale or hand-edited value can only ever cost
 * the user their preference, never arm the engine with something it can't draw.
 */
class ToolPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** New-stroke width in px. One of [WIDTHS]; anything else reads as [DEFAULT_WIDTH]. */
    var penWidthPx: Float
        get() = prefs.getFloat(KEY_PEN_WIDTH, DEFAULT_WIDTH).takeIf { it in WIDTHS } ?: DEFAULT_WIDTH
        set(value) { prefs.edit().putFloat(KEY_PEN_WIDTH, value).apply() }

    /** Abstract pen type, stored by enum name. An unknown name reads as [StrokeStyle.PEN]. */
    var penStyle: StrokeStyle
        get() = prefs.getString(KEY_PEN_STYLE, null)
            ?.let { runCatching { StrokeStyle.valueOf(it) }.getOrNull() } ?: StrokeStyle.PEN
        set(value) { prefs.edit().putString(KEY_PEN_STYLE, value.name).apply() }

    /** ARGB ink for new strokes. Default opaque black — the panel offers the greyscale ladder. */
    var penInk: Int
        get() = prefs.getInt(KEY_PEN_INK, InkColorCodec.BLACK)
        set(value) { prefs.edit().putInt(KEY_PEN_INK, value).apply() }

    /** Eraser hit radius in px. One of [ERASER_RADII]; anything else reads as [DEFAULT_ERASER]. */
    var eraserRadiusPx: Float
        get() = prefs.getFloat(KEY_ERASER, DEFAULT_ERASER).takeIf { it in ERASER_RADII } ?: DEFAULT_ERASER
        set(value) { prefs.edit().putFloat(KEY_ERASER, value).apply() }

    companion object {
        /** The five widths the pen panel offers, thinnest first. */
        val WIDTHS = listOf(1f, 2f, 3f, 5f, 8f)

        /** The four radii the eraser panel offers, smallest first. */
        val ERASER_RADII = listOf(8f, 15f, 30f, 60f)

        const val DEFAULT_WIDTH = 3f
        const val DEFAULT_ERASER = 15f

        private const val FILE = "sn_tool"
        private const val KEY_PEN_WIDTH = "penWidth"
        private const val KEY_PEN_STYLE = "penStyle"
        private const val KEY_PEN_INK = "penInk"
        private const val KEY_ERASER = "eraserRadius"
    }
}
