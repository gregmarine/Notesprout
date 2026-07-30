package com.notesprout.android.core.markdown

/**
 * The Markdown editing operations behind the format bar and its keyboard shortcuts.
 *
 * Every operation is a plain text edit on the buffer. Because the editor's model is always raw
 * Markdown (see [MarkdownStyler]), a button press and a hand-typed `**` are indistinguishable
 * afterwards, and one code path serves both the WYSIWYG and the plain-Markdown mode.
 *
 * Each function returns the [Selection] the caller should install afterwards — the caret lands
 * where a writer would expect to keep typing, not wherever the edit happened to leave it.
 */
object MarkdownFormatter {

    /** Where the caret / selection sits after an operation. */
    data class Selection(val start: Int, val end: Int)

    /** Line-level Markdown constructs. [PARAGRAPH] is "no marker" — what toggling off produces. */
    enum class Block { PARAGRAPH, HEADING, QUOTE, BULLET, ORDERED, TASK }

    // ── Inline ────────────────────────────────────────────────────────────────

    /**
     * Wrap the selection in [marker] (`**`, `*`, `~~`, `` ` ``) — or unwrap it when it is already
     * wrapped, so a second press is "off". With no selection the word under the caret is used;
     * with no word either, an empty pair is inserted and the caret parked between the markers.
     */
    fun toggleInline(buf: TextBuffer, selStart: Int, selEnd: Int, marker: String): Selection {
        var s = minOf(selStart, selEnd)
        var t = maxOf(selStart, selEnd)
        if (s == t) {
            val word = wordAt(buf, s)
            s = word.first
            t = word.second
        }
        val n = marker.length

        // Markers sitting immediately outside the selection (the usual case after a first press).
        if (s - n >= 0 && t + n <= buf.length &&
            buf.substring(s - n, s) == marker && buf.substring(t, t + n) == marker
        ) {
            buf.delete(t, t + n)
            buf.delete(s - n, s)
            return Selection(s - n, t - n)
        }

        // Markers included in the selection (the user dragged across them).
        if (t - s >= 2 * n && buf.substring(s, s + n) == marker && buf.substring(t - n, t) == marker) {
            buf.delete(t - n, t)
            buf.delete(s, s + n)
            return Selection(s, t - 2 * n)
        }

        buf.insert(t, marker)
        buf.insert(s, marker)
        return Selection(s + n, t + n)
    }

    // ── Block ─────────────────────────────────────────────────────────────────

    /**
     * Apply [block] to every line the selection touches, replacing whatever marker those lines
     * already carry. When they *all* already carry exactly this block (same heading [level]), the
     * marker is removed instead — the button toggles.
     */
    fun toggleBlock(
        buf: TextBuffer,
        selStart: Int,
        selEnd: Int,
        block: Block,
        level: Int = 1,
    ): Selection {
        val lo = minOf(selStart, selEnd)
        val hi = maxOf(selStart, selEnd)
        val first = lineStart(buf, lo)
        val last = lineEnd(buf, hi)
        val lines = buf.substring(first, last).split("\n")

        val allMatch = lines.all { line ->
            val parts = parseLine(line)
            parts.block == block && (block != Block.HEADING || parts.level == level)
        }
        val target = if (allMatch) Block.PARAGRAPH else block

        val rebuilt = StringBuilder()
        var ordinal = 1
        var startDelta = 0
        var endDelta = 0
        var origin = first

        for ((index, line) in lines.withIndex()) {
            if (index > 0) rebuilt.append('\n')
            val parts = parseLine(line)
            val prefix = when (target) {
                Block.PARAGRAPH -> ""
                Block.HEADING -> "#".repeat(level.coerceIn(1, 6)) + " "
                Block.QUOTE -> "> "
                Block.BULLET -> "- "
                Block.ORDERED -> "${ordinal++}. "
                Block.TASK -> "- [ ] "
            }
            val replacement = parts.indent + prefix + parts.content
            rebuilt.append(replacement)

            // Shift the caret by the delta of its own line and of every line above it, so it keeps
            // its position within the *content* rather than drifting into the new marker.
            val delta = replacement.length - line.length
            if (lo >= origin) startDelta += delta
            if (hi >= origin) endDelta += delta
            origin += line.length + 1
        }

        buf.replace(first, last, rebuilt.toString())
        val limit = first + rebuilt.length
        return Selection(
            (lo + startDelta).coerceIn(first, limit),
            (hi + endDelta).coerceIn(first, limit),
        )
    }

    // ── Insertions ────────────────────────────────────────────────────────────

