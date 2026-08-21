package com.symmetricalpalmtree.notesprout.ext.heading

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.OutlineEntry

/**
 * The heading payload rules (arc 4 / H3 — pure Kotlin, JVM-tested): a heading's payload is its
 * markdown source, `"#" × level + " " + words`, level 1..6. The original Notesprout `HeadingObject`
 * helpers (`headingPrefix` / `stripHeadingPrefix` / `applyLevel`) widened from three levels to six.
 * The core never sees any of this — it stores the payload opaquely.
 */
object HeadingText {

    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 6

    private val PREFIX = Regex("^#{1,6}[ \\t]+")
    private val NEWLINES = Regex("[\\r\\n]+")
    private val SPACES = Regex("[ \\t]{2,}")
    private val BARE_PREFIX = Regex("^#{1,6}$")

    /** `"## "` for level 2; the level is clamped to 1..6. */
    fun prefix(level: Int): String = "#".repeat(level.coerceIn(MIN_LEVEL, MAX_LEVEL)) + " "

    /** The words without any leading `#`s (and the space after them), trimmed. */
    fun strip(payload: String): String = payload.trimStart().replaceFirst(PREFIX, "").trim()

    /** [text] (its own `#`s stripped, newlines folded) prefixed for [level]. */
    fun withLevel(text: String, level: Int): String = prefix(level) + strip(fold(text))

    /** The heading level a payload carries: the count of leading `#`s (1..6); malformed → 1. */
    fun levelOf(payload: String): Int {
        val m = PREFIX.find(payload.trimStart()) ?: return MIN_LEVEL
        return m.value.count { it == '#' }.coerceIn(MIN_LEVEL, MAX_LEVEL)
    }

    /** Recognized text → one line: newlines become spaces, runs of spaces collapse, trimmed. */
    fun fold(text: String): String = text.replace(NEWLINES, " ").replace(SPACES, " ").trim()

    /**
     * The heading's outline entry (arc 5 / C0): its words (stripped, folded, trimmed) cut to
     * `MAX_OUTLINE_LABEL_CHARS` at [levelOf] — or [OutlineEntry.NONE] when the words are blank
     * (a heading is never level 0 otherwise; a malformed payload is level 1, as [levelOf] says).
     */
    fun outlineOf(payload: String): OutlineEntry {
        val words = fold(strip(payload))
        if (words.isBlank() || BARE_PREFIX.matches(words)) return OutlineEntry.NONE   // "#" alone: a prefix with no words
        return OutlineEntry(words.take(ExtensionContract.MAX_OUTLINE_LABEL_CHARS), levelOf(payload))
    }
}
