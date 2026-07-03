package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialized `type = "heading"` payload.
 *
 * [strokes] holds the original handwriting **only** for the ML-Kit-failed fallback
 * (`recognizedText == null`), where the strokes are the sole visual representation.
 * Once a heading is recognized ([recognizedText] non-null) it renders as canvas text and the
 * strokes are dropped — [strokes] is [emptyList] and, with `encodeDefaults = false`, omitted
 * from the JSON entirely. Headings are never revertible back to strokes (parity with text objects).
 */
@Serializable
data class HeadingObject(
    val strokes: List<LiveStroke> = emptyList(),
    val recognizedText: String? = null,
    val level: Int = 1,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): HeadingObject = Json.decodeFromString(serializer(), json)

        /** Returns the markdown prefix for [level] (clamped 1–3), e.g. `"## "` for level 2. */
        fun headingPrefix(level: Int): String = "#".repeat(level.coerceIn(1, 3)) + " "

        /**
         * Strips a leading run of 1–3 `#` characters followed by one or more spaces from [text].
         * Returns the bare text with no heading prefix.
         */
        fun stripHeadingPrefix(text: String): String =
            text.replaceFirst(Regex("^#{1,3}\\s+"), "")

        /**
         * Returns [text] with its heading prefix replaced by the prefix for [level].
         * Returns null when [text] is null (preserves null for stroke-only headings).
         */
        fun applyLevel(text: String?, level: Int): String? =
            text?.let { headingPrefix(level) + stripHeadingPrefix(it) }
    }
}
