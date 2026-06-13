package com.notesprout.android.notebook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.notesprout.android.R
import com.notesprout.android.core.Slog
import com.notesprout.android.databinding.DialogTextEditBinding

/**
 * Dialog for editing Markdown text with a live WYSIWYG preview mode and a plain Markdown source
 * mode. Both modes share the same EditText and underlying text — mode switching only changes how
 * the text is displayed (with or without formatting spans).
 *
 * Formatting toolbar provides: Bold, Italic, Strikethrough, Heading (H1–H6 popup), Unordered
 * List, Ordered List, Task Checkbox, Blockquote, Horizontal Rule, Link.
 *
 * Auto-renumbering: ordered list blocks are renumbered 1, 2, 3… on every text change.
 *
 * NOTE (Prompt 2): This dialog is temporarily wired to a toolbar button in NotebookActivity for
 * verification. Prompt 3 will replace that wiring with the real text-object insert flow.
 */
class TextEditDialog(
    private val context: Context,
    private val initialMarkdown: String,
    private val onConfirm: (String) -> Unit,
) {

    // ── Regex patterns for WYSIWYG span application ───────────────────────────

    private val boldDoubleStarPattern = Regex("""\*\*([^*\n]+?)\*\*""")
    private val boldDoubleUnderPattern = Regex("""__([^_\n]+?)__""")
    private val italicStarPattern = Regex("""\*([^*\n]+?)\*""")
    private val italicUnderPattern = Regex("""_([^_\n]+?)_""")
    private val strikePattern = Regex("""~~([^~\n]+?)~~""")
    private val linkPattern = Regex("""\[([^\]\n]+)\]\(([^)\n]*)\)""")
    private val headingPattern = Regex("""^(#{1,6}) (.+)$""", RegexOption.MULTILINE)
    private val ulPattern = Regex("""^([-*+]) (.+)$""", RegexOption.MULTILINE)
    private val olPattern = Regex("""^(\d+)\. (.+)$""", RegexOption.MULTILINE)
    private val taskCheckedPattern = Regex("""^[-*+] \[([xX])\] (.+)$""", RegexOption.MULTILINE)
    private val taskUncheckedPattern = Regex("""^[-*+] \[ \] (.+)$""", RegexOption.MULTILINE)
    private val blockquotePattern = Regex("""^(>) (.*)$""", RegexOption.MULTILINE)
    private val hrPattern = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""", RegexOption.MULTILINE)
    private val orderedItemPattern = Regex("""^(\d+)\. """, RegexOption.MULTILINE)

    // inkLight color (#888888) for de-emphasised markers
    private val markerColor = Color.parseColor("#888888")

    // ── State ─────────────────────────────────────────────────────────────────

    private var isWysiwygMode = true
    private var isApplyingFormatting = false

    // Captured in beforeTextChanged to detect newline insertions for ordered-list continuation
    private var textBeforeChange = ""
    private var changeStartIndex = 0
    private var changeInsertedCount = 0

    // ── Public entry point ────────────────────────────────────────────────────

    fun show() {
        val binding = DialogTextEditBinding.inflate(LayoutInflater.from(context))
        binding.editMarkdown.setText(initialMarkdown)

        setupModeToggle(binding)
        binding.editMarkdown.addTextChangedListener(createTextWatcher(binding))
        setupFormattingToolbar(binding)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Edit Text")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(binding.editMarkdown.windowToken, 0)
                val text = binding.editMarkdown.text?.toString().orEmpty()
                if (text.isNotEmpty()) onConfirm(text)
            }
            .setNegativeButton("Cancel") { _, _ ->
                val imm = context.getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(binding.editMarkdown.windowToken, 0)
            }
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        binding.editMarkdown.requestFocus()
        if (initialMarkdown.isNotEmpty()) {
            binding.editMarkdown.setSelection(initialMarkdown.length)
        }

        // Show keyboard
        binding.editMarkdown.postDelayed({
            ViewCompat.getWindowInsetsController(binding.editMarkdown)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm?.showSoftInput(binding.editMarkdown, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)

        // Apply initial WYSIWYG spans if content is non-empty
        if (initialMarkdown.isNotEmpty()) {
            binding.editMarkdown.editableText?.let { applyWysiwygSpans(it) }
        }

        // Mark WYSIWYG as initially selected
        binding.btnModeWysiwyg.isSelected = true
        binding.btnModeMarkdown.isSelected = false
    }

    // ── Mode toggle ───────────────────────────────────────────────────────────

    private fun setupModeToggle(binding: DialogTextEditBinding) {
        binding.btnModeWysiwyg.setOnClickListener {
            if (isWysiwygMode) return@setOnClickListener
            isWysiwygMode = true
            binding.btnModeWysiwyg.isSelected = true
            binding.btnModeMarkdown.isSelected = false
            // Re-apply spans to current text
            binding.editMarkdown.editableText?.let { applyWysiwygSpans(it) }
        }
        binding.btnModeMarkdown.setOnClickListener {
            if (!isWysiwygMode) return@setOnClickListener
            isWysiwygMode = false
            binding.btnModeWysiwyg.isSelected = false
            binding.btnModeMarkdown.isSelected = true
            // Strip all formatting spans — raw source is already the text content
            binding.editMarkdown.editableText?.let { stripAllSpans(it) }
        }
    }

    // ── TextWatcher ───────────────────────────────────────────────────────────

    private fun createTextWatcher(binding: DialogTextEditBinding) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            textBeforeChange = s.toString()
            changeStartIndex = start
            changeInsertedCount = after
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            if (isApplyingFormatting) return
            isApplyingFormatting = true
            try {
                // 1. Auto-continue ordered list on Enter
                autoContineOrderedList(s, binding)

                // 2. Renumber all ordered list blocks
                renumberOrderedLists(s, binding)

                // 3. Apply WYSIWYG spans (span changes do not re-trigger TextWatcher)
                if (isWysiwygMode) applyWysiwygSpans(s)
            } finally {
                isApplyingFormatting = false
            }
        }
    }

    // ── Ordered list auto-continuation ───────────────────────────────────────

    /**
     * When the user presses Enter at the end of an ordered list line, prefix the new line
     * with the next sequential number. This is purely additive — the renumber pass below will
     * correct the sequence for the whole block afterward.
     */
    private fun autoContineOrderedList(s: Editable, binding: DialogTextEditBinding) {
        // Detect a single newline insertion
        if (changeInsertedCount != 1) return
        val insertedChar = s.getOrNull(changeStartIndex) ?: return
        if (insertedChar != '\n') return

        // What line was the cursor on before the Enter?
        val beforeText = textBeforeChange
        val lineBeforeStart = beforeText.lastIndexOf('\n', changeStartIndex - 1) + 1
        val lineBeforeText = beforeText.substring(lineBeforeStart, changeStartIndex)

        // Match ordered list item: "N. content"
        val olMatch = Regex("""^(\d+)\. (.+)""").find(lineBeforeText) ?: return
        val content = olMatch.groupValues[2]
        if (content.isBlank()) return // empty item → don't continue

        val nextNum = olMatch.groupValues[1].toInt() + 1
        val insertPos = changeStartIndex + 1 // after the '\n'
        if (insertPos > s.length) return

        val prefix = "$nextNum. "
        s.insert(insertPos, prefix)
        // Move cursor to after the inserted prefix
        binding.editMarkdown.setSelection((insertPos + prefix.length).coerceAtMost(s.length))
    }

    // ── Ordered list renumbering ──────────────────────────────────────────────

    /**
     * Rewrites the literal numbers in each contiguous ordered-list block so they read
     * 1, 2, 3… from the top of the block. Operates directly on the [Editable] so cursor
     * management is handled by Android's text machinery (individual replace calls preserve
     * positions better than a full text swap).
     */
    private fun renumberOrderedLists(s: Editable, binding: DialogTextEditBinding) {
        val text = s.toString()
        val lines = text.split('\n')

        var pos = 0        // current char offset into s
        var blockCounter = 0

        for (line in lines) {
            val olMatch = Regex("""^(\d+)\. """).find(line)
            if (olMatch != null) {
                blockCounter++
                val actual = olMatch.groupValues[1].toIntOrNull() ?: blockCounter
                if (actual != blockCounter) {
                    // Replace the number prefix on this line
                    val prefixStart = pos
                    val oldPrefix = "$actual. "
                    val newPrefix = "$blockCounter. "
                    if (s.length >= prefixStart + oldPrefix.length &&
                        s.substring(prefixStart, prefixStart + oldPrefix.length) == oldPrefix
                    ) {
                        val cursorBefore = binding.editMarkdown.selectionStart
                        s.replace(prefixStart, prefixStart + oldPrefix.length, newPrefix)
                        // Adjust cursor: if it was after the replaced region, shift by delta
                        val delta = newPrefix.length - oldPrefix.length
                        if (cursorBefore > prefixStart + oldPrefix.length) {
                            val newCursor = (cursorBefore + delta).coerceIn(0, s.length)
                            binding.editMarkdown.setSelection(newCursor)
                        }
                    }
                }
            } else {
                blockCounter = 0
            }
            pos += line.length + 1 // +1 for the '\n' separator
        }
    }

    // ── WYSIWYG span application ──────────────────────────────────────────────

    private fun applyWysiwygSpans(s: Editable) {
        stripAllSpans(s)
        val text = s.toString()

        // Apply in order: block-level first, then inline (so inline can overlay)

        // Headings — line-level: size + bold for text, de-emphasize # markers
        headingPattern.findAll(text).forEach { m ->
            val hashEnd = m.groups[1]!!.range.last + 1 // after the # chars + space
            val hashRange = m.groups[1]!!.range
            val textRange = m.groups[2]!!.range
            val level = m.groupValues[1].length
            val sizeMult = headingSizeMult(level)
            // De-emphasize markers
            s.setSpan(ForegroundColorSpan(markerColor), hashRange.first, hashEnd + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Heading text: size + bold
            s.setSpan(RelativeSizeSpan(sizeMult), textRange.first, textRange.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.BOLD), textRange.first, textRange.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Blockquotes — de-emphasize '>' prefix, indent content
        blockquotePattern.findAll(text).forEach { m ->
            val prefixRange = m.groups[1]!!.range
            // Full line including content
            val lineStart = m.range.first
            val lineEnd = m.range.last + 1
            s.setSpan(ForegroundColorSpan(markerColor), prefixRange.first, prefixRange.last + 2,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val density = context.resources.displayMetrics.density
            val stripe = (3f * density).toInt().coerceAtLeast(2)
            val gap = (8f * density).toInt()
            s.setSpan(QuoteSpan(Color.BLACK, stripe, gap), lineStart, lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Horizontal rules — de-emphasize whole line
        hrPattern.findAll(text).forEach { m ->
            s.setSpan(ForegroundColorSpan(markerColor), m.range.first, m.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Ordered lists — de-emphasize "N. " prefix
        olPattern.findAll(text).forEach { m ->
            val prefixRange = m.groups[1]!!.range
            // de-emphasize "N. "
            s.setSpan(ForegroundColorSpan(markerColor), prefixRange.first, prefixRange.last + 3,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // indent
            val lineStart = m.range.first
            val lineEnd = m.range.last + 1
            val indentPx = (16f * context.resources.displayMetrics.density).toInt()
            s.setSpan(LeadingMarginSpan.Standard(indentPx, indentPx), lineStart, lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Unordered lists — de-emphasize bullet marker
        ulPattern.findAll(text).forEach { m ->
            val markerRange = m.groups[1]!!.range
            // Only process lines that are NOT task items (task regex is more specific)
            val lineText = text.substring(m.range.first, m.range.last + 1)
            if (taskCheckedPattern.containsMatchIn(lineText) ||
                taskUncheckedPattern.containsMatchIn(lineText)) return@forEach
            s.setSpan(ForegroundColorSpan(markerColor), markerRange.first, markerRange.last + 2,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val lineStart = m.range.first
            val lineEnd = m.range.last + 1
            val indentPx = (16f * context.resources.displayMetrics.density).toInt()
            s.setSpan(LeadingMarginSpan.Standard(indentPx, indentPx), lineStart, lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Task checkboxes — de-emphasize "- [ ] " / "- [x] " prefix
        taskCheckedPattern.findAll(text).forEach { m ->
            s.setSpan(ForegroundColorSpan(markerColor), m.range.first, m.range.first + 6,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Strike through the whole task content (checked)
            val contentStart = m.range.first + 6
            val contentEnd = m.range.last + 1
            if (contentStart < contentEnd)
                s.setSpan(StrikethroughSpan(), contentStart, contentEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        taskUncheckedPattern.findAll(text).forEach { m ->
            s.setSpan(ForegroundColorSpan(markerColor), m.range.first, m.range.first + 6,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // ── Inline spans (applied after block spans so they overlay correctly) ──

        // Bold **text** — apply to content, de-emphasize markers
        applyInlineWrap(s, text, boldDoubleStarPattern, 2, StyleSpan(Typeface.BOLD))
        // Bold __text__
        applyInlineWrap(s, text, boldDoubleUnderPattern, 2, StyleSpan(Typeface.BOLD))
        // Italic *text* (single star — must not overlap ** matches)
        applyItalicStar(s, text)
        // Italic _text_
        applyInlineWrap(s, text, italicUnderPattern, 1, StyleSpan(Typeface.ITALIC))
        // Strikethrough ~~text~~
        applyInlineWrap(s, text, strikePattern, 2, StrikethroughSpan())
        // Links [text](url)
        applyLinks(s, text)
    }

    /**
     * Applies [contentSpan] to the capture group content and de-emphasizes the [markerLen]-char
     * opening and closing markers.
     */
    private fun applyInlineWrap(
        s: Editable,
        text: String,
        pattern: Regex,
        markerLen: Int,
        contentSpan: Any,
    ) {
        pattern.findAll(text).forEach { m ->
            val fullStart = m.range.first
            val fullEnd = m.range.last + 1
            val contentStart = fullStart + markerLen
            val contentEnd = fullEnd - markerLen
            if (contentStart >= contentEnd) return@forEach
            // Opening marker
            s.setSpan(ForegroundColorSpan(markerColor), fullStart, contentStart,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Content span
            s.setSpan(contentSpan, contentStart, contentEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Closing marker
            s.setSpan(ForegroundColorSpan(markerColor), contentEnd, fullEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Applies italic to *text* single-star spans, skipping positions already covered by
     * bold ** spans to avoid double-matching.
     */
    private fun applyItalicStar(s: Editable, text: String) {
        italicStarPattern.findAll(text).forEach { m ->
            val fullStart = m.range.first
            val fullEnd = m.range.last + 1
            // Skip if this is part of a ** sequence
            if (fullStart > 0 && text[fullStart - 1] == '*') return@forEach
            if (fullEnd < text.length && text[fullEnd] == '*') return@forEach
            val contentStart = fullStart + 1
            val contentEnd = fullEnd - 1
            if (contentStart >= contentEnd) return@forEach
            s.setSpan(ForegroundColorSpan(markerColor), fullStart, contentStart,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(StyleSpan(Typeface.ITALIC), contentStart, contentEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(markerColor), contentEnd, fullEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun applyLinks(s: Editable, text: String) {
        linkPattern.findAll(text).forEach { m ->
            val displayText = m.groups[1]!!
            val urlGroup = m.groups[2]!!
            // "[" is de-emphasized, display text is underlined, "](url)" is de-emphasized
            val bracketOpen = m.range.first
            val displayStart = bracketOpen + 1
            val displayEnd = displayText.range.last + 1
            val urlStart = urlGroup.range.first
            val urlEnd = m.range.last + 1

            s.setSpan(ForegroundColorSpan(markerColor), bracketOpen, displayStart,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(UnderlineSpan(), displayStart, displayEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(markerColor), displayEnd, urlEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun headingSizeMult(level: Int) = when (level) {
        1 -> 2.0f; 2 -> 1.75f; 3 -> 1.5f; 4 -> 1.25f; 5 -> 1.1f; else -> 1.0f
    }

    private fun stripAllSpans(s: Editable) {
        s.getSpans(0, s.length, Any::class.java).forEach { span ->
            when (span) {
                is StyleSpan, is ForegroundColorSpan, is StrikethroughSpan,
                is UnderlineSpan, is RelativeSizeSpan, is LeadingMarginSpan,
                is QuoteSpan -> s.removeSpan(span)
            }
        }
    }

    // ── Formatting toolbar ────────────────────────────────────────────────────

    private fun setupFormattingToolbar(binding: DialogTextEditBinding) {
        val edit = binding.editMarkdown

        binding.btnFmtBold.setOnClickListener { wrapInline(edit, "**") }
        binding.btnFmtItalic.setOnClickListener { wrapInline(edit, "*") }
        binding.btnFmtStrikethrough.setOnClickListener { wrapInline(edit, "~~") }

        binding.btnFmtHeading.setOnClickListener {
            val popup = PopupMenu(context, binding.btnFmtHeading)
            popup.menu.add(0, 0, 0, "Normal")
            for (i in 1..6) popup.menu.add(0, i, i, "H$i")
            popup.setOnMenuItemClickListener { item ->
                setHeadingLevel(edit, item.itemId)
                true
            }
            popup.show()
        }

        binding.btnFmtUl.setOnClickListener { toggleLinePrefix(edit, "- ") }
        binding.btnFmtOl.setOnClickListener { toggleOrderedList(edit) }
        binding.btnFmtTask.setOnClickListener { toggleTaskPrefix(edit) }
        binding.btnFmtBlockquote.setOnClickListener { toggleLinePrefix(edit, "> ") }
        binding.btnFmtHr.setOnClickListener { insertHorizontalRule(edit) }
        binding.btnFmtLink.setOnClickListener { insertLink(edit) }
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    /** Wraps selection in [marker] pairs, or toggles them off if already wrapped. */
    private fun wrapInline(edit: android.widget.EditText, marker: String) {
        val text = edit.text ?: return
        val start = edit.selectionStart
        val end = edit.selectionEnd

        if (start == end) {
            // No selection: insert empty pair with cursor between them
            text.insert(start, "$marker$marker")
            edit.setSelection(start + marker.length)
            return
        }

        val selected = text.substring(start, end)
        // Toggle off if already wrapped
        if (selected.startsWith(marker) && selected.endsWith(marker) &&
            selected.length >= marker.length * 2 + 1
        ) {
            val inner = selected.substring(marker.length, selected.length - marker.length)
            text.replace(start, end, inner)
            edit.setSelection(start, start + inner.length)
        } else {
            text.insert(end, marker)
            text.insert(start, marker)
            edit.setSelection(start, end + marker.length * 2)
        }
    }

    /**
     * Sets or removes the heading prefix on each line in the selection.
     * [level] = 0 means "Normal" (remove heading).
     */
    private fun setHeadingLevel(edit: android.widget.EditText, level: Int) {
        val text = edit.text ?: return
        val (firstLineStart, lastLineEnd) = selectionLineRange(text.toString(),
            edit.selectionStart, edit.selectionEnd)

        val newLines = text.substring(firstLineStart, lastLineEnd)
            .split('\n')
            .map { line ->
                val stripped = line.replace(Regex("^#{1,6}\\s+"), "")
                if (level == 0) stripped else "${"#".repeat(level)} $stripped"
            }
        text.replace(firstLineStart, lastLineEnd, newLines.joinToString("\n"))
    }

    /**
     * Toggles [prefix] on each selected line.
     * If all lines already have the prefix → removes it.
     * Otherwise adds the prefix to every line.
     */
    private fun toggleLinePrefix(edit: android.widget.EditText, prefix: String) {
        val text = edit.text ?: return
        val (firstLineStart, lastLineEnd) = selectionLineRange(text.toString(),
            edit.selectionStart, edit.selectionEnd)

        val lines = text.substring(firstLineStart, lastLineEnd).split('\n')
        val allHave = lines.all { it.startsWith(prefix) }
        val newLines = if (allHave) {
            lines.map { it.removePrefix(prefix) }
        } else {
            lines.map { if (it.startsWith(prefix)) it else "$prefix$it" }
        }
        text.replace(firstLineStart, lastLineEnd, newLines.joinToString("\n"))
    }

    /** Toggles ordered list numbering on selected lines, then lets renumber pass fix sequence. */
    private fun toggleOrderedList(edit: android.widget.EditText) {
        val text = edit.text ?: return
        val (firstLineStart, lastLineEnd) = selectionLineRange(text.toString(),
            edit.selectionStart, edit.selectionEnd)

        val lines = text.substring(firstLineStart, lastLineEnd).split('\n')
        val olRegex = Regex("""^\d+\. """)
        val allHave = lines.all { olRegex.containsMatchIn(it) }
        val newLines = if (allHave) {
            lines.map { it.replace(olRegex, "") }
        } else {
            lines.mapIndexed { i, line ->
                if (olRegex.containsMatchIn(line)) line else "${i + 1}. $line"
            }
        }
        text.replace(firstLineStart, lastLineEnd, newLines.joinToString("\n"))
    }

    /**
     * Toggles task checkbox on selected lines:
     * - Not a task → prefix with "- [ ] "
     * - Unchecked task → toggle to "- [x] "
     * - Checked task → remove task prefix entirely
     */
    private fun toggleTaskPrefix(edit: android.widget.EditText) {
        val text = edit.text ?: return
        val (firstLineStart, lastLineEnd) = selectionLineRange(text.toString(),
            edit.selectionStart, edit.selectionEnd)

        val uncheckedRe = Regex("""^[-*+] \[ \] """)
        val checkedRe = Regex("""^[-*+] \[[xX]\] """)

        val lines = text.substring(firstLineStart, lastLineEnd).split('\n')
        val newLines = lines.map { line ->
            when {
                uncheckedRe.containsMatchIn(line) ->
                    line.replace(uncheckedRe, "- [x] ")
                checkedRe.containsMatchIn(line) ->
                    line.replace(checkedRe, "")
                else -> "- [ ] $line"
            }
        }
        text.replace(firstLineStart, lastLineEnd, newLines.joinToString("\n"))
    }

    /** Inserts a "---" horizontal rule on a new line at the current cursor. */
    private fun insertHorizontalRule(edit: android.widget.EditText) {
        val text = edit.text ?: return
        val pos = edit.selectionStart
        // Ensure preceding newline
        val hr = if (pos > 0 && text[pos - 1] != '\n') "\n---\n" else "---\n"
        text.insert(pos, hr)
        edit.setSelection(pos + hr.length)
    }

    /** Wraps selection as [selection](url) or inserts [text](url) template. */
    private fun insertLink(edit: android.widget.EditText) {
        val text = edit.text ?: return
        val start = edit.selectionStart
        val end = edit.selectionEnd
        val selected = if (start != end) text.substring(start, end) else ""

        if (selected.isNotEmpty()) {
            val link = "[$selected](url)"
            text.replace(start, end, link)
            // Select "url" so the user can type over it
            val urlStart = start + selected.length + 3
            val urlEnd = urlStart + 3
            edit.setSelection(urlStart.coerceAtMost(text.length),
                urlEnd.coerceAtMost(text.length))
        } else {
            val link = "[text](url)"
            text.insert(start, link)
            // Select "text" placeholder
            edit.setSelection(start + 1, start + 5)
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Returns the (firstLineStart, lastLineEnd) character range covering all lines that
     * overlap the selection from [selStart] to [selEnd].
     */
    private fun selectionLineRange(text: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val safeStart = selStart.coerceIn(0, text.length)
        val safeEnd = selEnd.coerceIn(0, text.length)

        val firstLineStart = text.lastIndexOf('\n', safeStart - 1) + 1
        val lastLineEnd = if (safeEnd >= text.length) {
            text.length
        } else {
            val nlPos = text.indexOf('\n', safeEnd)
            if (nlPos < 0) text.length else nlPos
        }
        return Pair(firstLineStart, lastLineEnd)
    }
}
