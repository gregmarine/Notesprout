package com.symmetricalpalmtree.notesproutsn.markdown

/**
 * Every edit the document editor's format bar (and its keyboard shortcuts) performs.
 *
 * The editor's model is always the raw Markdown source, so a button press writes exactly the
 * characters a writer would have typed by hand. Nothing here is a "rich text" state change: press
 * bold and the buffer gains two `**`, which is indistinguishable afterwards from a `**` typed at
 * the keyboard. That is what keeps one code path serving the plain source and the rendered view.
 *
 * Every operation returns the [Selection] to install afterwards. The caret is not left wherever
 * the splice happened to put it — it is placed where writing continues, which for a wrap is
 * around the same words as before, and for a skeleton insertion is on the placeholder about to be
 * overtyped.
 *
 * Pure Kotlin: it edits through [TextBuffer], never `android.text`.
 */
object MarkdownFormatter {

    /** The caret (start == end) or selected range to install once an operation returns. */
    data class Selection(val start: Int, val end: Int)

    /**
     * The line-level constructs the bar can apply. [PARAGRAPH] is the absence of a marker — the
     * state a toggle-off lands in, never something the bar applies directly.
     *
     * NOTE: this nested enum deliberately shadows the file-level `Block` sealed class of
     * [MarkdownParser] inside this object. Both belong to this package and both are named `Block`
     * because both are right for their own job — one is a parse result, this one is a marker the
     * user asks for. Inside `MarkdownFormatter` an unqualified `Block` is *this* enum; from
     * outside, always spell it `MarkdownFormatter.Block`.
     */
    enum class Block { PARAGRAPH, HEADING, QUOTE, BULLET, ORDERED, TASK }

    // ── Inline markers ────────────────────────────────────────────────────────

    /**
     * Wrap the selection in [marker] (`**`, `*`, `~~`, `` ` ``), or strip it when the selection is
     * already wrapped — one button, pressed twice, is off.
     *
     * Two shapes of "already wrapped" have to be recognised, because both are what the editor
     * actually holds: the markers sitting just *outside* the selection (what the first press left
     * behind, caret still on the words) and the markers *inside* it (the user dragged across
     * them). Neither, and the selection is wrapped fresh.
     *
     * An empty selection takes the word under the caret. No word there either — the caret is in
     * whitespace — and an empty pair is written with the caret parked between the halves, ready to
     * be typed into.
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

        // Markers immediately outside: strip the trailing one first so the leading offsets stay put.
        if (s - n >= 0 && t + n <= buf.length &&
            buf.substring(s - n, s) == marker && buf.substring(t, t + n) == marker
        ) {
            buf.delete(t, t + n)
            buf.delete(s - n, s)
            return Selection(s - n, t - n)
        }

        // Markers swallowed by the selection. The length guard is what stops a selection of exactly
        // one marker (`**`) reading as both halves of a wrap and deleting itself twice.
        if (t - s >= 2 * n && buf.substring(s, s + n) == marker && buf.substring(t - n, t) == marker) {
            buf.delete(t - n, t)
            buf.delete(s, s + n)
            return Selection(s, t - 2 * n)
        }

        buf.insert(t, marker)
        buf.insert(s, marker)
        return Selection(s + n, t + n)
    }

    // ── Line markers ──────────────────────────────────────────────────────────

    /**
     * Give every line the selection touches the marker for [block], replacing whatever marker those
     * lines already carry — switching a bullet list to a numbered one is one press, not two.
     *
     * When *all* of those lines already carry precisely this block (and, for a heading, precisely
     * this [level]), the markers come off instead. "All" is the right bar: with a mixed selection
     * the writer is asking to make it uniform, not to clear it.
     *
     * A blank line inside the selection is a separator, not an item: it takes no marker, consumes
     * no ordinal, and does not count towards "all lines already carry this". A selection that is
     * *nothing but* blank is the other case — the empty line a list is being started on — and there
     * the marker is written.
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

        // Separator blanks are skipped, but only while there is something else to mark — an
        // all-blank selection is the caret on an empty line, which is where a list gets started.
        val skipBlanks = lines.any { it.isNotBlank() }
        val marked = if (skipBlanks) lines.filter { it.isNotBlank() } else lines

        val allMatch = marked.all { line ->
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
            if (skipBlanks && line.isBlank()) {
                // Kept verbatim: a marker here would mint an empty item, and its length is unchanged
                // so neither caret moves across it.
                rebuilt.append(line)
                origin += line.length + 1
                continue
            }
            val parts = parseLine(line)
            val prefix = when (target) {
                Block.PARAGRAPH -> ""
                Block.HEADING -> "#".repeat(level.coerceIn(1, 6)) + " "
                Block.QUOTE -> "> "
                Block.BULLET -> "- "
                // A numbered selection is numbered from one, in reading order — the marker is
                // decided by position in the selection, not by whatever the line used to claim.
                Block.ORDERED -> "${ordinal++}. "
                Block.TASK -> "- [ ] "
            }
            val replacement = parts.indent + prefix + parts.content
            rebuilt.append(replacement)

            // Carry each caret by the length change of its own line plus every line above it, so it
            // stays with the words it was next to instead of sliding into the new marker.
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

    // ── Skeleton insertions ───────────────────────────────────────────────────

    /**
     * Make the selection the label of a link and leave the `url` placeholder selected, so the
     * address can be typed straight over it. With nothing selected the whole `[text](url)`
     * skeleton is written and `text` is selected instead — the part still to be decided.
     */
    fun insertLink(buf: TextBuffer, selStart: Int, selEnd: Int): Selection {
        val s = minOf(selStart, selEnd)
        val t = maxOf(selStart, selEnd)
        if (t > s) {
            val label = buf.substring(s, t)
            buf.replace(s, t, "[$label](url)")
            val urlStart = s + label.length + 3   // past "[", the label, and "]("
            return Selection(urlStart, urlStart + 3)
        }
        buf.insert(s, "[text](url)")
        return Selection(s + 1, s + 5)
    }

