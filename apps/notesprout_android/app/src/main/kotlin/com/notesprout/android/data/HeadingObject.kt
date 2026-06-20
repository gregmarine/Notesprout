package com.notesprout.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HeadingObject(
    val strokes: List<LiveStroke>,
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
