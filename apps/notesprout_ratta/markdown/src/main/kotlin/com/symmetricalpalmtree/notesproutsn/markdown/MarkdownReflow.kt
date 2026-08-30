package com.symmetricalpalmtree.notesproutsn.markdown

/**
 * Rejoins hand-wrapped lines into paragraphs.
 *
 * Handwriting recognition returns one line for every line that was written, which is the only
 * honest thing it can do — it saw ruled paper, not sentences. What comes back is prose broken at
 * every wrap. Reflow repairs that: a single break inside a block becomes a space, while the blank
 * lines the recognizer emits at real paragraph gaps stay the breaks they always were.
 *
 * It is deliberately timid. A break is only removed where it is *certainly* a wrap, because a
 * wrongly removed break destroys structure the writer put there, whereas a wrongly kept one is
 * just a break they can delete:
 *
 * - **Blank lines** separate paragraphs and survive; a run of them collapses to the one break it
 *   meant.
 * - **Headings** and **rules** stand alone — nothing joins onto them and they absorb nothing.
 * - **Table rows** (a line opening with `|`) stand alone too: their columns are positional, so
 *   joining one to the next would scramble the table.
 * - **List items** and **blockquotes** each start their own line but *do* absorb a following plain
 *   line, which is exactly the wrapped-item case.
 * - **Fenced code** passes through byte for byte, blank lines included — inside a fence a break is
 *   content, not layout.
 * - **Indented lines** (four spaces or a tab) stand alone, so an indented code block survives.
 * - A **hard break** — two or more trailing spaces, Markdown's explicit line break — is honoured,
 *   and the two spaces are written back out. Trimming them would silently delete the very thing
 *   this rule exists to protect.
 *
 * Leading whitespace is dropped everywhere it is not structure (an indented block, or the up-to-
 * three spaces that nest a list item). Recognition hands back a leading space on most lines, and
 * once the breaks around it are gone that space shows up as a dent at the start of a paragraph.
 *
 * The result is trimmed at the end and the function is idempotent: reflowing settled text returns
 * it unchanged, which is what lets a caller compare input to output and report "nothing to join".
 */
object MarkdownReflow {

    // Each is applied to one already-split line, so none of them can reach across a break. The
    // `{0,3}` leading-space tolerance is CommonMark's: a fourth space makes it indented code.
    private val HEADING = Regex("""^ {0,3}#{1,6}(\s|$)""")
    private val RULE = Regex("""^ {0,3}([-*_])(\s*\1){2,}\s*$""")
    private val BULLET = Regex("""^ {0,3}[-*+](\s|$)""")
    private val ORDERED = Regex("""^ {0,3}\d{1,9}[.)](\s|$)""")
    private val QUOTE = Regex("""^ {0,3}>""")
    private val FENCE = Regex("""^ {0,3}(```|~~~)""")

    /** [text] with its wrapped lines joined back into paragraphs. */
    fun reflow(text: String): String {
        val out = StringBuilder()
        // Whether the line last written is still able to take a plain line onto its end.
        var open = false
        var inFence = false
        var pendingBlank = false

        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd()

            if (inFence) {
                // Nothing inside a fence is reformatted — not the indentation, not the blank lines.
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
                // Remembered rather than written: a run of blanks becomes the single break it meant,
                // and a trailing run is dropped by the final trim.
                if (out.isNotEmpty()) pendingBlank = true
                open = false
                continue
            }

            val indented = rawLine.startsWith("\t") || rawLine.startsWith("    ")
            val hardBreak = rawLine.length - rawLine.trimEnd().length >= 2 && rawLine.isNotBlank()

            // A list marker's own leading spaces are nesting, so they are kept; on every other line
            // the leading space is recognizer noise and goes.
            val listItem = !indented && (BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line))
            val body = if (listItem) line else line.trimStart()

            val kind = when {
                indented -> Kind.STANDALONE
                HEADING.containsMatchIn(line) -> Kind.STANDALONE
                // A rule has to be the *whole* line — `--- and then some` is prose starting with a dash.
                RULE.matches(line) -> Kind.STANDALONE
                line.startsWith("|") -> Kind.STANDALONE
                BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line) -> Kind.OPENER
                QUOTE.containsMatchIn(line) -> Kind.OPENER
                else -> Kind.PLAIN
            }

            if (kind == Kind.PLAIN && open && !pendingBlank) {
                // The wrapped continuation: this is the break the whole pass exists to remove.
                out.append(' ').append(body)
            } else {
                startLine(out, pendingBlank)
                pendingBlank = false
                out.append(
                    when {
                        indented -> rawLine
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

    /** Open a fresh line, unless nothing has been written yet — no document starts with a break. */
    private fun startLine(out: StringBuilder, pendingBlank: Boolean) {
        if (out.isEmpty()) return
        out.append(if (pendingBlank) "\n\n" else "\n")
    }

    private enum class Kind {
        /** Joins to nothing in either direction: heading, rule, table row, indented line. */
        STANDALONE,

        /** Begins its own line but takes a following plain line onto it: list item, blockquote. */
        OPENER,

        /** Ordinary prose — the only kind that is ever joined onto something else. */
        PLAIN,
    }
}
