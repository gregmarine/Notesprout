package com.notesprout.android.core.markdown

// ── Block-level elements ──────────────────────────────────────────────────────

sealed class Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block()
    data class Paragraph(val inlines: List<Inline>) : Block()
    /** Covers unordered bullets, ordered numbers, and task-list checkboxes. */
    data class ListItem(
        val ordered: Boolean,
        val depth: Int,
        /** Computed sequential number (1-based) for ordered items; 0 for unordered. */
        val displayNumber: Int,
        val isTask: Boolean,
        val checked: Boolean,
        val inlines: List<Inline>,
    ) : Block()
    data class Blockquote(val inlines: List<Inline>) : Block()
    object HorizontalRule : Block()
}

// ── Inline elements ───────────────────────────────────────────────────────────

sealed class Inline {
    data class Text(val text: String) : Inline()
    data class Bold(val children: List<Inline>) : Inline()
    data class Italic(val children: List<Inline>) : Inline()
    data class Strikethrough(val children: List<Inline>) : Inline()
    /** Rendered as underlined display text; url is discarded (not clickable). */
    data class Link(val displayText: String, val url: String) : Inline()
}

// ── Parser ────────────────────────────────────────────────────────────────────

object MarkdownParser {

    private val headingRegex = Regex("""^(#{1,6})\s+(.+)""")
    private val blockquoteRegex = Regex("""^>\s?(.*)""")
    private val taskItemRegex = Regex("""^[-*+]\s+\[([xX ])\]\s+(.*)""")
    private val unorderedItemRegex = Regex("""^[-*+]\s+(.+)""")
    private val orderedItemRegex = Regex("""^(\d+)\.\s+(.+)""")