    /**
     * Turn the selection into a link, leaving the `url` placeholder selected so the next thing
     * typed replaces it. With no selection a whole `[text](url)` skeleton is inserted, with
     * `text` selected instead.
     */
    fun insertLink(buf: TextBuffer, selStart: Int, selEnd: Int): Selection {
        val s = minOf(selStart, selEnd)
        val t = maxOf(selStart, selEnd)
        if (t > s) {
            val label = buf.substring(s, t)
            buf.replace(s, t, "[$label](url)")
            val urlStart = s + label.length + 3   // "[" + label + "]("
            return Selection(urlStart, urlStart + 3)
        }
        buf.insert(s, "[text](url)")
        return Selection(s + 1, s + 5)
    }

    /** Insert a horizontal rule on its own line, reusing the current line when it is blank. */
    fun insertRule(buf: TextBuffer, selStart: Int, selEnd: Int): Selection {
        val pos = maxOf(selStart, selEnd)
        val start = lineStart(buf, pos)
        val end = lineEnd(buf, pos)
        if (buf.substring(start, end).isBlank()) {
            buf.replace(start, end, "---")
            val after = start + 3
            if (after == buf.length) buf.insert(after, "\n")
            val caret = (after + 1).coerceAtMost(buf.length)
            return Selection(caret, caret)
        }
        buf.insert(end, "\n---\n")
        val caret = (end + 5).coerceAtMost(buf.length)
        return Selection(caret, caret)
    }

    // ── Enter inside a list ───────────────────────────────────────────────────

    /** What a newline typed at the end of a list line should do to the list. */
    sealed interface ListEnter {
        /** Carry the series on: put [marker] at the start of the new line. */
        data class Continue(val marker: String) : ListEnter

        /** The item was empty: delete [length] characters — the marker itself — and end the series. */
        data class End(val length: Int) : ListEnter
    }

    /**
     * Decide what a list should do about a newline just typed between [before] and [after] (the halves
     * of the line the caret was on).
     *
     * - A list item with content carries on: the next bullet, the next number, or a fresh unchecked box.
     *   A checked item still yields an unchecked one — the next thing you write is not already done.
     * - An item with **nothing in it** ends the series and takes its own marker with it, which is what
     *   makes a second Enter a paragraph break rather than a stray dash. This is decided by the state of
     *   the line, not by how fast the two keys were pressed, so it cannot leave a rogue marker behind
     *   however slowly you type.
     * - Splitting an item mid-way carries on instead of ending, so the text that moved down keeps its
     *   place in the list rather than losing its marker.
     *
     * Returns null when [before] is not a list line at all, and the newline is left alone.
     */
    fun listEnter(before: String, after: String): ListEnter? {
        val parts = parseLine(before)
        val marker = when (parts.block) {
            Block.BULLET -> "${parts.bullet} "
            Block.TASK -> "${parts.bullet} [ ] "
            Block.ORDERED -> "${parts.ordinal + 1}. "
            else -> return null
        }
        if (parts.content.isBlank() && after.isBlank()) return ListEnter.End(before.length)
        return ListEnter.Continue(parts.indent + marker)
    }

    // ── Renumbering ordered lists ─────────────────────────────────────────────

    /** One marker rewrite: replace [length] characters at [at] with [marker]. */
    data class Renumber(val at: Int, val length: Int, val marker: String)

    /**
     * The marker rewrites that make the ordered lists in [text] read the way Markdown already renders
     * them — in ascending [Renumber.at] order.
     *
     * Markdown numbers a list itself: it takes the first item's number and counts from there, ignoring
     * whatever the later items claim. So inserting an item in the middle renders correctly while the
     * source says `1. 2. 2. 3.`, and the editor looks wrong about a document that is fine. This pass
     * makes the source agree.
     *
     * Because it writes exactly what Markdown would render, **it can never change the rendered output**
     * — which is what makes it safe to run automatically. A list that starts at 3 keeps starting at 3.
     *
     * Returns rewrites rather than new text so the caller can apply them as narrow edits, each confined
     * to a marker: the caret keeps its place in the content it was in, and an untouched list costs no
     * edit at all.
     *
     * Left alone: bullets and tasks (nothing to count), fenced code (its content is not Markdown), and
     * a deeply indented run with no list above it (that is an indented code block, not a nested list).
     * A run survives a single blank line, because a loose list is still one list, and ends at two.
     */
    fun renumberOrderedLists(text: CharSequence): List<Renumber> {
        val out = mutableListOf<Renumber>()
        // Indent width → the number the next item at that depth should carry.
        val runs = HashMap<Int, Int>()
        var blanks = 0
        var inFence = false
        var offset = 0

        for (line in text.toString().split('\n')) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    inFence = !inFence
                    runs.clear()
                }

