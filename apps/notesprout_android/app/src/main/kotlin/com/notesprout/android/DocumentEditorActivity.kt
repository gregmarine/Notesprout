package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.notesprout.android.core.Slog
import com.notesprout.android.core.TopGuard
import com.notesprout.android.core.markdown.EditableBuffer
import com.notesprout.android.core.markdown.MarkdownFormatter
import com.notesprout.android.core.markdown.MarkdownParser
import com.notesprout.android.core.markdown.MarkdownRenderer
import com.notesprout.android.core.markdown.TextBuffer

/**
 * Full-screen Markdown document editor.
 *
 * **Write in Markdown, read in Preview.** The editing surface is plain monospace Markdown source —
 * no live styling, so typing stays fast on weak e-ink CPUs. *Preview* renders the same string
 * read-only through the shared [MarkdownRenderer] (the renderer behind the recognized-text viewer
 * and on-page text objects), so a document previews exactly the way the rest of the app draws
 * Markdown.
 *
 * Formatting comes from the toolbar and the matching Ctrl shortcuts; both call the same
 * [MarkdownFormatter] operations, which are plain text edits. A button press and a hand-typed
 * `**` are indistinguishable afterwards.
 *
 * Phase 1 is deliberately storage-free: the editor opens empty, nothing is loaded, nothing is
 * saved. It exists to settle the feel of the surface before it is wired to a document object.
 */
class DocumentEditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DocEditor"

        fun launch(context: Context) {
            context.startActivity(Intent(context, DocumentEditorActivity::class.java))
        }
    }

    private lateinit var editor: MarkdownEditText
    private lateinit var formatBar: View
    private lateinit var previewScroll: ScrollView
    private lateinit var previewText: AppCompatTextView
    private lateinit var btnWrite: AppCompatButton
    private lateinit var btnPreview: AppCompatButton

    private var previewing = false

    /**
     * Manual override for the soft keyboard: `null` follows the hardware (suppressed while a
     * physical keyboard is attached), `true`/`false` force it. Long-press **Write** to flip it —
     * the escape hatch if the hardware detection ever calls it wrong.
     */
    private var softKeyboardOverride: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = buildUi()
        setContentView(root)
        TopGuard.applyInsetPadding(root)

        editor.requestFocus()
        applyKeyboardMode()
    }

    override fun onResume() {
        super.onResume()
        applyKeyboardMode()
    }

    /**
     * Pairing or unpairing a keyboard is a configuration change. The manifest keeps this activity
     * alive through it (losing an unsaved draft to a keyboard connect would be unforgivable), so
     * the soft-keyboard decision is simply re-made here.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyKeyboardMode()
    }

    override fun onPause() {
        super.onPause()
        // BOOX devices do not reliably drop the IME when a screen goes away (see docs/design-system.md).
        hideIme()
    }

    // ── UI (built programmatically to honor the e-ink design system) ─────────────

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val paper = ContextCompat.getColor(this, R.color.paperWhite)
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        val light = ContextCompat.getColor(this, R.color.inkLight)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Content starts below the system bar inset, so the chrome needs its own 1dp rule.
        root.addView(rule(ink))
        root.addView(buildHeader(ink))
        root.addView(rule(ink))
        formatBar = buildFormatBar(ink)
        root.addView(formatBar)
        root.addView(rule(ink))

        editor = MarkdownEditText(this).apply {
            setBackgroundColor(paper)
            setTextColor(ink)
            // Monospace: this is Markdown source, and columns carry meaning here.
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            hint = "Start writing…"
            setHintTextColor(light)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Without this the IME may open a fullscreen extract editor in landscape, which
            // replaces the document with its own bare text box.
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(editor)

        previewText = AppCompatTextView(this).apply {
            setTextColor(ink)
            textSize = 18f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        previewScroll = ScrollView(this).apply {
            isFillViewport = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(previewText)
        }
        root.addView(previewScroll)

        return root
    }

    private fun rule(ink: Int): View = View(this).apply {
        setBackgroundColor(ink)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun buildHeader(ink: Int): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        header.addView(AppCompatTextView(this).apply {
            text = "Document"
            setTextColor(ink)
            textSize = 18f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        btnWrite = modeButton("Write") { setPreviewing(false) }
        btnPreview = modeButton("Preview") { setPreviewing(true) }
        // Escape hatch: force the soft keyboard on or off when the hardware detection is wrong.
        btnWrite.setOnLongClickListener { toggleSoftKeyboardOverride(); true }
        header.addView(btnWrite)
        header.addView(btnPreview)

        header.addView(AppCompatButton(this).apply {
            text = "Done"
            isAllCaps = false
            setTextColor(ink)
            setBackgroundResource(R.drawable.shape_bordered)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { hideIme(); finish() }
        })

        updateModeButtons()
        return header
    }

    /** Segmented mode toggle — the selected half shows its border, the way the app's other
     *  two-way view switches read on e-ink (no tint, no fill). */
    private fun modeButton(label: String, onClick: () -> Unit): AppCompatButton =
        AppCompatButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@DocumentEditorActivity, R.color.inkBlack))
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            // Never take focus: the editor must keep the caret and selection the button acts on.
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            setOnClickListener { onClick() }
        }

    private fun buildFormatBar(ink: Int): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        fun divider() = bar.addView(View(this).apply {
            setBackgroundColor(ink)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            }
        })

        bar.addView(formatButton("H1", "Heading 1  ·  Ctrl+1") { applyBlock(MarkdownFormatter.Block.HEADING, 1) })
        bar.addView(formatButton("H2", "Heading 2  ·  Ctrl+2") { applyBlock(MarkdownFormatter.Block.HEADING, 2) })
        bar.addView(formatButton("H3", "Heading 3  ·  Ctrl+3") { applyBlock(MarkdownFormatter.Block.HEADING, 3) })
        divider()
        bar.addView(formatButton(styled("B", StyleSpan(Typeface.BOLD)), "Bold  ·  Ctrl+B") { wrapInline("**") })
        bar.addView(formatButton(styled("I", StyleSpan(Typeface.ITALIC)), "Italic  ·  Ctrl+I") { wrapInline("*") })
        bar.addView(formatButton(styled("S", StrikethroughSpan()), "Strikethrough  ·  Ctrl+Shift+X") { wrapInline("~~") })
        bar.addView(formatButton("Code", "Inline code  ·  Ctrl+E") { wrapInline("`") })
        divider()
        bar.addView(formatButton("Quote", "Blockquote  ·  Ctrl+Shift+Q") { applyBlock(MarkdownFormatter.Block.QUOTE) })
        bar.addView(formatButton("•", "Bullet list  ·  Ctrl+Shift+8") { applyBlock(MarkdownFormatter.Block.BULLET) })
        bar.addView(formatButton("1.", "Numbered list  ·  Ctrl+Shift+7") { applyBlock(MarkdownFormatter.Block.ORDERED) })
        bar.addView(formatButton("☐", "Task list  ·  Ctrl+Shift+9") { applyBlock(MarkdownFormatter.Block.TASK) })
        divider()
        bar.addView(formatButton("Link", "Link  ·  Ctrl+K") { runFormat(MarkdownFormatter::insertLink) })
        bar.addView(formatButton("—", "Horizontal rule  ·  Ctrl+Shift+−") { runFormat(MarkdownFormatter::insertRule) })

        // The bar always scrolls rather than wrapping — a format palette that reflows under the
        // hand would move the buttons out from under muscle memory.
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(bar)
        }
    }

    private fun formatButton(label: CharSequence, hint: String, onClick: () -> Unit): AppCompatButton =
        AppCompatButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@DocumentEditorActivity, R.color.inkBlack))
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            minWidth = dp(44)
            minHeight = dp(44)
            minimumWidth = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            // Never take focus: the editor must keep the caret and selection the button acts on.
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)
            ).apply { marginEnd = dp(2) }
            setOnClickListener { onClick() }
            // Long-press names the button and teaches its shortcut — the bar carries no labels.
            setOnLongClickListener { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show(); true }
        }

    /** A one-character button label wearing the style it applies (bold `B`, italic `I`, struck `S`). */
    private fun styled(label: String, span: Any): CharSequence =
        SpannableString(label).apply { setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    // ── Write / Preview ───────────────────────────────────────────────────────

    private fun setPreviewing(on: Boolean) {
        if (previewing == on) return
        previewing = on
        if (on) {
            hideIme()
            editor.visibility = View.GONE
            formatBar.visibility = View.GONE
            previewScroll.visibility = View.VISIBLE
            renderPreview()
        } else {
            previewScroll.visibility = View.GONE
            formatBar.visibility = View.VISIBLE
            editor.visibility = View.VISIBLE
            editor.requestFocus()
            applyKeyboardMode()
        }
        updateModeButtons()
        Slog.d(TAG) { "Mode → ${if (on) "preview" else "write"}" }
    }

    private fun updateModeButtons() {
        btnWrite.isSelected = !previewing
        btnPreview.isSelected = previewing
    }

    /** Render the current Markdown through the shared renderer, once, on entering Preview. */
    private fun renderPreview() {
        if (previewScroll.visibility != View.VISIBLE) return
        val width = previewText.width - previewText.paddingLeft - previewText.paddingRight
        if (width <= 0) {
            // First show: no measured width yet, and the rule span needs one.
            previewText.post { renderPreview() }
            return
        }
        val markdown = editor.text?.toString().orEmpty()
        previewText.text = if (markdown.isBlank()) "" else MarkdownRenderer.render(
            MarkdownParser.parse(markdown),
            availableWidthPx = width,
            paint = previewText.paint,
            density = resources.displayMetrics.density,
            blockGapPx = dp(8),
        )
        previewScroll.scrollTo(0, 0)
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private fun wrapInline(marker: String) = runFormat { buf, s, t ->
        MarkdownFormatter.toggleInline(buf, s, t, marker)
    }

    private fun applyBlock(kind: MarkdownFormatter.Block, level: Int = 1) = runFormat { buf, s, t ->
        MarkdownFormatter.toggleBlock(buf, s, t, kind, level)
    }

    /** Run one formatter operation over the current selection and re-install the caret it returns. */
    private fun runFormat(op: (TextBuffer, Int, Int) -> MarkdownFormatter.Selection) {
        if (previewing) return
        val text = editor.text ?: return
        val start = editor.selectionStart.coerceIn(0, text.length)
        val end = editor.selectionEnd.coerceIn(0, text.length)
        val selection = op(EditableBuffer(text), start, end)
        editor.setSelection(
            selection.start.coerceIn(0, text.length),
            selection.end.coerceIn(0, text.length),
        )
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && handleShortcut(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Ctrl shortcuts mirroring the format bar. Only the claimed keys are consumed — Ctrl+Z/Y/A/C/V/X
     * must fall through to the EditText, which already implements undo, redo, select-all and clipboard.
     *
     * No combination has to be avoided for the IME's sake: with a physical keyboard attached the
     * editor refuses an input connection entirely (see [applyKeyboardMode]), so no input method sits
     * upstream in the key path to claim a shortcut before the app sees it — including the Ctrl+Shift
     * chords that otherwise cycle soft keyboards.
     */
    private fun handleShortcut(event: KeyEvent): Boolean {
        val shift = event.isShiftPressed
        // Preview is read-only; only the mode toggle stays live there.
        if (previewing) {
            if (event.keyCode == KeyEvent.KEYCODE_P && !shift) { setPreviewing(false); return true }
            return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_B -> if (!shift) { wrapInline("**"); return true }
            KeyEvent.KEYCODE_I -> if (!shift) { wrapInline("*"); return true }
            KeyEvent.KEYCODE_X -> if (shift) { wrapInline("~~"); return true }
            KeyEvent.KEYCODE_E -> if (!shift) { wrapInline("`"); return true }
            KeyEvent.KEYCODE_K -> if (!shift) { runFormat(MarkdownFormatter::insertLink); return true }
            KeyEvent.KEYCODE_P -> if (!shift) { setPreviewing(true); return true }
            KeyEvent.KEYCODE_Q -> if (shift) { applyBlock(MarkdownFormatter.Block.QUOTE); return true }
            KeyEvent.KEYCODE_MINUS -> if (shift) { runFormat(MarkdownFormatter::insertRule); return true }
            KeyEvent.KEYCODE_0 -> if (!shift) { applyBlock(MarkdownFormatter.Block.PARAGRAPH); return true }
            KeyEvent.KEYCODE_1 -> if (!shift) { applyBlock(MarkdownFormatter.Block.HEADING, 1); return true }
            KeyEvent.KEYCODE_2 -> if (!shift) { applyBlock(MarkdownFormatter.Block.HEADING, 2); return true }
            KeyEvent.KEYCODE_3 -> if (!shift) { applyBlock(MarkdownFormatter.Block.HEADING, 3); return true }
            KeyEvent.KEYCODE_4 -> if (!shift) { applyBlock(MarkdownFormatter.Block.HEADING, 4); return true }
            KeyEvent.KEYCODE_5 -> if (!shift) { applyBlock(MarkdownFormatter.Block.HEADING, 5); return true }
            KeyEvent.KEYCODE_6 -> if (!shift) { applyBlock(MarkdownFormatter.Block.HEADING, 6); return true }
            KeyEvent.KEYCODE_7 -> if (shift) { applyBlock(MarkdownFormatter.Block.ORDERED); return true }
            KeyEvent.KEYCODE_8 -> if (shift) { applyBlock(MarkdownFormatter.Block.BULLET); return true }
            KeyEvent.KEYCODE_9 -> if (shift) { applyBlock(MarkdownFormatter.Block.TASK); return true }
        }
        return false
    }

    /**
     * Decide whether the soft keyboard participates at all.
     *
     * With a physical keyboard attached it is cut out completely: no InputConnection is offered, so
     * the IME gets no session and cannot intercept hardware keys before the app sees them. That is
     * what stops a soft keyboard from swallowing Ctrl shortcuts (BOOX's input-method switcher being
     * the offender) or popping open mid-sentence. Typing is unaffected — hardware key events go
     * straight to the editor's key listener.
     */
    private fun applyKeyboardMode() {
        val wantSoftKeyboard = softKeyboardOverride ?: !physicalKeyboardAttached()
        editor.suppressImeSession = !wantSoftKeyboard
        editor.showSoftInputOnFocus = wantSoftKeyboard
        // Rebuild (or tear down) the input connection so the change takes effect immediately.
        imm()?.restartInput(editor)
        if (wantSoftKeyboard && !previewing) showIme() else hideIme()
        Slog.d(TAG) { "Soft keyboard ${if (wantSoftKeyboard) "on" else "suppressed"}" }
    }

    /** True when a real, alphabetic keyboard is attached and usable. */
    private fun physicalKeyboardAttached(): Boolean {
        val config = resources.configuration
        val exposed = config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        if (!exposed) return false
        // Confirm against the device list too: the soft keyboard reports as a virtual device, so
        // requiring a non-virtual alphabetic one keeps a false positive from locking out typing.
        return InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id)
            device != null && !device.isVirtual &&
                device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        }
    }

    private fun toggleSoftKeyboardOverride() {
        val current = softKeyboardOverride ?: !physicalKeyboardAttached()
        softKeyboardOverride = !current
        applyKeyboardMode()
        Toast.makeText(
            this,
            if (softKeyboardOverride == true) "Software keyboard: on" else "Software keyboard: off",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun imm(): InputMethodManager? =
        getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun showIme() {
        editor.post { imm()?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideIme() {
        imm()?.hideSoftInputFromWindow(editor.windowToken, 0)
    }
}

/**
 * [AppCompatEditText] that can refuse the IME entirely.
 *
 * When [suppressImeSession] is set, [onCreateInputConnection] returns null: the framework never
 * binds an input method to this field, so no soft keyboard appears and — crucially — no IME sits
 * upstream of the app in the hardware key path, claiming shortcuts on its way past. The editor
 * still handles physical keys through its own key listener.
 */
private class MarkdownEditText(context: Context) : AppCompatEditText(context) {

    var suppressImeSession = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (suppressImeSession) null else super.onCreateInputConnection(outAttrs)
}