    fun parse(markdown: String): List<Block> {
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        // ordered list counter per nesting depth; cleared by any non-ordered-list block
        val orderedCounters = mutableMapOf<Int, Int>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmedStart = line.trimStart()
            val indent = line.length - trimmedStart.length
            val depth = indent / 2
            val trimmed = trimmedStart.trimEnd()

            if (trimmed.isEmpty()) {
                orderedCounters.clear()
                i++
                continue
            }

            // Heading
            val headingMatch = headingRegex.find(trimmed)
            if (headingMatch != null && headingMatch.range.first == 0) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2].trim()
                blocks += Block.Heading(level, parseInlines(text))
                orderedCounters.clear()
                i++
                continue
            }

            // Horizontal rule: 3+ repeated -, *, or _ with optional spaces (no other chars)
            if (isHorizontalRule(trimmed)) {
                blocks += Block.HorizontalRule
                orderedCounters.clear()
                i++
                continue
            }

            // Blockquote
            val bqMatch = blockquoteRegex.find(trimmed)
            if (bqMatch != null && bqMatch.range.first == 0) {
                val bqLines = mutableListOf(bqMatch.groupValues[1])
                while (i + 1 < lines.size) {
                    val next = lines[i + 1].trimStart().trimEnd()
                    val nextMatch = blockquoteRegex.find(next)
                    if (nextMatch != null && nextMatch.range.first == 0) {
                        bqLines += nextMatch.groupValues[1]
                        i++
                    } else break
                }
                blocks += Block.Blockquote(parseInlines(bqLines.joinToString(" ")))
                orderedCounters.clear()
                i++
                continue
            }

            // Task list item (must precede unordered check)
            val taskMatch = taskItemRegex.find(trimmed)
            if (taskMatch != null && taskMatch.range.first == 0) {
                val checked = taskMatch.groupValues[1].lowercase() == "x"
                val text = taskMatch.groupValues[2]
                blocks += Block.ListItem(
                    ordered = false, depth = depth, displayNumber = 0,
                    isTask = true, checked = checked, inlines = parseInlines(text),
                )
                orderedCounters.remove(depth)
                i++
                continue
            }

            // Unordered list item
            val ulMatch = unorderedItemRegex.find(trimmed)
            if (ulMatch != null && ulMatch.range.first == 0) {
                val text = ulMatch.groupValues[1]
                blocks += Block.ListItem(
                    ordered = false, depth = depth, displayNumber = 0,
                    isTask = false, checked = false, inlines = parseInlines(text),
                )
                orderedCounters.remove(depth)
                i++
                continue
            }

            // Ordered list item (auto-renumbered; literal number in source is ignored)
            val olMatch = orderedItemRegex.find(trimmed)
            if (olMatch != null && olMatch.range.first == 0) {
                val text = olMatch.groupValues[2]
                val num = (orderedCounters[depth] ?: 0) + 1
                orderedCounters[depth] = num
                blocks += Block.ListItem(
                    ordered = true, depth = depth, displayNumber = num,
                    isTask = false, checked = false, inlines = parseInlines(text),
                )
                i++
                continue
            }

            // Paragraph: collect consecutive non-block lines
            val paraLines = mutableListOf(trimmed)
            while (i + 1 < lines.size) {
                val nextTrimmed = lines[i + 1].trimStart().trimEnd()
                if (nextTrimmed.isEmpty() || isBlockStart(nextTrimmed)) break
                paraLines += nextTrimmed
                i++
            }
            blocks += Block.Paragraph(parseInlines(paraLines.joinToString(" ")))
            orderedCounters.clear()
            i++
        }

        return blocks
    }

    /** True when [trimmed] opens a new block and should terminate a running paragraph. */
    private fun isBlockStart(trimmed: String): Boolean {
        if (headingRegex.find(trimmed)?.range?.first == 0) return true
        if (isHorizontalRule(trimmed)) return true
        if (blockquoteRegex.find(trimmed)?.range?.first == 0) return true
        if (taskItemRegex.find(trimmed)?.range?.first == 0) return true
        if (unorderedItemRegex.find(trimmed)?.range?.first == 0) return true
        if (orderedItemRegex.find(trimmed)?.range?.first == 0) return true
        return false
    }

    private fun isHorizontalRule(trimmed: String): Boolean {
        val stripped = trimmed.replace(" ", "").replace("\t", "")
        if (stripped.length < 3) return false
        return stripped.all { it == '-' } || stripped.all { it == '*' } || stripped.all { it == '_' }
    }

    // ── Inline parser ─────────────────────────────────────────────────────────

    internal fun parseInlines(text: String): List<Inline> {
        val result = mutableListOf<Inline>()
        var i = 0
        while (i < text.length) {
            when {
                // Strikethrough ~~text~~ (check before single ~)
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end >= 0) {
                        result += Inline.Strikethrough(parseInlines(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        appendChar(result, text[i]); i++
                    }
                }
                // Bold **text** (check before single *)
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end >= 0) {
                        result += Inline.Bold(parseInlines(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        appendChar(result, text[i]); i++
                    }
                }
                // Bold __text__ (check before single _)
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end >= 0) {
                        result += Inline.Bold(parseInlines(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        appendChar(result, text[i]); i++
                    }
                }
                // Link [text](url)
                text[i] == '[' -> {
                    val textEnd = text.indexOf(']', i + 1)
                    if (textEnd >= 0 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                        val urlEnd = text.indexOf(')', textEnd + 2)
                        if (urlEnd >= 0) {
                            result += Inline.Link(
                                displayText = text.substring(i + 1, textEnd),
                                url = text.substring(textEnd + 2, urlEnd),
                            )
                            i = urlEnd + 1
                        } else {
                            appendChar(result, text[i]); i++
                        }
                    } else {
                        appendChar(result, text[i]); i++
                    }
                }
                // Italic *text* (single *, after ** check)
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end >= 0) {
                        result += Inline.Italic(parseInlines(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        appendChar(result, text[i]); i++
                    }
                }
                // Italic _text_ (single _, after __ check)
                text[i] == '_' -> {
                    val end = text.indexOf('_', i + 1)
                    if (end >= 0) {
                        result += Inline.Italic(parseInlines(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        appendChar(result, text[i]); i++
                    }
                }
                else -> {
                    appendChar(result, text[i]); i++
                }
            }
        }
        return result
    }

    private fun appendChar(result: MutableList<Inline>, c: Char) {
        val last = result.lastOrNull()
        if (last is Inline.Text) {
            result[result.lastIndex] = Inline.Text(last.text + c)
        } else {
            result += Inline.Text(c.toString())
        }
    }
}
