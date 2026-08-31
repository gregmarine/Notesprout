package com.symmetricalpalmtree.notesproutsn.importing

/**
 * What an imported notebook (and a recreated folder) is **called** — pure, JVM-tested.
 *
 * A name from an incoming file is untrusted text, not a path: the library never puts a name on the
 * filesystem (files are `<uuid>.soil`), so it does not have to pass
 * [com.symmetricalpalmtree.notesproutsn.library.NameRules] — mangling `Field notes (2)` down to the
 * typed-name charset would rename the user's notebook for no benefit. What it *does* have to be is
 * a single line of bounded length: [clean] drops control characters (a newline in a card label is a
 * card that draws over its neighbour) and caps the length, and nothing else.
 */
object ImportNames {

    /** Long enough for any real title, short enough that one row can never take a card apart. */
    const val MAX_NAME_CHARS = 120

    /** The last-resort name: a file with no meta and a display name that cleans away to nothing. */
    const val FALLBACK = "Imported notebook"

    /** One line, bounded, trimmed. Empty in, empty out — the caller decides what empty means. */
    fun clean(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val flattened = buildString(raw.length) {
            for (ch in raw) append(if (ch.isISOControl()) ' ' else ch)
        }
        return flattened.trim().take(MAX_NAME_CHARS).trim()
    }

    /** The name a file with no `notebook_meta` imports under: the picked document's display name
     *  minus its extension (og's rule), cleaned. [FALLBACK] when there is nothing left. */
    fun fromDisplayName(displayName: String): String {
        val stem = displayName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return clean(stem).ifEmpty { FALLBACK }
    }

    /** A notebook name, from the manifest where there is one and the file name where there is not. */
    fun notebookName(metaName: String?, displayName: String): String =
        clean(metaName).ifEmpty { fromDisplayName(displayName) }

    /** A recreated folder's name. A blank one would be an unclickable card, so it gets a word. */
    fun folderName(metaName: String?): String = clean(metaName).ifEmpty { "Imported" }

    /**
     * The one thing that crosses the seam about the picked document: **its display name**, and only
     * as a display name. `ImportSpec` refuses a path separator or a NUL by construction (unmarshal
     * is validation), so both are dropped here rather than allowed to fail an import over a name
     * nothing on the far side even needs; [max] is the parcelable's own cap.
     */
    fun specDisplayName(displayName: String, max: Int): String {
        val leaf = displayName.substringAfterLast('/').substringAfterLast('\\')
        return clean(leaf).filter { it != '/' }.take(max).trim()
    }

    /**
     * **Keep both**: the first free name in the sequence `X Copy`, `X Copy 2`, `X Copy 3`… og
     * appends `" Copy"` and stops; the numbered tail is what keeps a *second* Keep-both from
     * colliding with the first one, which og's shape would.
     *
     * [isTaken] is the caller's database question, asked at most [MAX_TRIES] times; if every
     * candidate is taken the last one is returned anyway — a name collision in the index is a
     * cosmetic duplicate, and refusing the import over it would be the worse answer.
     */
    fun keepBothName(name: String, isTaken: (String) -> Boolean): String {
        val base = clean(name).ifEmpty { FALLBACK }
        var candidate = withSuffix(base, " Copy")
        var n = 2
        while (isTaken(candidate) && n <= MAX_TRIES) {
            candidate = withSuffix(base, " Copy $n")
            n++
        }
        return candidate
    }

    /**
     * The name something that is **always new** lands under (arc 19 / M8, the text import): the
     * name itself when no sibling has it, the first free `… Copy` when one does.
     *
     * A text import never asks the Replace / Keep-both question — it creates a new document, always
     * (og's rule) — so the dedupe is **silent**: there is nothing to decide, and a dialog offering
     * to replace a notebook the user did not mention would be inviting a mistake, not preventing
     * one. [isTaken] is the caller's sibling set, read once so the "already taken?" question and
     * the "which Copy is free?" question can never disagree.
     */
    fun freeName(name: String, isTaken: (String) -> Boolean): String {
        val base = clean(name).ifEmpty { FALLBACK }
        return if (!isTaken(base)) base else keepBothName(base, isTaken)
    }

    /** The suffix must survive the cap — it is the whole meaning of the name — so it is the base
     *  that gets shortened, from its end, never the " Copy". */
    private fun withSuffix(base: String, suffix: String): String {
        val room = MAX_NAME_CHARS - suffix.length
        if (room <= 0) return suffix.trim()
        return (if (base.length <= room) base else base.take(room).trimEnd()) + suffix
    }

    private const val MAX_TRIES = 50
}