    /**
     * The image mirror of [insertLink]: the selection becomes the alt text with `url` selected, or
     * a whole `![description](url)` skeleton lands with `description` selected.
     *
     * No picker sits behind this. It writes the reference and leaves the address to the writer,
     * which is the whole of the image story in this editor.
     */
    fun insertImage(buf: TextBuffer, selStart: Int, selEnd: Int): Selection {
        val s = minOf(selStart, selEnd)
        val t = maxOf(selStart, selEnd)
        if (t > s) {
            val alt = buf.substring(s, t)
            buf.replace(s, t, "![$alt](url)")
            val urlStart = s + alt.length + 4   // past "![", the alt text, and "]("
            return Selection(urlStart, urlStart + 3)
        }
        buf.insert(s, "![description](url)")
        return Selection(s + 2, s + 13)
    }

    /**
     * Put a horizontal rule on a line of its own. A blank current line is reused rather than
     * pushed down — pressing rule on the empty line you just made should not leave that line
     * stranded above the rule.
     */
    fun insertRule(buf: TextBuffer, selStart: Int, selEnd: Int): Selection {
        val pos = maxOf(selStart, selEnd)
        val start = lineStart(buf, pos)
        val end = lineEnd(buf, pos)
        if (buf.substring(start, end).isBlank()) {
            buf.replace(start, end, "---")
            val after = start + 3
            // At the very end of the buffer there is no line to move down to; make one.
            if (after == buf.length) buf.insert(after, "\n")
            val caret = (after + 1).coerceAtMost(buf.length)
            return Selection(caret, caret)
        }
        buf.insert(end, "\n---\n")
        val caret = (end + 5).coerceAtMost(buf.length)
        return Selection(caret, caret)
    }

    // ── Enter inside a list ───────────────────────────────────────────────────

    /** What a list should do about a newline typed at the caret. */
    sealed interface ListEnter {
        /** Keep the series going: the new line opens with [marker]. */
        data class Continue(val marker: String) : ListEnter

        /** The item was empty: delete [length] characters (its marker) and stop the series. */
        data class End(val length: Int) : ListEnter
    }

    /**
     * Decide what a newly typed newline should do to the list, given the two halves of the line the
     * caret was on — [before] it and [after] it.
     *
     * - An item with content continues: the same bullet character, the next number, or a fresh
     *   empty checkbox. A ticked item yields an *unticked* one, because the thing about to be
     *   written has not been done yet.
     * - An item with nothing in it, and nothing after the caret, ends the series and takes its own
     *   marker away with it. That is what makes the second Enter a plain paragraph break rather
     *   than a dangling dash.
     * - Splitting an item part-way through continues instead of ending, so the text pushed to the
     *   new line keeps its place in the list — this is why [after] is consulted at all.
     *
     * The decision reads only the state of the line, never how quickly the two Enters were struck.
     * A timing rule would leave a rogue marker behind for anyone who types slowly.
     *
     * Returns null when [before] is not a list line, and the newline is nobody's business.
     */
    fun listEnter(before: String, after: String): ListEnter? {
        val parts = parseLine(before)
        val marker = when (parts.block) {
            Block.BULLET -> "${parts.bullet} "
            Block.TASK -> "${parts.bullet} [ ] "
            Block.ORDERED -> "${parts.ordinal + 1}. "
            else -> return null
        }
        // The indent goes with the marker on the way out too, or the paragraph after a nested list
        // would start indented.
        if (parts.content.isBlank() && after.isBlank()) return ListEnter.End(before.length)
        return ListEnter.Continue(parts.indent + marker)
    }

