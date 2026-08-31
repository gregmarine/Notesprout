package com.symmetricalpalmtree.notesproutsn.ext.document

import android.content.Context
import android.text.Editable
import android.widget.Toast
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownFormatter
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownReflow
import com.symmetricalpalmtree.notesproutsn.markdown.TextSearch
import java.util.Locale

/**
 * The editor's tidying and measuring tools (arc 19 / M5) — reflow, list renumbering, word count, and
 * the scroll that keeps the caret above the keyboard.
 *
 * They live together, and outside the Activity, because they share one shape: each takes the live
 * `Editable` and the selection, does one thing to it, and reports. None of them touches the seam,
 * the saver's bookkeeping or the mode — the two callbacks are the whole of what they need from the
 * screen.
 *
 * Every buffer change here goes through the `Editable`, exactly like a format-bar operation, so the
 * editor's own undo takes it back.
 *
 * **Document text is never logged** — which slice was worked on, never what was in it.
 */
internal class EditorTools(
    private val context: Context,
    private val binding: ActivityDocumentEditorBinding,
    private val isPreviewing: () -> Boolean,
    /** Reflow is a writing tool: running it from Preview leaves Preview first. */
    private val leavePreview: () -> Unit,
    /** A deliberate edit to the whole document goes down now, not in two seconds' time. */
    private val onEdited: () -> Unit,
) {

    // ── Ordered lists ─────────────────────────────────────────────────────────

    /**
     * Make the ordered lists in the buffer read the way Markdown renders them.
     *
     * Rewrites are applied back-to-front so offsets computed against the old text stay valid, and
     * the caret is carried by [caretAfterRenumber] — a marker rewrite must not shuffle the caret
     * out of the words it was sitting in.
     *
     * Returns whether anything was rewritten, which is half of what [reflow] reports.
     */
    fun renumberLists(text: Editable): Boolean {
        val changes = MarkdownFormatter.renumberOrderedLists(text)
        if (changes.isEmpty()) return false
        val caret = caretAfterRenumber(changes, binding.editor.selectionEnd)
        for (c in changes.asReversed()) {
            text.replace(c.at, (c.at + c.length).coerceAtMost(text.length), c.marker)
        }
        binding.editor.setSelection(caret.coerceIn(0, text.length))
        return true
    }

    /**
     * og's list continuation, through the buffer rather than through key events: Enter inside a list
     * item writes the next item's marker, and Enter on an empty item takes the marker away and ends
     * the list.
     *
     * [newlineIndex] is where the plain newline was just typed — the Activity's watcher notes it and
     * calls this from `afterTextChanged`, which is the only place an `Editable` may be edited.
     */
    fun continueListAt(text: Editable, newlineIndex: Int) {
        var lineStart = newlineIndex
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = newlineIndex + 1
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

        val before = text.subSequence(lineStart, newlineIndex).toString()
        val after = text.subSequence((newlineIndex + 1).coerceAtMost(text.length), lineEnd).toString()

        when (val action = MarkdownFormatter.listEnter(before, after)) {
            is MarkdownFormatter.ListEnter.Continue -> {
                val at = (newlineIndex + 1).coerceAtMost(text.length)
                text.insert(at, action.marker)
                binding.editor.setSelection((at + action.marker.length).coerceAtMost(text.length))
            }

            is MarkdownFormatter.ListEnter.End -> {
                text.delete(lineStart, (lineStart + action.length).coerceAtMost(text.length))
            }

            null -> return
        }
        // An item added in the middle leaves the ones below it claiming numbers they no longer have.
        renumberLists(text)
    }

    // ── Reflow ────────────────────────────────────────────────────────────────

    /**
     * Join wrapped lines into paragraphs — over the selection when there is one, otherwise the whole
     * document. Recognized handwriting arrives with a break at every hand-wrapped line, so this is
     * usually the first thing a fresh draft wants.
     *
     * **No control this phase.** og's home for the button is the source strip, which M6 builds; a
     * temporary one somewhere else would only have to be taken away again. `Ctrl+Shift+F` is the
     * whole of it until then.
     */
    fun reflow() {
        if (isPreviewing()) leavePreview()
        val text = binding.editor.text ?: return
        if (text.isEmpty()) return

        // A selection is grown to whole lines first: reflowing half a line would join across a break
        // the writer cannot see the other side of.
        val hasSelection = binding.editor.selectionEnd > binding.editor.selectionStart
        val whole = text.toString()
        val from = if (!hasSelection) 0 else
            whole.lastIndexOf('\n', (binding.editor.selectionStart - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
        val to = if (!hasSelection) whole.length else
            whole.indexOf('\n', binding.editor.selectionEnd).let { if (it < 0) whole.length else it }

        val slice = whole.substring(from, to)
        val reflowed = MarkdownReflow.reflow(slice)
        val joined = reflowed != slice
        if (joined) {
            text.replace(from, to, reflowed)
            binding.editor.setSelection((from + reflowed.length).coerceIn(0, text.length))
        }
        // Tidying the source is tidying the numbers too — including a list left with gaps by an item
        // deleted long ago. Rendering-neutral, so it runs document-wide even for a selection.
        val renumbered = renumberLists(text)
        if (!joined && !renumbered) {
            Toast.makeText(context, R.string.reflow_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        onEdited()
        Slog.d(TAG) { "reflowed ${if (hasSelection) "selection" else "document"}" }
    }

    // ── Word count ────────────────────────────────────────────────────────────

    /**
     * Words and characters — for the selection when there is one, otherwise the document.
     *
     * A toast, and one of the sanctioned ones: it confirms a result that already exists rather than
     * explaining why something did not work.
     */
    fun showWordCount() {
        val (words, chars) = wordCount()
        val prefix = if (hasCountableSelection()) {
            context.getString(R.string.word_count_selection_prefix)
        } else {
            ""
        }
        Toast.makeText(
            context,
            prefix + String.format(
                Locale.US, context.getString(R.string.word_count_format), words, chars,
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** (words, characters) over the same slice [showWordCount] reports on. */
    fun wordCount(): Pair<Int, Int> {
        val text = binding.editor.text ?: return 0 to 0
        val slice = if (hasCountableSelection()) {
            text.subSequence(binding.editor.selectionStart, binding.editor.selectionEnd).toString()
        } else {
            text.toString()
        }
        return TextSearch.counts(slice)
    }

    private fun hasCountableSelection(): Boolean =
        !isPreviewing() && binding.editor.selectionEnd > binding.editor.selectionStart

    // ── Keeping the caret above the keyboard ──────────────────────────────────

    /**
     * Keep [keepCaretVisible] honest as the surface resizes — the watch lives here because the
     * scroll it triggers does.
     *
     * A shorter editing surface can leave the caret below the fold, which is precisely what the
     * keyboard appearing does. Only a real height change is worth reacting to.
     */
    fun watchHeight() {
        binding.editor.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) binding.editor.post { keepCaretVisible() }
        }
    }

    /**
     * Scroll the editing surface just enough to keep the caret's line in view.
     *
     * The layout shrinking is only half the fix: the room the keyboard takes is the room the caret
     * was probably sitting in. Text coordinates are offset by the surface's own top padding and
     * displaced by its scroll, so the caret's line is below the fold when
     * `paddingTop + lineBottom - scrollY` passes `height - paddingBottom`.
     */
    fun keepCaretVisible() {
        if (isPreviewing() || !binding.editor.hasFocus()) return
        val layout = binding.editor.layout ?: return
        val caret = binding.editor.selectionEnd.coerceIn(0, binding.editor.text?.length ?: 0)
        val lineBottom = layout.getLineBottom(layout.getLineForOffset(caret))
        val below = binding.editor.paddingTop + lineBottom - binding.editor.scrollY -
            (binding.editor.height - binding.editor.paddingBottom)
        if (below > 0) binding.editor.scrollBy(0, below)
    }

    internal companion object {
        private const val TAG = "DocumentEditor"

        /**
         * Where [caret] ends up once [changes] — ascending, non-overlapping marker rewrites — have
         * been applied.
         *
         * A caret standing *inside* a marker being rewritten has nothing to be carried by: the
         * characters it sat between may not exist afterwards, and a shrinking marker (`10.` → `3.`)
         * would otherwise leave it inside the item's text. It is put at the end of the new marker,
         * which is where the content starts — the one position a rewritten marker can promise.
         */
        fun caretAfterRenumber(changes: List<MarkdownFormatter.Renumber>, caret: Int): Int {
            var delta = 0
            for (c in changes) {
                if (c.at + c.length <= caret) delta += c.marker.length - c.length
                else if (c.at < caret) return c.at + delta + c.marker.length
                else break
            }
            return caret + delta
        }
    }
}