                inFence -> Unit

                line.isBlank() -> {
                    blanks++
                    if (blanks >= 2) runs.clear()
                }

                else -> {
                    blanks = 0
                    val parts = parseLine(line)
                    val width = parts.indent.length
                    if (parts.block == Block.ORDERED && !(width >= 4 && runs.isEmpty())) {
                        val number = runs[width] ?: parts.ordinal
                        if (number != parts.ordinal) {
                            // Keep the line's own spacing after the dot; only the number is wrong.
                            val markerLength = line.length - parts.content.length
                            var digits = width
                            while (digits < line.length && line[digits].isDigit()) digits++
                            val spacing = line.substring((digits + 1).coerceAtMost(markerLength), markerLength)
                            out += Renumber(
                                at = offset,
                                length = markerLength,
                                marker = "${parts.indent}$number.$spacing",
                            )
                        }
                        runs[width] = number + 1
                        // A shallower item restarts anything nested under it.
                        runs.keys.removeAll { it > width }
                    } else {
                        // Anything else at this depth ends an ordered run there and below it, while a
                        // line indented *under* an item (a wrapped continuation) leaves it alone.
                        runs.keys.removeAll { it >= width }
                    }
                }
            }
            offset += line.length + 1
        }
        return out
    }

    // ── Line parsing ──────────────────────────────────────────────────────────

    private data class LineParts(
        val indent: String,
        val block: Block,
        val level: Int,
        val content: String,
        /** The bullet character this line actually used (`-`, `*`, `+`) — repeated when continuing. */
        val bullet: Char = '-',
        /** The number this ordered item actually carried — incremented when continuing. */
        val ordinal: Int = 0,
    )

    /** Split [line] into its leading indent, its block marker (if any), and the content after it. */
    private fun parseLine(line: String): LineParts {
        val indentLen = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        val indent = line.substring(0, indentLen)
        val rest = line.substring(indentLen)

        var hashes = 0
        while (hashes < 6 && hashes < rest.length && rest[hashes] == '#') hashes++
        if (hashes in 1..6 && hashes < rest.length && rest[hashes] == ' ') {
            return LineParts(indent, Block.HEADING, hashes, rest.substring(hashes).trimStart())
        }

        if (rest.startsWith(">")) {
            return LineParts(indent, Block.QUOTE, 0, rest.substring(1).trimStart())
        }

        if (rest.length >= 2 && (rest[0] == '-' || rest[0] == '*' || rest[0] == '+') && rest[1] == ' ') {
            var c = 2
            while (c < rest.length && rest[c] == ' ') c++
            if (c + 2 < rest.length && rest[c] == '[' && rest[c + 2] == ']' &&
                (rest[c + 1] == ' ' || rest[c + 1] == 'x' || rest[c + 1] == 'X')
            ) {
                return LineParts(indent, Block.TASK, 0, rest.substring(c + 3).trimStart(), bullet = rest[0])
            }
            return LineParts(indent, Block.BULLET, 0, rest.substring(c), bullet = rest[0])
        }

        var digits = 0
        while (digits < rest.length && rest[digits].isDigit()) digits++
        if (digits > 0 && digits + 1 < rest.length && rest[digits] == '.' && rest[digits + 1] == ' ') {
            return LineParts(
                indent, Block.ORDERED, 0, rest.substring(digits + 1).trimStart(),
                ordinal = rest.substring(0, digits).toIntOrNull() ?: 0,
            )
        }

        return LineParts(indent, Block.PARAGRAPH, 0, rest)
    }

    // ── Offsets ───────────────────────────────────────────────────────────────

    private fun lineStart(buf: TextBuffer, pos: Int): Int {
        var i = pos.coerceIn(0, buf.length)
        while (i > 0 && buf[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(buf: TextBuffer, pos: Int): Int {
        var i = pos.coerceIn(0, buf.length)
        while (i < buf.length && buf[i] != '\n') i++
        return i
    }

    /** The word surrounding [pos] — an empty range when the caret is not inside one. */
    private fun wordAt(buf: TextBuffer, pos: Int): Pair<Int, Int> {
        var s = pos.coerceIn(0, buf.length)
        var t = s
        while (s > 0 && isWordChar(buf[s - 1])) s--
        while (t < buf.length && isWordChar(buf[t])) t++
        return s to t
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\''
}