    // ── Renumbering ordered lists ─────────────────────────────────────────────

    /** One marker rewrite: the [length] characters at [at] become [marker]. */
    data class Renumber(val at: Int, val length: Int, val marker: String)

    /**
     * The marker rewrites that would make the ordered lists in [text] say what Markdown already
     * renders them as, in ascending [Renumber.at] order.
     *
     * Markdown counts a list itself: it reads the first item's number and counts on, ignoring what
     * every later item claims. So a document whose source says `1. 2. 2. 3.` renders perfectly and
     * only the editor looks wrong about it. This pass settles the source.
     *
     * Because the numbers written are exactly the ones already being rendered, this **cannot
     * change the output** — which is what makes it safe to run without asking. A list that starts
     * at 3 still starts at 3.
     *
     * Rewrites rather than replacement text, so the caller can apply narrow edits confined to the
     * markers: the caret stays in the content it was in, and a list that is already right costs no
     * edit at all.
     *
     * Untouched on purpose: bullets and tasks (nothing to count), fenced code (not Markdown while
     * inside it), and a deeply indented run with no list above it — that is an indented code block
     * whose lines merely look like items. A run survives a single blank line, because a loose list
     * is still one list, and ends at two.
     */
    fun renumberOrderedLists(text: CharSequence): List<Renumber> {
        val out = mutableListOf<Renumber>()
        // Indent width in spaces → the number the next item at that width should carry. Width, not
        // depth level: nothing here needs to know how many levels three spaces means, and guessing
        // would break lists indented by any other amount.
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
                            // Only the digits are wrong; the gap the author left after the dot is
                            // theirs, and rewriting it would be an edit they did not ask for.
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
                        // A shallower item restarts everything nested beneath it.
                        runs.keys.removeAll { it > width }
                    } else {
                        // Any other content closes the runs at this width and below. A line indented
                        // *past* an item is a wrapped continuation and leaves its run alone, which
                        // is why this is `>=` here and `>` above.
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
        /** The bullet character actually written (`-`, `*`, `+`) — reused when the list continues. */
        val bullet: Char = '-',
        /** The number this ordered item actually carried — counted on from when the list continues. */
        val ordinal: Int = 0,
    )

    /**
     * Break [line] into its leading spaces, the block marker it carries (if any), and the content
     * after that marker.
     *
     * Indent is spaces only. A tab is not treated as structure here: nothing in this editor writes
     * one, and reading it as indent would make the width bookkeeping in [renumberOrderedLists]
     * disagree with what the line looks like.
     */
    private fun parseLine(line: String): LineParts {
        val indentLen = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        val indent = line.substring(0, indentLen)
        val rest = line.substring(indentLen)

        var hashes = 0
        while (hashes < 6 && hashes < rest.length && rest[hashes] == '#') hashes++
        // The space is required: `#tag` is a word, not a heading.
        if (hashes in 1..6 && hashes < rest.length && rest[hashes] == ' ') {
            return LineParts(indent, Block.HEADING, hashes, rest.substring(hashes).trimStart())
        }

        if (rest.startsWith(">")) {
            return LineParts(indent, Block.QUOTE, 0, rest.substring(1).trimStart())
        }

        if (rest.length >= 2 && (rest[0] == '-' || rest[0] == '*' || rest[0] == '+') && rest[1] == ' ') {
            var c = 2
            while (c < rest.length && rest[c] == ' ') c++
            // Tested before the plain bullet it also is: `- [x] done` must read as a task.
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

    /** The word around [pos], as an empty range when the caret is not inside one. */
    private fun wordAt(buf: TextBuffer, pos: Int): Pair<Int, Int> {
        var s = pos.coerceIn(0, buf.length)
        var t = s
        while (s > 0 && isWordChar(buf[s - 1])) s--
        while (t < buf.length && isWordChar(buf[t])) t++
        return s to t
    }

    /** The apostrophe is in so `don't` is one word — emphasising half a contraction is never meant. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\''
}
