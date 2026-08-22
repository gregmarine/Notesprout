package com.symmetricalpalmtree.notesproutsn.core.markdown

/**
 * The heading row's `text` ↔ level contract, in one place — **never hardcode `"# "`.**
 *
 * A heading row stores hash-prefixed markdown (`"## Title"`), but the stored `flags` level is
 * authoritative: the prefix is only ever *written from* the level ([applyLevel]), never parsed to
 * discover it. [stripHeadingPrefix] is the display direction — the edit dialog shows the bare
 * title, and Save goes back through [applyLevel].
 *
 * Pure Kotlin — JVM-tested.
 */
object HeadingPrefix {

    private val PREFIX = Regex("^#{1,6} ")

    /** `"## "` for level 2. Levels outside 1..6 are clamped — `flags` comes from a stored column. */
    fun headingPrefix(level: Int): String = "#".repeat(level.coerceIn(1, 6)) + " "

    /** The bare title: the leading `#{1,6} ` removed. Text with no such prefix passes through. */
    fun stripHeadingPrefix(text: String): String = PREFIX.replaceFirst(text, "")

    /** [text] re-prefixed for [level] — any existing prefix is replaced, never stacked. */
    fun applyLevel(text: String, level: Int): String =
        headingPrefix(level) + stripHeadingPrefix(text)
}
