package com.notesprout.android.core.markdown

/**
 * Joins wrapped lines back into paragraphs.
 *
 * Handwriting recognition emits **one line per line the user wrote**, because that is all it can
 * honestly know. The result is a document broken at every hand-wrapped line even though the writing is
 * prose. Reflow repairs that: single line breaks inside a block become spaces, and blank lines — which
 * the recognizer does emit at real paragraph gaps — are kept as the paragraph breaks they are.
 *
 * Deliberately conservative about what it will not touch. A break is only removed where it is
 * *certain* to be a wrap:
 *
 * - **Blank lines** separate paragraphs and survive (runs of them collapse to one).
 * - **Headings** (`#`) and **rules** (`---`) stand alone — nothing joins onto them, and they never
 *   absorb the line below.
 * - **List items**, **blockquotes**, and **table rows** each start their own line. Lists and quotes
 *   *do* absorb a following plain line, because that is exactly the wrapped-item case; a table row
 *   does not, since its columns are positional.
 * - **Fenced code** (```` ``` ````/`~~~`) passes through untouched — inside a fence every break is
 *   content, not formatting.
 * - **Indented lines** (4+ spaces or a tab) stand alone, so an indented code block survives.
 * - A **hard break** (a line ending in two or more spaces, Markdown's explicit line break) is honored.
 *
 * Leading whitespace is dropped except where it marks structure (an indented block, or a nested list
 * item). Recognition hands back a leading space on most lines, and once the breaks around it are gone
 * that space is a visible dent at the start of a paragraph.
 *
 * See docs/documents.md § "Reflow".
 */
object MarkdownReflow {

    private val HEADING = Regex("""^ {0,3}#{1,6}(\s|$)""")
    private val RULE = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
    private val BULLET = Regex("""^ {0,3}[-*+](\s|$)""")
    private val ORDERED = Regex("""^ {0,3}\d{1,9}[.)](\s|$)""")
    private val QUOTE = Regex("""^ {0,3}>""")
    private val FENCE = Regex("""^ {0,3}(```|~~~)""")

    /**
     * [text] with wrapped lines joined into paragraphs. Returns the input unchanged (bar trailing
     * whitespace) when there is nothing to join, so callers can tell a no-op from a change.
     */
    fun reflow(text: String): String {
        val out = StringBuilder()
        // Whether the last line written can still absorb a plain line onto its end.
        var open = false
        var inFence = false
        var pendingBlank = false

        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd()

            if (inFence) {
                // Inside a fence nothing is reformatted, not even blank lines.
                if (pendingBlank) { out.append('\n'); pendingBlank = false }
                if (out.isNotEmpty()) out.append('\n')
                out.append(rawLine)
                if (FENCE.containsMatchIn(line)) inFence = false
                open = false
                continue
            }

            if (FENCE.containsMatchIn(line)) {
                inFence = true
                startLine(out, pendingBlank); pendingBlank = false
                out.append(rawLine)
                open = false
                continue
            }

            if (line.isBlank()) {
                // Collapse any run of blank lines into the single break they mean.
                if (out.isNotEmpty()) pendingBlank = true
                open = false
                continue
            }

            val indented = rawLine.startsWith("\t") || rawLine.startsWith("    ")
            val hardBreak = rawLine.length - rawLine.trimEnd().length >= 2 && rawLine.isNotBlank()

            // Leading whitespace is only meaningful where it marks structure: an indented block (kept
            // verbatim below) or a nested list item, whose markers allow up to three spaces. Everywhere
            // else it is noise — recognition hands back a leading space on most lines — and once the
            // breaks around it are gone, it is a visible space at the start of a paragraph.
            val listItem = !indented && (BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line))
            val body = if (listItem) line else line.trimStart()

            val kind = when {
                indented -> Kind.STANDALONE
                HEADING.containsMatchIn(line) -> Kind.STANDALONE
                RULE.matches(line) -> Kind.STANDALONE
                line.startsWith("|") -> Kind.STANDALONE
                BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line) -> Kind.OPENER
                QUOTE.containsMatchIn(line) -> Kind.OPENER
                else -> Kind.PLAIN
            }

            if (kind == Kind.PLAIN && open && !pendingBlank) {
                // A wrapped continuation: this is the break we are here to remove.
                out.append(' ').append(body)
            } else {
                startLine(out, pendingBlank)
                pendingBlank = false
                out.append(
                    when {
                        indented -> rawLine
                        // The two trailing spaces *are* the hard break — trimming them would silently
                        // delete the very formatting this branch exists to honor.
                        hardBreak -> "$body  "
                        else -> body
                    }
                )
            }

            open = when {
                hardBreak -> false
                indented -> false
                kind == Kind.STANDALONE -> false
                else -> true
            }
        }

        return out.toString().trimEnd()
    }

    private fun startLine(out: StringBuilder, pendingBlank: Boolean) {
        if (out.isEmpty()) return
        out.append(if (pendingBlank) "\n\n" else "\n")
    }

    private enum class Kind {
        /** Stands alone: absorbs nothing and joins to nothing (heading, rule, table row, indented). */
        STANDALONE,

        /** Starts its own line but absorbs a following plain line (list item, blockquote). */
        OPENER,

        /** Ordinary prose. */
        PLAIN,
    }
}
