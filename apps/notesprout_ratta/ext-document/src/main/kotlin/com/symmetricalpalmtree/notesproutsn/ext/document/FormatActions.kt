package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.markdown.EditableBuffer
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownFormatter
import com.symmetricalpalmtree.notesproutsn.markdown.TextBuffer

/**
 * One [FormatTool] applied to the buffer (arc 19 / M4) — the bar's fourteen tools and the four
 * chord-only ones, all of them the shared engine's [MarkdownFormatter] run over the current
 * selection.
 *
 * Every operation goes through the live `Editable`, which is what makes the editor's own Ctrl+Z take
 * it back, and each one hands back the selection it wants afterwards — a toggle that put four
 * characters in front of the line has to move the caret with them or the writer's next keystroke
 * lands in the marker.
 *
 * Two members of the enum are not formatter operations at all: Search and Word count act on the
 * screen rather than on the buffer, and are routed straight back out through the callbacks.
 */
internal class FormatActions(
    private val binding: ActivityDocumentEditorBinding,
    /** Preview is read-only: a format tool there has nothing to act on. */
    private val isPreviewing: () -> Boolean,
    private val onSearch: () -> Unit,
    private val onWordCount: () -> Unit,
) {

    fun run(tool: FormatTool) {
        when (tool) {
            FormatTool.H1 -> block(MarkdownFormatter.Block.HEADING, 1)
            FormatTool.H2 -> block(MarkdownFormatter.Block.HEADING, 2)
            FormatTool.H3 -> block(MarkdownFormatter.Block.HEADING, 3)
            FormatTool.BOLD -> inline("**")
            FormatTool.ITALIC -> inline("*")
            FormatTool.STRIKETHROUGH -> inline("~~")
            FormatTool.CODE -> inline("`")
            FormatTool.QUOTE -> block(MarkdownFormatter.Block.QUOTE)
            FormatTool.BULLET -> block(MarkdownFormatter.Block.BULLET)
            FormatTool.ORDERED -> block(MarkdownFormatter.Block.ORDERED)
            FormatTool.TASK -> block(MarkdownFormatter.Block.TASK)
            FormatTool.LINK -> apply(MarkdownFormatter::insertLink)
            FormatTool.IMAGE -> apply(MarkdownFormatter::insertImage)
            FormatTool.RULE -> apply(MarkdownFormatter::insertRule)
            // Not formatter operations: these two act on the screen, not on the buffer.
            FormatTool.SEARCH -> onSearch()
            FormatTool.WORD_COUNT -> onWordCount()
        }
    }

    /** A block toggle by kind — the chord-only paragraph and H4–H6 come in here too. */
    fun block(kind: MarkdownFormatter.Block, level: Int = 1) = apply { buf, s, t ->
        MarkdownFormatter.toggleBlock(buf, s, t, kind, level)
    }

    private fun inline(marker: String) = apply { buf, s, t ->
        MarkdownFormatter.toggleInline(buf, s, t, marker)
    }

    /** Run one formatter operation over the current selection and re-install the caret it returns. */
    private fun apply(op: (TextBuffer, Int, Int) -> MarkdownFormatter.Selection) {
        if (isPreviewing()) return
        val text = binding.editor.text ?: return
        val start = binding.editor.selectionStart.coerceIn(0, text.length)
        val end = binding.editor.selectionEnd.coerceIn(0, text.length)
        val selection = op(EditableBuffer(text), start, end)
        binding.editor.setSelection(
            selection.start.coerceIn(0, text.length),
            selection.end.coerceIn(0, text.length),
        )
    }
}
