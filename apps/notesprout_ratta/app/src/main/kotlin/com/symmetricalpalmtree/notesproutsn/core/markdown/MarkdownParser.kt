package com.symmetricalpalmtree.notesproutsn.core.markdown

/**
 * Block-level markdown elements.
 *
 * The supported subset is deliberate and closed: headings, paragraphs, the three list flavours,
 * blockquotes, horizontal rules. Code fences, tables, raw HTML, and Pandoc's lettered / roman
 * ordered lists are out — a document that rendered them here would come out as run-together
 * paragraphs in every other markdown reader, and portability of the source text is the point.
 */
sealed class Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block()
    data class Paragraph(val inlines: List<Inline>) : Block()

    /** One row of any list: unordered bullet, ordered number, or task checkbox. */
    data class ListItem(
        val ordered: Boolean,
        val depth: Int,
        /**
         * Number to draw for an ordered item, 0 otherwise. The **first** item of a run keeps the
         * number the author wrote — that is CommonMark's `<ol start>`, so `3.` renders as 3 — and
         * every item after it counts on from there no matter what number it claims.
         */
        val displayNumber: Int,
        val isTask: Boolean,
        val checked: Boolean,
        val inlines: List<Inline>,
    ) : Block()

    data class Blockquote(val inlines: List<Inline>) : Block()
    object HorizontalRule : Block()
}

/** Inline (span-level) markdown elements. */
sealed class Inline {
    data class Text(val text: String) : Inline()
    data class Bold(val children: List<Inline>) : Inline()
    data class Italic(val children: List<Inline>) : Inline()
    data class Strikethrough(val children: List<Inline>) : Inline()

    /** `` `code` `` — its content is literal, so it carries text rather than parsed children. */
    data class Code(val text: String) : Inline()

    /** Drawn as underlined display text; the url is kept but never followed. */
    data class Link(val displayText: String, val url: String) : Inline()
}

/**
 * Markdown source → [Block] list. Pure Kotlin: no android imports, no I/O, no state — safe to call
 * from any thread and cheap enough to run on every re-measure.
 *
 * Block detection is line-by-line and every pattern below is `^`-anchored against a single already
 * split line, so no expression can ever scan across a newline (no `DOT_MATCHES_ALL`, no unbounded
 * backtracking over a whole document). Inline parsing is a plain index scan for the same reason.
 */
object MarkdownParser {

    // Anchored, and only ever applied to one line at a time — `lines()` has already removed the
    // newlines, so `\s` here can never swallow a line break.
    private val HEADING = Regex("""^(#{1,6})\s+(.+)""")
    private val BLOCKQUOTE = Regex("""^>\s?(.*)""")
    private val TASK_ITEM = Regex("""^[-*+]\s+\[([xX ])\]\s+(.*)""")
    private val UNORDERED_ITEM = Regex("""^[-*+]\s+(.+)""")
    private val ORDERED_ITEM = Regex("""^(\d+)\.\s+(.+)""")

    fun parse(markdown: String): List<Block> {
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        // One running number per nesting depth. Any block that is not an ordered item at that depth
        // ends the run, so the next ordered item is free to set a new start number.
        val counters = mutableMapOf<Int, Int>()
        var i = 0

        while (i < lines.size) {
            val raw = lines[i]
            val leftTrimmed = raw.trimStart()
            // Two spaces of indent is one nesting level; a tab counts as one character of indent.
            val depth = (raw.length - leftTrimmed.length) / 2
            val line = leftTrimmed.trimEnd()

            if (line.isEmpty()) {
                counters.clear()
                i++
                continue
            }

            val heading = matchAtStart(HEADING, line)
            if (heading != null) {
                blocks += Block.Heading(
                    level = heading.groupValues[1].length,
                    inlines = parseInlines(heading.groupValues[2].trim()),
                )
                counters.clear()
                i++
                continue
            }

            // Checked before lists: `- - -` and `***` are rules, not bullets.
            if (isHorizontalRule(line)) {
                blocks += Block.HorizontalRule
                counters.clear()
                i++
                continue
            }

            val quote = matchAtStart(BLOCKQUOTE, line)
            if (quote != null) {
                val quoted = mutableListOf(quote.groupValues[1])
                while (i + 1 < lines.size) {
                    val next = matchAtStart(BLOCKQUOTE, lines[i + 1].trimStart().trimEnd()) ?: break
                    quoted += next.groupValues[1]
                    i++
                }
                blocks += Block.Blockquote(parseInlines(quoted.joinToString(" ")))
                counters.clear()
                i++
                continue
            }

            // Tasks must be tested before plain bullets — `- [x] done` also matches UNORDERED_ITEM.
            val task = matchAtStart(TASK_ITEM, line)
            if (task != null) {
                blocks += Block.ListItem(
                    ordered = false,
                    depth = depth,
                    displayNumber = 0,
                    isTask = true,
                    checked = task.groupValues[1].lowercase() == "x",
                    inlines = parseInlines(task.groupValues[2]),
                )
                counters.remove(depth)
                i++
                continue
            }

            val bullet = matchAtStart(UNORDERED_ITEM, line)
            if (bullet != null) {
                blocks += Block.ListItem(
                    ordered = false,
                    depth = depth,
                    displayNumber = 0,
                    isTask = false,
                    checked = false,
                    inlines = parseInlines(bullet.groupValues[1]),
                )
                counters.remove(depth)
                i++
                continue
            }

            val numbered = matchAtStart(ORDERED_ITEM, line)
            if (numbered != null) {
                val running = counters[depth]
                val number = if (running != null) running + 1
                else numbered.groupValues[1].toIntOrNull() ?: 1
                counters[depth] = number
                blocks += Block.ListItem(
                    ordered = true,
                    depth = depth,
                    displayNumber = number,
                    isTask = false,
                    checked = false,
                    inlines = parseInlines(numbered.groupValues[2]),
                )
                i++
                continue
            }

            // Paragraph: swallow following lines until a blank one or the start of another block.
            val paragraph = mutableListOf(line)
            while (i + 1 < lines.size) {
                val next = lines[i + 1].trimStart().trimEnd()
                if (next.isEmpty() || startsBlock(next)) break
                paragraph += next
                i++
            }
            blocks += Block.Paragraph(parseInlines(paragraph.joinToString(" ")))
            counters.clear()
            i++
        }

        return blocks
    }

