package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.markdown.EditableBuffer
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownFormatter
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownParser
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownRenderer
import com.symmetricalpalmtree.notesproutsn.markdown.TextBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The extension-owned document editor (arc 19 / M4) — Write and Preview, the format bar, and an
 * autosave that crosses a process boundary.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be — the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell leaves `callingPackage` null and is refused.
 *
 * **The screen opens no file.** Every byte of this document is pulled from, and pushed back to, the
 * host through the callback binder parked in [EditorSession] — og's "the host owns every `.soil`
 * read and write", now enforced by the process boundary. The buffer here is the only copy that is
 * not the host's, which is why the teardown paths below matter as much as the typing does.
 *
 * **Document text is never logged**, on any path, in any build: lengths, counts and class names
 * only. The debug automation hook carries whole documents and logs nothing either.
 *
 * **No paper, no handoff.** There is no g-paper surface here, so there is no EPD pipeline to reclaim
 * on the way in — answered on the Nomad at M3: with this screen up, the pen draws nothing behind it
 * and ink flows normally on return.
 *
 * **The IME is never hidden from this screen, deliberately.** On Ratta hardware keys are translated
 * by the IME and delivered *only while it is shown* — hiding it drops every key event, Ctrl chords
 * included. So nothing here calls an IME-hide API; the window takes `adjustResize` and lets the
 * keyboard have its room.
 *
 * **M5 added the editor's tools** — find and replace, word count, reflow, text size, and the caret
 * memory that opens a document where it was left. All of them are og's semantics; the only thing
 * that is different here is where the small state lives: not `SharedPreferences` but the host's
 * extension store, through [EditorPrefs], because an extension writes nothing to disk itself.
 *
 * Not built at M5, rather than built and hidden: the page arrows and source strip (M6), the scope
 * toggle (M7) and proofread (M10). Reflow has no button for the same reason — its home is M6's
 * source strip, so until then `Ctrl+Shift+F` is the whole of it.
 */
class DocumentEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentEditorBinding

    /** Timers, threads and the binder; every decision inside it is [AutosaveGovernor]'s. The caret
     *  rides along with every save trigger — see [DocumentSaver]'s `caretSink`. */
    private val saver = DocumentSaver(
        snapshot = { currentText() },
        caretSnapshot = { if (this::binding.isInitialized) binding.editor.selectionEnd else 0 },
        caretSink = { key, caret -> EditorPrefs.rememberCaretAsync(key, caret) },
    )

    private var overflow: FormatBarOverflow? = null
    private var lastBarWidth = 0

    /** The find bar's own wiring — its query, its count, and the five controls that act on the
     *  editor's selection. Built with the rest of the chrome. */
    private lateinit var find: FindReplaceBar

    /** Reflow, list renumbering, word count and the caret scroll — the tools that take the buffer
     *  and the selection and nothing else. */
    private lateinit var tools: EditorTools

    /** The size in force, which both surfaces are drawn at. Loaded from the store, not the XML. */
    private var textSizeSp = EditorPrefs.DEFAULT_TEXT_SIZE

    private var previewing = false

    /** Set the moment a leave path starts, so a second Done cannot start a second one. */
    private var leaving = false

    /** Where a plain newline was just typed — read (and cleared) in `afterTextChanged`, which is
     *  where the buffer may be edited. Clearing it before use is also the re-entrancy guard: the
     *  list edit re-enters the watcher, and the re-entry finds nothing to do. */
    private var newlineAt = -1

    /** True while this screen is writing to its own buffer, so a programmatic load is not mistaken
     *  for the writer typing. */
    private var applyingEdit = false

    /** Non-null once the load has landed — and until then no save can happen at all, which is the
     *  simplest possible guarantee that nothing is written to a target this screen never learned. */
    private val targetKey: String? get() = saver.pageKey

    private var restoredText: String? = null

    /** The bundle's caret, or −1 for "there was no bundle". The distinction matters: 0 is a real
     *  caret (the top of the document) and would otherwise beat the remembered one on every
     *  recreation. `onSaveInstanceState` always writes the key, so a bundle implies a value. */
    private var restoredCaret = NO_CARET
    private var restoredPreviewing = false

    /** This instance's session hooks, held by identity so `onDestroy` can only clear its own. */
    private val beginListener = EditorSession.BeginListener { onHostBegan() }
    private val flushHook = EditorSession.FlushHook { unsavedSnapshotBlocking() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivityDocumentEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // followIme: this screen types, so the layout gives the software keyboard its room back
        // rather than letting it cover the lines being written.
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        restoredText = savedInstanceState?.getString(STATE_TEXT)
        restoredCaret = savedInstanceState?.getInt(STATE_CARET, NO_CARET) ?: NO_CARET
        restoredPreviewing = savedInstanceState?.getBoolean(STATE_PREVIEWING) == true

        buildChrome()
        installWatcher()

        // Registered by identity so a recreated screen's hooks — installed in ITS onCreate, which
        // runs BEFORE this instance's onDestroy — are never cleared by the instance going away.
        EditorSession.beginListener = beginListener
        EditorSession.flushHook = flushHook
        if (BuildConfig.DEBUG) EditorAutomation.peer = automationPeer

        // Back is the Close path, not a discard: writing is not cancellable here, so every way out
        // of this screen saves first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave(Activity.RESULT_CANCELED)
        })

        load()
    }

    override fun onPause() {
        super.onPause()
        // A refused caller never inflated anything and is already finishing.
        if (!this::binding.isInitialized) return
        // Leaving the screen is a save point: there is no Cancel to honour, and a backgrounded
        // extension process is the first thing the system reclaims.
        saver.saveNow()
    }

    /**
     * The buffer rides the bundle only while it is small enough to survive the trip: a `Bundle` goes
     * home through a Binder transaction, and one over the ~1 MB budget takes the whole process down
     * with `TransactionTooLargeException`. Above the cap the unsaved tail is at worst one debounce
     * — and the park and the teardown flush are both still standing behind it.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PREVIEWING, previewing)
        if (!this::binding.isInitialized) return
        val text = currentText()
        outState.putInt(STATE_CARET, binding.editor.selectionEnd)
        if (saver.isDirty(text) && text.length <= MAX_BUNDLED_CHARS) {
            outState.putString(STATE_TEXT, text)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only ever clear what this instance installed — see the registration note in onCreate.
        if (EditorSession.beginListener === beginListener) EditorSession.beginListener = null
        if (EditorSession.flushHook === flushHook) EditorSession.flushHook = null
        if (EditorAutomation.peer === automationPeer) EditorAutomation.peer = null
        saver.cancelTimers()
    }

    // ── Chrome ────────────────────────────────────────────────────────────────

    private fun buildChrome() {
        binding.btnClose.setOnClickListener { leave(Activity.RESULT_CANCELED) }
        binding.btnDone.setOnClickListener { leave(Activity.RESULT_OK) }
        binding.btnWrite.setOnClickListener { setPreviewing(false) }
        binding.btnPreview.setOnClickListener { setPreviewing(true) }
        // Live in Preview too: reading comfort is most of what a text size is for.
        binding.btnTextSize.setOnClickListener { promptTextSize() }
        for (button in listOf(
            binding.btnClose, binding.btnDone, binding.btnTextSize, binding.btnWrite, binding.btnPreview,
        )) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }
        updateModeButtons()

        tools = EditorTools(
            context = this,
            binding = binding,
            isPreviewing = { previewing },
            leavePreview = { setPreviewing(false) },
            onEdited = { saver.saveNow() },
        )
        find = FindReplaceBar(
            context = this,
            binding = binding,
            isPreviewing = { previewing },
            leavePreview = { setPreviewing(false) },
            keepCaretVisible = { tools.keepCaretVisible() },
            onReplacedAll = { saver.saveNow() },
        )
        find.install()

        // A shorter editing surface can leave the caret below the fold, which is precisely what the
        // keyboard appearing does. Only a real height change is worth reacting to.
        binding.editor.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) binding.editor.post { tools.keepCaretVisible() }
        }

        val controls = FormatBar.build(
            bar = binding.formatBar,
            onTool = ::runTool,
            onToolUsed = { overflow?.close() },
            onOverflow = { overflow?.toggle() },
        )
        val manager = FormatBarOverflow(
            binding.formatBar, binding.overflowPanel, controls.dividerOverflow, controls.btnOverflow,
        )
        overflow = manager
        // Work out what fits once the bar has a width, and again whenever that width changes.
        // Guarded on the width itself: the listener also fires for layout passes that change
        // nothing, and a recalc rebuilds the bar, which would loop.
        binding.formatBar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastBarWidth) {
                lastBarWidth = width
                binding.formatBar.post { manager.recalc() }
            }
        }
    }

    /**
     * A tap anywhere that is not the bar or the panel puts the overflow away — placing the caret in
     * the text should not have to be preceded by dismissing a menu. Deliberately **not** consumed:
     * the touch is the writer choosing where to type, and it must still land.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!this::binding.isInitialized) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && overflow?.isOpen() == true &&
            !isInside(binding.formatBar, event) && !isInside(binding.overflowPanel, event)
        ) {
            overflow?.close()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInside(view: View, event: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val xy = IntArray(2).also { view.getLocationOnScreen(it) }
        return event.rawX >= xy[0] && event.rawX <= xy[0] + view.width &&
            event.rawY >= xy[1] && event.rawY <= xy[1] + view.height
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Ask the host for the current target and pull its text across, chunk by chunk. Both halves are
     * one IO hop: the state names how many `readChunk` calls serve the window it just parked, so
     * they must not be separated by anything that could let a new window be loaded between them —
     * at M4 nothing can, and at M6 the flip guards make that explicit.
     *
     * The editor's own state ([EditorPrefs]) is read in the same hop — the store is a blocking
     * Binder call like the rest, and both are wanted before the first frame with text in it.
     */
    private fun load() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val host = EditorSession.host ?: throw IllegalStateException("no showing")
                    val state = host.current()
                    val text = buildString {
                        for (i in 0 until state.textChunks) append(host.readChunk(i))
                    }
                    // Only worth asking when the bundle has no answer — a recreated screen's own
                    // caret always wins, so a lookup then would be read and thrown away.
                    val caret = if (restoredCaret == NO_CARET) EditorPrefs.caret(state.pageKey) else 0
                    Loaded(state, text, EditorPrefs.textSize(), caret)
                } catch (e: Exception) {
                    // The class name only: an exception's message from either side of this seam
                    // could carry a path, and its content certainly could.
                    Slog.d(TAG) { "load failed: ${e.javaClass.simpleName}" }
                    null
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                showLoadFailure()
                return@launch
            }
            adopt(loaded)
        }
    }

    /** Take the loaded target on: its key is the only one this screen will ever save to. */
    private fun adopt(loaded: Loaded) {
        val state = loaded.state
        saver.pageKey = state.pageKey
        saver.markLoaded(loaded.text)

        binding.title.text = state.title.ifEmpty { getString(R.string.document_title) }
        // −1 is the notebook scope (M7) — not a page, so nothing to number.
        binding.pageIndicator.text = if (state.pageIndex >= 0) {
            getString(R.string.document_page_indicator, state.pageIndex + 1, state.pageCount)
        } else ""

        // Both surfaces are sized before the text lands, so nothing is laid out twice. Not persisted
        // — this is the value that was just read back.
        applyTextSize(loaded.textSizeSp, persist = false)

        // A recreated editor prefers its own saved buffer over the pull, and treats it as UNSAVED:
        // `savedText` stays what the host handed over, so the first debounce writes the difference.
        val restored = restoredText
        val opening = restored ?: loaded.text
        applyingEdit = true
        binding.editor.setText(opening)
        applyingEdit = false
        // Open where the writer left off — the bundle's caret on a recreation, the remembered one
        // otherwise. Falling back to the TOP rather than the end: a document is usually read before
        // it is added to, and landing at the bottom hides everything written.
        val caret = if (restoredCaret != NO_CARET) restoredCaret else loaded.caret
        binding.editor.setSelection(caret.coerceIn(0, opening.length))
        restoredText = null

        if (restoredPreviewing) setPreviewing(true)
        if (saver.isDirty(opening)) saver.schedule()
        if (!previewing) binding.editor.requestFocus()
        Slog.d(TAG) { "loaded ${loaded.text.length} chars in ${state.textChunks} chunk(s)" }
    }

    /**
     * Nothing could be read. The buffer stays empty and un-typeable and the writing chrome goes:
     * there is no document to format, and words typed here could never be saved (no target key was
     * ever learned, so [DocumentSaver] refuses every trigger). The line says so where the text would
     * have been.
     */
    private fun showLoadFailure() {
        binding.editor.hint = getString(R.string.document_load_failed)
        binding.editor.isFocusable = false
        binding.editor.isFocusableInTouchMode = false
        binding.writingChrome.visibility = View.GONE
        binding.btnWrite.visibility = View.GONE
        binding.btnPreview.visibility = View.GONE
    }

    /** What one load brought back — the state, the reassembled text and the editor's own stored
     *  state, all materialised off Main. */
    private class Loaded(
        val state: DocumentPageState,
        val text: String,
        val textSizeSp: Float,
        val caret: Int,
    )

    // ── Write / Preview ───────────────────────────────────────────────────────

    private fun setPreviewing(on: Boolean) {
        if (previewing == on) return
        previewing = on
        if (on) {
            // Switching to reading is a natural save point, and the writing chrome goes with it.
            saver.saveNow()
            overflow?.close()
            binding.editor.visibility = View.GONE
            binding.writingChrome.visibility = View.GONE
            binding.previewScroll.visibility = View.VISIBLE
            renderPreview()
        } else {
            binding.previewScroll.visibility = View.GONE
            binding.writingChrome.visibility = View.VISIBLE
            binding.editor.visibility = View.VISIBLE
            binding.editor.requestFocus()
        }
        updateModeButtons()
        Slog.d(TAG) { "mode → ${if (on) "preview" else "write"}" }
    }

    /** The armed mode reads as armed: `bg_toolbar_button`'s selected state is a 1dp inkBlack box,
     *  the same way every armed tool in this app shows itself. */
    private fun updateModeButtons() {
        binding.btnWrite.isSelected = !previewing
        binding.btnPreview.isSelected = previewing
    }

    /** Render the current Markdown through the shared engine, once, on entering Preview. */
    private fun renderPreview() {
        if (binding.previewScroll.visibility != View.VISIBLE) return
        val view = binding.previewText
        val width = view.width - view.paddingLeft - view.paddingRight
        if (width <= 0) {
            // First show: no measured width yet, and the horizontal rule's span needs one.
            view.post { renderPreview() }
            return
        }
        val markdown = currentText()
        view.text = if (markdown.isBlank()) "" else MarkdownRenderer.render(
            MarkdownParser.parse(markdown),
            availableWidthPx = width,
            paint = view.paint,
            density = resources.displayMetrics.density,
            blockGapPx = (8f * resources.displayMetrics.density).toInt(),
        )
        binding.previewScroll.scrollTo(0, 0)
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private fun runTool(tool: FormatTool) {
        when (tool) {
            FormatTool.H1 -> applyBlock(MarkdownFormatter.Block.HEADING, 1)
            FormatTool.H2 -> applyBlock(MarkdownFormatter.Block.HEADING, 2)
            FormatTool.H3 -> applyBlock(MarkdownFormatter.Block.HEADING, 3)
            FormatTool.BOLD -> wrapInline("**")
            FormatTool.ITALIC -> wrapInline("*")
            FormatTool.STRIKETHROUGH -> wrapInline("~~")
            FormatTool.CODE -> wrapInline("`")
            FormatTool.QUOTE -> applyBlock(MarkdownFormatter.Block.QUOTE)
            FormatTool.BULLET -> applyBlock(MarkdownFormatter.Block.BULLET)
            FormatTool.ORDERED -> applyBlock(MarkdownFormatter.Block.ORDERED)
            FormatTool.TASK -> applyBlock(MarkdownFormatter.Block.TASK)
            FormatTool.LINK -> runFormat(MarkdownFormatter::insertLink)
            FormatTool.IMAGE -> runFormat(MarkdownFormatter::insertImage)
            FormatTool.RULE -> runFormat(MarkdownFormatter::insertRule)
            // Not formatter operations: these two act on the screen, not on the buffer.
            FormatTool.SEARCH -> find.open()
            FormatTool.WORD_COUNT -> tools.showWordCount()
        }
    }

    private fun wrapInline(marker: String) = runFormat { buf, s, t ->
        MarkdownFormatter.toggleInline(buf, s, t, marker)
    }

    private fun applyBlock(kind: MarkdownFormatter.Block, level: Int = 1) = runFormat { buf, s, t ->
        MarkdownFormatter.toggleBlock(buf, s, t, kind, level)
    }

    /** Run one formatter operation over the current selection and re-install the caret it returns. */
    private fun runFormat(op: (TextBuffer, Int, Int) -> MarkdownFormatter.Selection) {
        if (previewing) return
        val text = binding.editor.text ?: return
        val start = binding.editor.selectionStart.coerceIn(0, text.length)
        val end = binding.editor.selectionEnd.coerceIn(0, text.length)
        val selection = op(EditableBuffer(text), start, end)
        binding.editor.setSelection(
            selection.start.coerceIn(0, text.length),
            selection.end.coerceIn(0, text.length),
        )
    }

    // ── Typing ────────────────────────────────────────────────────────────────

    private fun installWatcher() {
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Note a plain newline for afterTextChanged, which is where the buffer may be
                // edited. Watching the text rather than the Enter key covers both keyboards: a soft
                // keyboard commits "\n" through the input connection and may send no key event.
                newlineAt = if (before == 0 && count == 1 && s?.getOrNull(start) == '\n') start else -1
            }

            override fun afterTextChanged(s: Editable?) {
                // Read-and-clear before using it: the list edit below re-enters this callback, and
                // the re-entry must find nothing to do rather than continue the list twice.
                val at = newlineAt
                newlineAt = -1
                if (applyingEdit) return
                if (at >= 0 && s != null) continueListAt(s, at)
                saver.schedule()
            }
        })
    }

    /** og's list continuation, through the buffer rather than key events. */
    private fun continueListAt(text: Editable, newlineIndex: Int) {
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
        tools.renumberLists(text)
    }

    // ── Text size ─────────────────────────────────────────────────────────────

    /** Pick a text size. The tick marks the one in force; the choice outlives the showing. */
    private fun promptTextSize() {
        overflow?.close()
        val sheet = ActionSheetDialog(this).title(getString(R.string.text_size_title))
        for ((labelRes, size) in EditorPrefs.SIZES) {
            val label = getString(labelRes)
            sheet.addAction(
                null,
                if (size == textSizeSp) getString(R.string.text_size_current, label) else label,
            ) { applyTextSize(size) }
        }
        sheet.show()
    }

    /** Draw both surfaces at [sp]. [persist] is false only at load, where the value came *from* the
     *  store and writing it back would be a Binder round trip that changes nothing. */
    private fun applyTextSize(sp: Float, persist: Boolean = true) {
        textSizeSp = sp
        binding.editor.textSize = sp
        binding.previewText.textSize = sp + EditorPrefs.PREVIEW_BUMP
        if (persist) lifecycleScope.launch(Dispatchers.IO) { EditorPrefs.saveTextSize(sp) }
        // The renderer bakes sizes into spans from the paint it was handed, so the preview has to be
        // rebuilt rather than just re-measured.
        if (previewing) renderPreview()
        Slog.d(TAG) { "text size → ${sp}sp" }
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (this::binding.isInitialized &&
            event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && handleShortcut(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The format bar's chords plus og's chord-only four (Ctrl+P mode toggle, Ctrl+0 paragraph,
     * Ctrl+4–6 the headings the bar has no room for). Ctrl+Z/Y/A/C/V/X must fall through to the EditText,
     * which already implements undo, redo, select-all and the clipboard.
     *
     * On Ratta the IME stays connected (hardware keys arrive only through it), so an input method
     * sits upstream in the key path and may claim a chord before this sees it — which is a reason to
     * keep the set small, not a reason to hide the keyboard.
     */
    private fun handleShortcut(event: KeyEvent): Boolean {
        val shift = event.isShiftPressed
        // Preview is read-only; only the mode toggle stays live there (og's rule).
        if (previewing) {
            if (event.keyCode == KeyEvent.KEYCODE_P && !shift) { setPreviewing(false); return true }
            return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_P -> if (!shift) { setPreviewing(true); return true }
            // Paragraph and H4–H6 are chord-only, as in og: the bar stops at H3, the grammar does not.
            KeyEvent.KEYCODE_0 -> if (!shift) { overflow?.close(); applyBlock(MarkdownFormatter.Block.PARAGRAPH); return true }
            KeyEvent.KEYCODE_4 -> if (!shift) { overflow?.close(); applyBlock(MarkdownFormatter.Block.HEADING, 4); return true }
            KeyEvent.KEYCODE_5 -> if (!shift) { overflow?.close(); applyBlock(MarkdownFormatter.Block.HEADING, 5); return true }
            KeyEvent.KEYCODE_6 -> if (!shift) { overflow?.close(); applyBlock(MarkdownFormatter.Block.HEADING, 6); return true }
            KeyEvent.KEYCODE_1 -> if (!shift) return tool(FormatTool.H1)
            KeyEvent.KEYCODE_2 -> if (!shift) return tool(FormatTool.H2)
            KeyEvent.KEYCODE_3 -> if (!shift) return tool(FormatTool.H3)
            KeyEvent.KEYCODE_B -> if (!shift) return tool(FormatTool.BOLD)
            KeyEvent.KEYCODE_I -> if (!shift) return tool(FormatTool.ITALIC)
            KeyEvent.KEYCODE_X -> if (shift) return tool(FormatTool.STRIKETHROUGH)
            KeyEvent.KEYCODE_E -> if (!shift) return tool(FormatTool.CODE)
            KeyEvent.KEYCODE_Q -> if (shift) return tool(FormatTool.QUOTE)
            KeyEvent.KEYCODE_8 -> if (shift) return tool(FormatTool.BULLET)
            KeyEvent.KEYCODE_7 -> if (shift) return tool(FormatTool.ORDERED)
            KeyEvent.KEYCODE_9 -> if (shift) return tool(FormatTool.TASK)
            KeyEvent.KEYCODE_K -> return tool(if (shift) FormatTool.IMAGE else FormatTool.LINK)
            KeyEvent.KEYCODE_MINUS -> if (shift) return tool(FormatTool.RULE)
            // Find, and its shifted sibling: reflow has no button until M6, so this is its only
            // entry point besides the debug hook.
            KeyEvent.KEYCODE_F -> {
                overflow?.close()
                if (shift) tools.reflow() else find.open()
                return true
            }
        }
        return false
    }

    /** Run a tool from a chord and claim the key. */
    private fun tool(tool: FormatTool): Boolean {
        overflow?.close()
        runTool(tool)
        return true
    }

    // ── Saving, and the two ways a showing comes apart ────────────────────────

    private fun currentText(): String = binding.editor.text?.toString().orEmpty()

    /** Done and Close both save; neither discards. There is no cancel here — writing is not
     *  cancellable, and an X would promise a way out that does not exist. */
    private fun leave(resultCode: Int) {
        if (leaving) return
        leaving = true
        saver.flushAndThen {
            setResult(resultCode)
            finish()
        }
    }

    /**
     * The host restarted underneath this screen and lent new binders. Ask it what it is showing now
     * and flush only if that is still this editor's target — the read comes first and the write
     * after it, because the answer is what decides whether there is a write at all.
     *
     * Arrives on a **Binder thread**; everything it touches lives on Main.
     */
    private fun onHostBegan() {
        runOnUiThread {
            if (isFinishing || isDestroyed || targetKey == null) return@runOnUiThread
            lifecycleScope.launch {
                val key = withContext(Dispatchers.IO) {
                    try {
                        EditorSession.host?.current()?.pageKey
                    } catch (e: Exception) {
                        Slog.d(TAG) { "reconnect state failed: ${e.javaClass.simpleName}" }
                        null
                    }
                }
                if (key == null || isFinishing || isDestroyed) return@launch
                Slog.d(TAG) { "host reconnected (target ${if (key == targetKey) "matches" else "differs"})" }
                saver.flushOnReconnect(key)
            }
        }
    }

    /**
     * The service's teardown backstop, called on a **Binder thread**: hop to Main, read the buffer,
     * hop back. Blocking there is allowed and bounded — a second is far longer than a `post` needs,
     * and if Main is wedged the park and the host's own flush are what is left.
     */
    private fun unsavedSnapshotBlocking(): Pair<String, String>? {
        val out = AtomicReference<Pair<String, String>?>(null)
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                if (this::binding.isInitialized) out.set(saver.unsavedSnapshot())
            } finally {
                latch.countDown()
            }
        }
        latch.await(FLUSH_HOP_MS, TimeUnit.MILLISECONDS)
        return out.get()
    }

    // ── Debug automation (release never assigns this) ─────────────────────────

    private val automationPeer = object : AutomationPeer {
        override fun setText(text: String, append: Boolean) {
            val editable = binding.editor.text ?: return
            if (append) {
                val at = binding.editor.selectionEnd.coerceIn(0, editable.length)
                editable.insert(at, text)
                // No setSelection afterwards: the insert lands at the cursor and the cursor span
                // follows it — and the list-continuation watcher may have just placed the caret
                // after a marker it wrote. Forcing `at + length` here clobbered exactly that
                // (found re-driving the M4 walk's list FAIL by hand).
            } else {
                binding.editor.setText(text)
                binding.editor.setSelection(text.length)
            }
        }

        override fun text(): String = currentText()
        override fun caret(): Int = binding.editor.selectionEnd
        override fun setCaret(position: Int) {
            binding.editor.setSelection(position.coerceIn(0, currentText().length))
        }

        override fun isPreviewing(): Boolean = previewing
        override fun setPreviewing(on: Boolean) = this@DocumentEditorActivity.setPreviewing(on)
        override fun isDirty(): Boolean = saver.isDirty(currentText())
        override fun pageLabel(): String = binding.pageIndicator.text.toString()
        override fun saveNow() = saver.saveNow()
        override fun done() = leave(Activity.RESULT_OK)
        override fun close() = leave(Activity.RESULT_CANCELED)

        override fun findOpen(query: String): Int {
            find.open()
            find.setQuery(query)
            return find.matchCount()
        }

        override fun findStep(backwards: Boolean): String {
            find.step(backwards)
            return find.countLabel()
        }

        override fun findReplaceAll(replacement: String): Int {
            find.setReplacement(replacement)
            return find.replaceAll()
        }

        override fun findClose() = find.close()
        override fun reflow() = tools.reflow()
        override fun wordCount(): Pair<Int, Int> = tools.wordCount()

        override fun undo() {
            binding.editor.onTextContextMenuItem(android.R.id.undo)
        }

        override fun textSize(): Float = textSizeSp
        override fun setTextSize(sp: Float) = applyTextSize(sp)
    }

    private companion object {
        const val TAG = "DocumentEditor"

        /** "The bundle had no caret" — see [restoredCaret]. */
        const val NO_CARET = -1

        const val STATE_TEXT = "doc_text"
        const val STATE_CARET = "doc_caret"
        const val STATE_PREVIEWING = "doc_previewing"

        /** The buffer rides `onSaveInstanceState` only below this: a bundle is a Binder transaction
         *  and the budget is ~1 MB. 256k chars is half of it as UTF-16, with room for the rest. */
        const val MAX_BUNDLED_CHARS = 256_000

        /** How long the teardown backstop waits for Main to hand over the buffer. */
        const val FLUSH_HOP_MS = 1_000L
    }
}
