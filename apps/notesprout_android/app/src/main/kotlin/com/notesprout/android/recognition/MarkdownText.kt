package com.notesprout.android.recognition

import com.notesprout.android.core.markdown.Block
import com.notesprout.android.core.markdown.Inline
import com.notesprout.android.core.markdown.MarkdownParser

/**
 * Converts the recognized Markdown into the "text-only" export variant: markdown **syntax** is
 * stripped, but layout structure is kept as plain text (per the user's choice —
 * see docs/handwriting-recognition.md / [com.notesprout.android.data.PageText]):
 *
 * - Headings keep their text on their own line, without `#`.
 * - Unordered list items keep a simple `- ` marker; ordered items keep `N. `; tasks keep `- [ ]`/`- [x]`.
 * - Horizontal rules become a blank-line section break (an extra blank line).
 * - Paragraph gaps (blank lines) are preserved.
 * - Inline emphasis (`**`, `*`, `~~`) and link URLs are removed, keeping the visible text.
 */
object MarkdownText {

    /** Strip Markdown syntax from [markdown], keeping structure as readable plain text. */
    fun toPlainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        val blocks = MarkdownParser.parse(markdown)
        val out = StringBuilder()
        for (block in blocks) {
            when (block) {
                is Block.Heading -> out.append(flatten(block.inlines)).append("\n\n")
                is Block.Paragraph -> out.append(flatten(block.inlines)).append("\n\n")
                is Block.Blockquote -> out.append(flatten(block.inlines)).append("\n\n")
                is Block.HorizontalRule -> out.append("\n")   // extra blank line = section break
                is Block.ListItem -> {
                    val indent = "  ".repeat(block.depth.coerceAtLeast(0))
                    val marker = when {
                        block.isTask -> if (block.checked) "- [x] " else "- [ ] "
                        block.ordered -> "${block.displayNumber}. "
                        else -> "- "
                    }
                    out.append(indent).append(marker).append(flatten(block.inlines)).append("\n")
                }
            }
        }
        return out.toString().trim()
    }

    private fun flatten(inlines: List<Inline>): String {
        val sb = StringBuilder()
        for (inline in inlines) {
            when (inline) {
                is Inline.Text -> sb.append(inline.text)
                is Inline.Bold -> sb.append(flatten(inline.children))
                is Inline.Italic -> sb.append(flatten(inline.children))
                is Inline.Strikethrough -> sb.append(flatten(inline.children))
                is Inline.Code -> sb.append(inline.text)
                is Inline.Link -> sb.append(inline.displayText)
            }
        }
        return sb.toString()
    }
}