    /** All patterns are `^`-anchored; this keeps the "must match at 0" intent explicit. */
    private fun matchAtStart(regex: Regex, line: String): MatchResult? =
        regex.find(line)?.takeIf { it.range.first == 0 }

    /** True when [line] opens a new block, which ends any paragraph currently being collected. */
    private fun startsBlock(line: String): Boolean =
        matchAtStart(HEADING, line) != null ||
            isHorizontalRule(line) ||
            matchAtStart(BLOCKQUOTE, line) != null ||
            matchAtStart(TASK_ITEM, line) != null ||
            matchAtStart(UNORDERED_ITEM, line) != null ||
            matchAtStart(ORDERED_ITEM, line) != null

    /** Three or more of `-`, `*`, or `_` — mixed together they are not a rule (`-*-` is text). */
    private fun isHorizontalRule(line: String): Boolean {
        val bare = line.replace(" ", "").replace("\t", "")
        if (bare.length < 3) return false
        return bare.all { it == '-' } || bare.all { it == '*' } || bare.all { it == '_' }
    }

    // ── Inlines ───────────────────────────────────────────────────────────────

    /**
     * Index scan rather than regex: the markers nest, and a regex able to span a whole paragraph is
     * exactly the shape that backtracks catastrophically on unclosed markup.
     *
     * Order matters. Code wins outright (its content is literal). Two-character markers are tested
     * before their one-character selves, or `**bold**` reads as an empty italic. Images come before
     * links, or the `!` is left stranded and the rest renders as a link that isn't one. Anything
     * unclosed falls through to a literal character, so half-typed markup shows as typed.
     */
    internal fun parseInlines(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end >= 0) {
                        out += Inline.Code(text.substring(i + 1, end))
                        i = end + 1
                    } else i = literal(out, text, i)
                }

                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end >= 0) {
                        out += Inline.Strikethrough(parseInlines(text.substring(i + 2, end)))
                        i = end + 2
                    } else i = literal(out, text, i)
                }

                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end >= 0) {
                        out += Inline.Bold(parseInlines(text.substring(i + 2, end)))
                        i = end + 2
                    } else i = literal(out, text, i)
                }

                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end >= 0) {
                        out += Inline.Bold(parseInlines(text.substring(i + 2, end)))
                        i = end + 2
                    } else i = literal(out, text, i)
                }

                // `![alt](url)` — nothing draws images, so the alt text stands in as an italic
                // caption. An empty alt leaves nothing at all; a stray bracket would read worse.
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    val altEnd = text.indexOf(']', i + 2)
                    val urlEnd = if (altEnd >= 0 && altEnd + 1 < text.length && text[altEnd + 1] == '(') {
                        text.indexOf(')', altEnd + 2)
                    } else -1
                    if (urlEnd >= 0) {
                        val alt = text.substring(i + 2, altEnd)
                        if (alt.isNotEmpty()) out += Inline.Italic(listOf(Inline.Text(alt)))
                        i = urlEnd + 1
                    } else i = literal(out, text, i)
                }

                c == '[' -> {
                    val textEnd = text.indexOf(']', i + 1)
                    val urlEnd = if (textEnd >= 0 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                        text.indexOf(')', textEnd + 2)
                    } else -1
                    if (urlEnd >= 0) {
                        out += Inline.Link(
                            displayText = text.substring(i + 1, textEnd),
                            url = text.substring(textEnd + 2, urlEnd),
                        )
                        i = urlEnd + 1
                    } else i = literal(out, text, i)
                }

                c == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end >= 0) {
                        out += Inline.Italic(parseInlines(text.substring(i + 1, end)))
                        i = end + 1
                    } else i = literal(out, text, i)
                }

                c == '_' -> {
                    val end = text.indexOf('_', i + 1)
                    if (end >= 0) {
                        out += Inline.Italic(parseInlines(text.substring(i + 1, end)))
                        i = end + 1
                    } else i = literal(out, text, i)
                }

                else -> i = literal(out, text, i)
            }
        }
        return out
    }

    /**
     * Emits `text[i]` as literal content and returns the next index. Consecutive literals coalesce
     * into one [Inline.Text] so a plain paragraph is a single node rather than one per character.
     */
    private fun literal(out: MutableList<Inline>, text: String, i: Int): Int {
        val last = out.lastOrNull()
        if (last is Inline.Text) out[out.lastIndex] = Inline.Text(last.text + text[i])
        else out += Inline.Text(text[i].toString())
        return i + 1
    }
}
