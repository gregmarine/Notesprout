package com.notesprout.android

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.DocumentPreferences
import com.notesprout.android.core.IndexGuard
import com.notesprout.android.core.Slog
import com.notesprout.android.core.TopGuard
import com.notesprout.android.core.isRattaDevice
import com.notesprout.android.core.markdown.DocumentDraft
import com.notesprout.android.core.markdown.EditableBuffer
import com.notesprout.android.core.markdown.MarkdownFormatter
import com.notesprout.android.core.markdown.MarkdownParser
import com.notesprout.android.core.markdown.MarkdownReflow
import com.notesprout.android.core.markdown.MarkdownRenderer
import com.notesprout.android.core.markdown.TextBuffer
import com.notesprout.android.notebook.ToolbarOverflowManager

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
 * **The page is the draft; this is the result.** The text arrives once, seeded from the page's
 * recognized handwriting, and from then on it is the user's — recognition never overwrites it. The
 * source strip offers the page's text back on request, and nothing else does.
 *
 * The editor **never touches the `.soil`**. It holds text and asks [DocumentTransfer.host] — the
 * notebook, which already owns the open connection — to read and write on its behalf. Text is
 * autosaved (there is no Cancel: writing on paper is not cancellable) on an idle timer, on the
 * Write↔Preview switch, on pause, and on Done.
 */
class DocumentEditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DocEditor"

        /** The notebook this document belongs to — the header's title. */
        const val EXTRA_NOTEBOOK_NAME = "notebook_name"

        private const val STATE_TEXT = "doc_text"
        private const val STATE_PREVIEWING = "doc_previewing"
        private const val STATE_CARET = "doc_caret"
        private const val STATE_NOTEBOOK = "doc_notebook"

        /** Pen-idle window before the text is written, matching RTR's own debounce. */
        private const val AUTOSAVE_DELAY_MS = 2000L

        /**
         * How long a page flip may take before the "Reading this page…" popup appears. A page with a
         * document loads in well under this, so the popup shows only when the page actually has to be
         * read — an instant flip must not flash a dialog on e-ink.
         */
        private const val READING_POPUP_DELAY_MS = 350L

        fun intent(context: Context, notebookName: String): Intent =
            Intent(context, DocumentEditorActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
    }

    private lateinit var editor: MarkdownEditText
    private lateinit var formatBar: LinearLayout
    private lateinit var previewScroll: ScrollView
    private lateinit var previewText: AppCompatTextView
    private lateinit var btnWrite: AppCompatImageButton
    private lateinit var btnPreview: AppCompatImageButton
    private lateinit var sourceStrip: View
    private lateinit var sourceText: AppCompatTextView
    private lateinit var titleText: AppCompatTextView
    private lateinit var pageText: AppCompatTextView
    private lateinit var btnFlipPrev: AppCompatImageButton
    private lateinit var btnFlipNext: AppCompatImageButton
    private lateinit var btnScope: AppCompatImageButton
    private lateinit var btnBringIn: AppCompatButton

    /** Source strip + rule + format bar + overflow panel: the writing chrome, shown and hidden together. */
    private lateinit var writingChrome: View

    // ── Format-bar overflow ───────────────────────────────────────────────────
    // The same ToolbarOverflowManager the notebook toolbar uses: it moves the trailing tools that do
    // not fit into a bordered panel below the bar, moving the actual views so their click listeners
    // and long-press hints come along untouched.
    private lateinit var overflowMenu: LinearLayout
    private lateinit var btnOverflow: View
    private lateinit var dividerOverflow: View
    private var overflow: ToolbarOverflowManager? = null

    /** Last bar width a recalc ran for — the layout listener fires for reasons other than resizing. */
    private var lastBarWidth = 0

    /** Editing-surface text size in sp, remembered across sessions ([DocumentPreferences]). */
    private var textSizeSp = DocumentPreferences.DEFAULT_TEXT_SIZE

    private var previewing = false

    /** The notebook's name — the header title. */
    private var notebookName: String = ""

    /** This page's place in the notebook ("4 / 12"), shown between the flip arrows. */
    private var pageLabel: String = ""

    /** Whether the notebook has a page on either side of this one. */
    private var hasPrev = false
    private var hasNext = false

    /** True while the page has been written on since this text was drafted from it. */
    private var pageChanged = false

    /** True once we know this text came from the page — at open, or after a "bring in". */
    private var drafted = false

    /**
     * True while the buffer holds the **notebook document** — the whole notebook's merged final
     * draft — rather than one page's. Toggled by the header's scope button; page flips don't
     * apply, the source strip speaks of pages-plural, and saves land on the notebook row.
     */
    private var notebookMode = false

    /**
     * Set while the host is reading a page for us — a "bring in" or a page flip. Blocks a second
     * request from overlapping it and puts the strip into its reading state.
     */
    private var bringingIn = false

    /**
     * Set for the flip flavour of [bringingIn] only. While a flip is in flight the host is already
     * writing to the incoming page but this buffer still shows the outgoing one, so [persist] must
     * stay silent — see there.
     */
    private var flipInFlight = false

    /**
     * The modal "Reading this page…" — the same banner the notebook shows while seeding a page's
     * document at open. Null while not shown.
     */
    private var readingDialog: AlertDialog? = null
    private val readingPopup = Handler(Looper.getMainLooper())

    /** What the popup should say when it (or its delayed post) fires — set before showing. */
    private var readingMessage = "Reading this page…"

    /**
     * Offered as a Cancel button on the popup when set — only for the notebook-document merge,
     * which may read every page in the notebook and is too long a run to be unescapable. A page
     * read stays uncancelable, like the notebook's own open-time banner: recognition has no
     * partial result to keep.
     */
    private var readingCancel: (() -> Unit)? = null
    private val showReadingPopup = Runnable { showReadingDialog() }

    /** The spell-checking layer — flags, debounce, popup. Thin by design; see ProofreadController. */
    private lateinit var proofread: ProofreadController

    private val autosave = Handler(Looper.getMainLooper())
    private val autosaveTick = Runnable { persist() }

    /** Offset of a newline just typed, handed from `onTextChanged` to `afterTextChanged`. -1 = none. */
    private var newlineAt = -1

    /**
     * Manual override for the soft keyboard: `null` follows the hardware (suppressed while a
     * physical keyboard is attached), `true`/`false` force it. Long-press **Write** to flip it —
     * the escape hatch if the hardware detection ever calls it wrong.
     */
    private var softKeyboardOverride: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The proofread user dictionary lives in the global index. In every normal launch the
        // notebook host (itself guarded) already opened it; this fires only when Android rebuilds
        // the task after a process kill — where the host is gone and nothing could save durably.
        if (!IndexGuard.ready(this)) return

        notebookName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME).orEmpty()
        // Read before the views are built — both surfaces are sized from it.
        textSizeSp = DocumentPreferences.textSize(this)

        val root = buildUi()
        setContentView(root)
        // followIme: this screen types, so the layout has to give the software keyboard its room back
        // rather than let it cover the lines being written.
        TopGuard.applyInsetPadding(root, followIme = true)

        // The views are built in code with no ids, so nothing here is restored for us. A recreated
        // editor prefers its own saved buffer over the hand-off, which may be a stale seed (or, after
        // process death, gone entirely).
        val restored = savedInstanceState?.getString(STATE_TEXT)
        val session = DocumentTransfer.input
        val opening = restored ?: session?.text.orEmpty()
        editor.setText(opening)
        // Open where the writer left off. Falling back to the **top** rather than the end: a document
        // is usually read before it is added to, and landing at the bottom of a page of text hides
        // everything that was written.
        val caret = savedInstanceState?.getInt(STATE_CARET) ?: session?.caret ?: 0
        editor.setSelection(caret.coerceIn(0, opening.length))
        pageChanged = session?.stale == true
        drafted = session?.srcUpdatedAt != null
        hasPrev = session?.hasPrev == true
        hasNext = session?.hasNext == true
        // The saved flag wins over the hand-off: a recreated editor prefers its own buffer, and
        // the mode must match the text it goes with.
        notebookMode = savedInstanceState?.getBoolean(STATE_NOTEBOOK)
            ?: (session?.notebook == true)
        session?.pageLabel?.takeIf { it.isNotBlank() }?.let { pageLabel = it }
        updateTitle()
        updatePageLabel()
        applyModeChrome()
        updateSourceStrip()

        // Overflow: work out what fits once the bar has a width, and again whenever that width changes
        // (rotation, a folding screen). Guarded on the width itself — the listener also fires for
        // layout passes that change nothing, and a recalc rebuilds the bar, which would loop.
        val bar = formatBar
        overflow = ToolbarOverflowManager(bar, overflowMenu, dividerOverflow, btnOverflow)
        bar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && width != lastBarWidth) {
                lastBarWidth = width
                bar.post { overflow?.recalc() }
            }
        }

        // A shorter editing surface can leave the caret below the fold, which is precisely what the
        // keyboard appearing does. Only a real height change is worth reacting to.
        editor.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) editor.post { keepCaretVisible() }
        }

        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Note a plain newline for afterTextChanged, which is where the buffer may be edited.
                // Watching the text rather than the Enter key covers both keyboards: a soft keyboard
                // commits "\n" through the input connection and may send no key event at all.
                newlineAt = if (before == 0 && count == 1 && s?.getOrNull(start) == '\n') start else -1
            }

            override fun afterTextChanged(s: android.text.Editable?) {
                val at = newlineAt
                newlineAt = -1
                if (at >= 0 && s != null) continueListAt(s, at)
                scheduleSave()
            }
        })

        // The proofread layer registers its own text watcher, so it comes after setText — the
        // opening text is not an edit. Its first pass runs when the dictionary is ready.
        // `.dict` rather than `.gz` (the content is gzip): AAPT silently *decompresses* any `.gz`
        // asset and strips the extension from the APK, so the runtime name would not match the
        // source tree — found the hard way on the Manta. An opaque extension ships byte-identical.
        proofread = ProofreadController(editor, lifecycleScope) {
            assets.open("proofread/en_82765.dict")
        }
        editor.onWordTap = { offset -> proofread.onTap(offset) }
        proofread.start()

        if (savedInstanceState?.getBoolean(STATE_PREVIEWING) == true) setPreviewing(true)

        editor.requestFocus()
        applyKeyboardMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TEXT, currentText())
        outState.putBoolean(STATE_PREVIEWING, previewing)
        outState.putInt(STATE_CARET, editor.selectionEnd)
        outState.putBoolean(STATE_NOTEBOOK, notebookMode)
    }

    override fun onResume() {
        super.onResume()
        // A request whose host died mid-flight never calls back, which would leave the strip reading
        // "Reading this page…" and the button refusing forever.
        if (bringingIn && DocumentTransfer.host == null) {
            bringingIn = false
            flipInFlight = false
            dismissReadingDialog()
            updateSourceStrip()
        }
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
        // Leaving the screen is a save point: BOOX kills backgrounded activities freely, and there is
        // no Cancel to honour.
        persist()
        // BOOX devices do not reliably drop the IME when a screen goes away (see docs/design-system.md).
        hideIme()
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        super.onDestroy()
        autosave.removeCallbacks(autosaveTick)
        dismissReadingDialog()
        if (::proofread.isInitialized) proofread.dispose()
    }

    // ── Storage (always through the host — see DocumentTransfer) ──────────────

    private fun currentText(): String = editor.text?.toString().orEmpty()

    /** Restart the idle timer; a burst of typing coalesces into one write. */
    private fun scheduleSave() {
        autosave.removeCallbacks(autosaveTick)
        autosave.postDelayed(autosaveTick, AUTOSAVE_DELAY_MS)
    }

    /**
     * Hand the current text to the host, and republish it for the host's teardown flush either way —
     * if the notebook is destroyed under this screen it closes the DB with it, and [DocumentTransfer]
     * is then the only place the text still exists.
     */
    private fun persist() {
        // Mid-flip the host is already keyed to the incoming page while this buffer still holds the
        // outgoing one — a save (or even a republish to `live`) here would land one page's text on
        // another. The outgoing page was persisted as the flip began, so nothing the host was ever
        // given is dropped; anything typed into the doomed buffer is discarded by the arriving
        // session regardless.
        if (flipInFlight) return
        autosave.removeCallbacks(autosaveTick)
        val text = currentText()
        val caret = editor.selectionEnd
        DocumentTransfer.live = text
        DocumentTransfer.liveCaret = caret

        val host = DocumentTransfer.host
        if (host == null) {
            // The notebook was destroyed behind us (process death). The text stays in `live`, which
            // the recreated host flushes when this screen closes.
            Slog.d(TAG) { "No host — text held for the host's return (${text.length} chars)" }
            return
        }
        // Handed over unconditionally, even when the words are unchanged: the caret may still have
        // moved, and the repository drops a write that would change nothing anyway.
        host.saveDocument(text, caret)
        Slog.d(TAG) { "Saved ${text.length} chars, caret $caret" }
    }

    // ── UI (built programmatically to honor the e-ink design system) ─────────────

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // The shared icon-button tap target + glyph inset (44dp/10dp under sw720dp, 62dp/14dp on
    // tablets) — the same dimens Widget.Notesprout.ToolbarButton uses, so this screen's bars
    // match every XML toolbar in the app.
    private val toolbarButtonSize: Int
        get() = resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
    private val toolbarButtonInset: Int
        get() = resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)

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

        // Writing chrome, hidden as one piece in Preview. The source strip reads as a caption under
        // the header; the format bar is a band of its own and gets a rule to say so.
        writingChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            sourceStrip = buildSourceStrip(ink, light)
            addView(sourceStrip)
            addView(rule(ink))
            formatBar = buildFormatBar(ink)
            addView(formatBar)
            // The overflow panel belongs to the chrome, so Preview takes it away with everything else.
            addView(buildOverflowMenu())
        }
        root.addView(writingChrome)
        root.addView(rule(ink))

        editor = MarkdownEditText(this).apply {
            setBackgroundColor(paper)
            setTextColor(ink)
            // Monospace: this is Markdown source, and columns carry meaning here.
            typeface = Typeface.MONOSPACE
            textSize = textSizeSp
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
            textSize = textSizeSp + DocumentPreferences.PREVIEW_BUMP
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

        titleText = AppCompatTextView(this).apply {
            setTextColor(ink)
            textSize = 18f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)

        // Page flips live in the header rather than the format bar: they move the document, not the
        // text. The notebook follows along when this screen closes. Same chevrons the notebook uses for
        // its own page navigation, with the count between them — the number belongs to the control that
        // changes it, and reads as one unit: ‹ 4 / 12 ›
        btnFlipPrev = headerIcon(R.drawable.ic_page_prev, "Previous page  ·  Ctrl+PgUp") { flipPage(-1) }
        header.addView(btnFlipPrev)
        pageText = AppCompatTextView(this).apply {
            setTextColor(ink)
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER
            minWidth = dp(44)
            setPadding(dp(2), 0, dp(2), 0)
        }
        header.addView(pageText)
        btnFlipNext = headerIcon(R.drawable.ic_page_next, "Next page  ·  Ctrl+PgDn") { flipPage(1) }
        header.addView(btnFlipNext)

        // The scope toggle: this page's document ↔ the notebook document (the merged final draft).
        // It sits with the flip cluster because it is the same kind of control — it moves which
        // document the buffer holds, not the text. In notebook mode the flip cluster hides (there
        // is no "next page" of the notebook document), which hands back the width this took.
        btnScope = headerIcon(R.drawable.ic_notebook, "Notebook document") { toggleScope() }
        header.addView(btnScope)

        // Text size lives in the header rather than the writing chrome so it is still reachable in
        // Preview — reading size matters at least as much as writing size.
        header.addView(headerIcon(R.drawable.ic_text_size, "Text size") { promptTextSize() })

        // Write / Preview / Done are icons too, which is not where this started: they are modes and a
        // commit, and words read better for those. But three words plus five controls do not fit a
        // 439dp screen (P2P) — Done fell off the edge — and a button you cannot reach is worse than one
        // you have to learn. Each carries a long-press hint naming it, and the pencil/eye pair reads as
        // the toggle it is.
        btnWrite = headerIcon(R.drawable.ic_pen, "Write") { setPreviewing(false) }
        btnPreview = headerIcon(R.drawable.ic_eye, "Preview") { setPreviewing(true) }
        // Escape hatch: force the soft keyboard on or off when the hardware detection is wrong. This
        // replaces Write's hint toast — the override announces itself with a toast of its own.
        btnWrite.setOnLongClickListener { toggleSoftKeyboardOverride(); true }
        header.addView(btnWrite)
        header.addView(btnPreview)

        // A check, not an X: everything is already saved, so this finishes rather than discards, and an
        // X would promise a way out that does not exist. Keeps the bordered background that set the
        // commit action apart when it was a word.
        header.addView(headerIcon(R.drawable.ic_check, "Done") { persist(); hideIme(); finish() }.apply {
            setBackgroundResource(R.drawable.shape_bordered)
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })

        updateModeButtons()
        return header
    }

    /**
     * One line naming where this text came from, and the only way to bring the page's text back in.
     * Giving provenance and staleness a home of their own keeps the header — which is already
     * `Write | Preview | Done` — from crowding off a narrow screen.
     */
    private fun buildSourceStrip(ink: Int, light: Int): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(8), dp(6))
        }

        sourceText = AppCompatTextView(this).apply {
            // Full ink, not the grey a caption would take elsewhere: mid-greys wash out on e-ink (see
            // docs/design-system.md) and this line is the only place the document says how it stands
            // to its page. Its smaller size is what keeps it secondary.
            setTextColor(ink)
            textSize = 12f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        strip.addView(sourceText)

        // Reflow sits to the *left* of Bring in so Bring in keeps its position under the hand.
        strip.addView(stripButton(ink, "Reflow", "Join wrapped lines into paragraphs  ·  Ctrl+Shift+F") {
            reflowText()
        })
        // "Bring in" in page mode, "Merge" in notebook mode — applyModeChrome relabels it.
        btnBringIn = stripButton(ink, "Bring in", "Bring this page's text into the document") {
            promptBringIn()
        }
        strip.addView(btnBringIn)

        return strip
    }

    /**
     * Point the chrome at the mode the buffer is in. Notebook mode hides the flip cluster — the
     * notebook document has no neighbouring page — which hands back the width the scope toggle
     * took, and relabels the toggle and the strip's action for the way back.
     */
    private fun applyModeChrome() {
        val flips = if (notebookMode) View.GONE else View.VISIBLE
        btnFlipPrev.visibility = flips
        pageText.visibility = flips
        btnFlipNext.visibility = flips

        btnScope.setImageResource(if (notebookMode) R.drawable.ic_file_text else R.drawable.ic_notebook)
        val scopeHint = if (notebookMode) "Page document" else "Notebook document"
        btnScope.contentDescription = scopeHint
        btnScope.setOnLongClickListener { Toast.makeText(this, scopeHint, Toast.LENGTH_SHORT).show(); true }

        btnBringIn.text = if (notebookMode) "Merge" else "Bring in"
        val bringHint = if (notebookMode) "Merge the pages' text into this document"
        else "Bring this page's text into the document"
        btnBringIn.setOnLongClickListener { Toast.makeText(this, bringHint, Toast.LENGTH_SHORT).show(); true }
    }

    private fun stripButton(ink: Int, label: String, hint: String, onClick: () -> Unit): AppCompatButton =
        AppCompatButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(ink)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            // Never take focus: the editor must keep the caret and selection these act on.
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            setOnClickListener { closeOverflowMenu(); onClick() }
            setOnLongClickListener { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show(); true }
        }

    /**
     * Provenance line: says whether the page has moved on since this text was drafted from it.
     *
     * Silent when we cannot say — a document written by hand, or a session recovered from process
     * death, where the hand-off that carried the provenance is gone. No claim beats a wrong one, and
     * *Bring in* is offered either way.
     */
    private fun updateSourceStrip() {
        sourceText.text = if (notebookMode) when {
            bringingIn -> "Reading the pages…"
            pageChanged -> "Pages have changed since this merge"
            drafted -> "Merged from this notebook's pages"
            else -> ""
        } else when {
            bringingIn -> "Reading this page…"
            pageChanged -> "Page has changed since this draft"
            drafted -> "Drafted from this page"
            else -> ""
        }
    }

    /**
     * Carry a list on — or end it — after a newline was typed at [newlineIndex].
     *
     * A single Enter inside a list item writes the next marker, so a series keeps going by itself. A
     * second Enter finds an item with nothing in it, deletes that marker and leaves a clean blank line:
     * two Enters are a paragraph break, and no stray dash, number or checkbox is left behind.
     *
     * Runs from `afterTextChanged`, the one callback where the buffer may be edited. Neither edit it
     * makes is a lone newline, so this cannot re-enter itself.
     */
    private fun continueListAt(text: android.text.Editable, newlineIndex: Int) {
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
                editor.setSelection((at + action.marker.length).coerceAtMost(text.length))
            }
            is MarkdownFormatter.ListEnter.End -> {
                text.delete(lineStart, (lineStart + action.length).coerceAtMost(text.length))
            }
            null -> return
        }
        // An item added in the middle leaves the ones below it claiming numbers they no longer have.
        renumberLists(text)
    }

    /**
     * Make the ordered lists in the buffer read the way Markdown renders them, and report whether
     * anything needed changing.
     *
     * Rewrites are applied back-to-front so the offsets computed against the old text stay valid, and
     * the caret is carried by the change in length of everything that ends before it — a marker rewrite
     * must not shuffle the caret out of the words it was sitting in.
     */
    private fun renumberLists(text: android.text.Editable): Boolean {
        val changes = MarkdownFormatter.renumberOrderedLists(text)
        if (changes.isEmpty()) return false
        val caret = editor.selectionEnd
        var delta = 0
        for (c in changes) if (c.at + c.length <= caret) delta += c.marker.length - c.length
        for (c in changes.asReversed()) {
            text.replace(c.at, (c.at + c.length).coerceAtMost(text.length), c.marker)
        }
        editor.setSelection((caret + delta).coerceIn(0, text.length))
        return true
    }

    /**
     * Scroll the editing surface just enough to keep the caret's line in view.
     *
     * The layout shrinking is only half the fix: the room the keyboard takes is the room the caret was
     * probably sitting in. Text coordinates are offset by the surface's own top padding and displaced by
     * its scroll, so the caret's line is below the fold when
     * `paddingTop + lineBottom - scrollY` passes `height - paddingBottom`.
     */
    private fun keepCaretVisible() {
        if (previewing || !editor.hasFocus()) return
        val layout = editor.layout ?: return
        val caret = editor.selectionEnd.coerceIn(0, editor.text?.length ?: 0)
        val lineBottom = layout.getLineBottom(layout.getLineForOffset(caret))
        val overflow = editor.paddingTop + lineBottom - editor.scrollY -
            (editor.height - editor.paddingBottom)
        if (overflow > 0) editor.scrollBy(0, overflow)
    }

    // ── Text size ─────────────────────────────────────────────────────────────

    /** Pick a text size. The tick marks the one in force; the choice is remembered for next time. */
    private fun promptTextSize() {
        val sheet = ActionSheetDialog(this).title("Text size")
        for ((label, size) in DocumentPreferences.SIZES) {
            val current = size == textSizeSp
            sheet.addAction(null, if (current) "$label  ✓" else label) { applyTextSize(size) }
        }
        sheet.show()
    }

    private fun applyTextSize(sizeSp: Float) {
        textSizeSp = sizeSp
        DocumentPreferences.saveTextSize(this, sizeSp)
        editor.textSize = sizeSp
        previewText.textSize = sizeSp + DocumentPreferences.PREVIEW_BUMP
        // The renderer bakes sizes into spans from the paint it was handed, so the preview has to be
        // rebuilt rather than just re-measured.
        if (previewing) renderPreview()
        Slog.d(TAG) { "Text size → ${sizeSp}sp" }
    }

    // ── Reflow (join wrapped lines into paragraphs) ───────────────────────────

    /**
     * Join wrapped lines into paragraphs — over the selection when there is one, otherwise the whole
     * document. Recognized handwriting arrives with a break at every hand-wrapped line, so this is
     * usually the first thing a fresh draft wants.
     *
     * Applied as an ordinary buffer edit, exactly like a format-bar operation, so Ctrl+Z takes it back.
     */
    private fun reflowText() {
        if (previewing) setPreviewing(false)
        val text = editor.text ?: return
        if (text.isEmpty()) return

        // A selection is grown to whole lines first: reflowing half a line would join across a break
        // the user cannot see the other side of.
        val hasSelection = editor.selectionEnd > editor.selectionStart
        val from = if (!hasSelection) 0 else
            text.toString().lastIndexOf('\n', (editor.selectionStart - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
        val to = if (!hasSelection) text.length else
            text.toString().indexOf('\n', editor.selectionEnd).let { if (it < 0) text.length else it }

        val slice = text.subSequence(from, to).toString()
        val reflowed = MarkdownReflow.reflow(slice)
        val joined = reflowed != slice
        if (joined) {
            text.replace(from, to, reflowed)
            editor.setSelection((from + reflowed.length).coerceIn(0, text.length))
        }
        // Tidying the source is tidying the numbers too — including a list left with gaps by an item
        // deleted long ago. Rendering-neutral, so it runs document-wide even for a selection.
        val renumbered = renumberLists(text)
        if (!joined && !renumbered) {
            Toast.makeText(this, "Nothing to reflow.", Toast.LENGTH_SHORT).show()
            return
        }
        persist()
        Slog.d(TAG) { "Reflowed ${if (hasSelection) "selection" else "document"}" }
    }

    // ── Proofread ─────────────────────────────────────────────────────────────

    /**
     * The proofread sheet: an on-demand full pass, the user dictionary, and the on/off switch.
     * Off hides "Check document" rather than disabling it — a disabled control is invisible on
     * e-ink (see docs/design-system.md), and turning the feature on checks everything anyway.
     */
    private fun promptProofread() {
        val sheet = ActionSheetDialog(this).title("Proofread")
        if (proofread.enabled) {
            sheet.addAction(R.drawable.ic_text_spellcheck, "Check document") {
                if (!proofread.ready) {
                    Toast.makeText(this, "Loading the dictionary — it will check when ready.", Toast.LENGTH_SHORT).show()
                }
                proofread.checkDocument()
            }
            sheet.addAction(R.drawable.ic_book, "User dictionary") { proofread.promptUserDictionary() }
            sheet.addAction(R.drawable.ic_eye_off, "Turn off proofread") { proofread.setEnabled(false) }
        } else {
            sheet.addAction(R.drawable.ic_eye, "Turn on proofread") { proofread.setEnabled(true) }
        }
        sheet.show()
    }

    // ── Page flips ────────────────────────────────────────────────────────────

    /**
     * Move to the next/previous page's document. The current text is stored first, and the host
     * switches which page it writes to as part of the request — the notebook itself only catches up
     * when this screen closes.
     */
    private fun flipPage(delta: Int) {
        if (bringingIn || notebookMode) return
        val host = DocumentTransfer.host
        if (host == null) {
            Toast.makeText(this, "The notebook is no longer open.", Toast.LENGTH_SHORT).show()
            return
        }
        if (delta < 0 && !hasPrev) { Toast.makeText(this, "First page.", Toast.LENGTH_SHORT).show(); return }
        if (delta > 0 && !hasNext) { Toast.makeText(this, "Last page.", Toast.LENGTH_SHORT).show(); return }

        persist()
        bringingIn = true
        flipInFlight = true
        updateSourceStrip()
        // The popup appears only if the flip is still in flight after the delay — that is, when the
        // page actually has to be read. It is the same banner the notebook shows at editor open,
        // which cannot be shown from the host here: the host is stopped behind this screen.
        readingMessage = "Reading this page…"
        readingCancel = null
        readingPopup.postDelayed(showReadingPopup, READING_POPUP_DELAY_MS)
        host.requestPage(delta) { session ->
            bringingIn = false
            flipInFlight = false
            dismissReadingDialog()
            if (session == null) {
                updateSourceStrip()
                Toast.makeText(this, "Couldn't open that page.", Toast.LENGTH_SHORT).show()
                return@requestPage
            }
            applySession(session)
        }
    }

    // ── Notebook document (the merged final draft) ────────────────────────────

    /**
     * Switch between this page's document and the notebook document. The same shape as a page
     * flip — text stored first, the host switches which row it writes to as part of the request,
     * and the gap is a no-save zone on both sides.
     *
     * Toggling **in** may be the first visit, which merges every page's text to seed the document
     * — a run long enough to deserve the Cancel the popup offers (cancelling calls back null and
     * the editor stays on the page it was on). Toggling **out** is `requestPage(0)`: the host
     * kept which page the editor was on.
     */
    private fun toggleScope() {
        if (bringingIn) return
        val host = DocumentTransfer.host
        if (host == null) {
            Toast.makeText(this, "The notebook is no longer open.", Toast.LENGTH_SHORT).show()
            return
        }
        persist()
        bringingIn = true
        flipInFlight = true
        updateSourceStrip()
        if (!notebookMode) {
            readingMessage = "Reading the pages…"
            readingCancel = { host.cancelDocumentRequest() }
            readingPopup.postDelayed(showReadingPopup, READING_POPUP_DELAY_MS)
            host.requestNotebookDocument { session -> onScopeResult(session) }
        } else {
            readingMessage = "Reading this page…"
            readingCancel = null
            readingPopup.postDelayed(showReadingPopup, READING_POPUP_DELAY_MS)
            host.requestPage(0) { session -> onScopeResult(session) }
        }
    }

    private fun onScopeResult(session: DocumentTransfer.Session?) {
        bringingIn = false
        flipInFlight = false
        dismissReadingDialog()
        if (session == null) {
            // Cancelled, or the load failed — either way the host reverted to the document the
            // buffer still shows, so staying put is correct and nothing was lost.
            updateSourceStrip()
            return
        }
        applySession(session)
    }

    /** Show a page's document: its text, its provenance, and where it sits in the notebook. */
    private fun applySession(session: DocumentTransfer.Session) {
        // `setText`, deliberately — not an Editable edit like every other change here. Arriving at
        // another page is a new document, not an edit to this one, and it must not be left sitting on
        // the undo stack: undoing "the flip" would put the page we left behind into the page we
        // arrived at, and the next autosave would store it there.
        editor.setText(session.text)
        // Where this page was left off, same as opening it directly.
        editor.setSelection(session.caret.coerceIn(0, session.text.length))
        editor.scrollTo(0, 0)
        pageChanged = session.stale
        drafted = session.srcUpdatedAt != null
        hasPrev = session.hasPrev
        hasNext = session.hasNext
        pageLabel = session.pageLabel
        notebookMode = session.notebook
        applyModeChrome()
        updatePageLabel()
        updateSourceStrip()
        // Store this page's seed right away, so the gap where the text exists only in memory is as
        // short on a flip as it is on open.
        persist()
        // A new page is a fresh document (setText dropped the old spans with the old Editable), so
        // it gets the same full pass opening one does.
        proofread.checkDocument()
        if (previewing) renderPreview()
    }

    /**
     * The modal "Reading this page…" — visually the twin of the one [NotebookActivity] shows while
     * seeding a document at editor open, so the two entry points to a page read announce themselves
     * the same way. Not cancelable, like the original: recognition has no partial result to keep.
     */
    private fun showReadingDialog() {
        if (readingDialog != null || isFinishing || isDestroyed) return
        val message = AppCompatTextView(this).apply {
            text = readingMessage
            setPadding(64, 48, 64, 48)
            setTextColor(ContextCompat.getColor(this@DocumentEditorActivity, R.color.inkBlack))
            textSize = 16f
        }
        val cancel = readingCancel
        readingDialog = AlertDialog.Builder(this)
            .setView(message)
            .setCancelable(false)
            .apply {
                // Only the notebook merge offers a way out — it may read every page. The button
                // asks the host to stop; the request's own null callback puts the UI right.
                if (cancel != null) setNegativeButton("Cancel") { _, _ -> cancel() }
            }
            .create()
            .also {
                it.show()
                it.window?.setElevation(0f)
                it.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }
    }

    /** Put the popup away — and cancel a delayed show that has not fired yet. */
    private fun dismissReadingDialog() {
        readingPopup.removeCallbacks(showReadingPopup)
        readingDialog?.let { runCatching { it.dismiss() } }
        readingDialog = null
    }

    // ── Bringing the page's text in ───────────────────────────────────────────

    /**
     * The one path by which the page can overwrite the document, and only ever by asking. Both
     * choices are offered because both situations are real: the edits were a false start, or more ink
     * was written after the editing.
     */
    private fun promptBringIn() {
        if (previewing) setPreviewing(false)
        if (bringingIn) return
        if (DocumentTransfer.host == null) {
            Toast.makeText(this, "The notebook is no longer open.", Toast.LENGTH_SHORT).show()
            return
        }
        // In notebook mode the same two situations exist one level up: the edits were a false
        // start, or more was written on the pages after the editing.
        ActionSheetDialog(this)
            .title(if (notebookMode) "Merge pages" else "Bring in page text")
            .addAction(null, "Replace this document") { bringIn(replace = true) }
            .addAction(null, "Add below the current text") { bringIn(replace = false) }
            .show()
    }

    private fun bringIn(replace: Boolean) {
        val host = DocumentTransfer.host ?: return
        val merge = notebookMode
        // Anything typed so far is stored before the page's text lands on top of it.
        persist()
        bringingIn = true
        updateSourceStrip()
        // Shown at once, not on the flip's delay: a bring-in always reads the page(s) in full.
        readingMessage = if (merge) "Reading the pages…" else "Reading this page…"
        readingCancel = if (merge) ({ host.cancelDocumentRequest() }) else null
        showReadingDialog()
        val onDraft: (DocumentTransfer.Draft?) -> Unit = { draft ->
            bringingIn = false
            dismissReadingDialog()
            if (draft == null) {
                updateSourceStrip()
                if (!merge) {
                    Toast.makeText(this, "Nothing to bring in from this page yet.", Toast.LENGTH_SHORT).show()
                }
                // A cancelled merge stays silent — the user just said no.
            } else {
                applyDraft(draft.text, replace)
                // The document now matches the page(s) as of this recognition.
                pageChanged = false
                drafted = true
                updateSourceStrip()
                persist()
            }
        }
        if (merge) host.requestNotebookMerge(onDraft) else host.requestPageDraft(onDraft)
    }

    /**
     * Apply [draft] to the buffer as an ordinary text edit — the same route the format bar takes — so
     * the editor's own Ctrl+Z can take it back within the session.
     */
    private fun applyDraft(draft: String, replace: Boolean) {
        val text = editor.text ?: return
        if (replace) {
            text.replace(0, text.length, draft)
            editor.setSelection(text.length)
        } else {
            val merged = DocumentDraft.append(text.toString(), draft)
            val insertAt = merged.length - draft.trim().length
            text.replace(0, text.length, merged)
            editor.setSelection(insertAt.coerceIn(0, text.length))
        }
        Slog.d(TAG) { "Brought in page text (${if (replace) "replace" else "append"})" }
    }

    /**
     * The title names the **notebook** — the screen itself is evidently a document, so the word would
     * only take room from the one piece of context the editor cannot otherwise give you: which
     * notebook you are writing in. Falls back to "Document" only when the name is unknown.
     */
    private fun updateTitle() {
        titleText.text = notebookName.ifBlank { "Document" }
    }

    /** The page's place in the notebook, sitting between the two arrows that change it. */
    private fun updatePageLabel() {
        pageText.text = pageLabel
    }

    /**
     * A compact header control — the page arrows and the text-size button.
     *
     * The arrows stay visible and tappable at the ends of the notebook: a disabled button is visually
     * silent on e-ink (see docs/design-system.md), so the guard lives in the handler and says what
     * happened instead.
     */
    private fun headerIcon(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton =
        iconButton(iconRes, hint, size = toolbarButtonSize, inset = toolbarButtonInset, onClick = onClick).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(2)
        }

    private fun buildFormatBar(ink: Int): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun divider() = bar.addView(groupDivider(ink))

        bar.addView(formatIcon(R.drawable.ic_h_1, "Heading 1  ·  Ctrl+1") { applyBlock(MarkdownFormatter.Block.HEADING, 1) })
        bar.addView(formatIcon(R.drawable.ic_h_2, "Heading 2  ·  Ctrl+2") { applyBlock(MarkdownFormatter.Block.HEADING, 2) })
        bar.addView(formatIcon(R.drawable.ic_h_3, "Heading 3  ·  Ctrl+3") { applyBlock(MarkdownFormatter.Block.HEADING, 3) })
        divider()
        bar.addView(formatIcon(R.drawable.ic_bold, "Bold  ·  Ctrl+B") { wrapInline("**") })
        bar.addView(formatIcon(R.drawable.ic_italic, "Italic  ·  Ctrl+I") { wrapInline("*") })
        bar.addView(formatIcon(R.drawable.ic_strikethrough, "Strikethrough  ·  Ctrl+Shift+X") { wrapInline("~~") })
        bar.addView(formatIcon(R.drawable.ic_code, "Inline code  ·  Ctrl+E") { wrapInline("`") })
        divider()
        bar.addView(formatIcon(R.drawable.ic_blockquote, "Blockquote  ·  Ctrl+Shift+Q") { applyBlock(MarkdownFormatter.Block.QUOTE) })
        bar.addView(formatIcon(R.drawable.ic_list, "Bullet list  ·  Ctrl+Shift+8") { applyBlock(MarkdownFormatter.Block.BULLET) })
        bar.addView(formatIcon(R.drawable.ic_list_numbers, "Numbered list  ·  Ctrl+Shift+7") { applyBlock(MarkdownFormatter.Block.ORDERED) })
        bar.addView(formatIcon(R.drawable.ic_list_check, "Task list  ·  Ctrl+Shift+9") { applyBlock(MarkdownFormatter.Block.TASK) })
        divider()
        bar.addView(formatIcon(R.drawable.ic_link, "Link  ·  Ctrl+K") { runFormat(MarkdownFormatter::insertLink) })
        bar.addView(formatIcon(R.drawable.ic_photo, "Image  ·  Ctrl+Shift+K") { runFormat(MarkdownFormatter::insertImage) })
        bar.addView(formatIcon(R.drawable.ic_separator_horizontal, "Horizontal rule  ·  Ctrl+Shift+−") { runFormat(MarkdownFormatter::insertRule) })
        divider()
        // Last on the bar, so on narrow screens it lives in the overflow panel — a check runs on
        // its own; this button is for the occasional full pass and the on/off switch.
        bar.addView(formatIcon(R.drawable.ic_text_spellcheck, "Proofread") { promptProofread() })

        // Overflow controls, pinned at the trailing edge and hidden whenever everything fits. The full
        // palette is ~730dp of buttons, so a 6" screen (sw571dp) cannot show it whole — and a bar that
        // scrolls hides its tail with no sign that there is one. What is on the bar stays put for a
        // given screen, so muscle memory still holds; only the tail moves, and it moves to one place.
        dividerOverflow = groupDivider(ink)
        btnOverflow = iconButton(
            R.drawable.ic_dots, "More tools", size = toolbarButtonSize, inset = toolbarButtonInset, closesOverflow = false,
        ) { toggleOverflowMenu() }
        bar.addView(dividerOverflow)
        bar.addView(btnOverflow)
        return bar
    }

    /** A group separator in the format bar — a plain [View], which is how the overflow manager tells
     *  dividers from tools when it decides where to cut. */
    private fun groupDivider(ink: Int): View = View(this).apply {
        setBackgroundColor(ink)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
    }

    /** The panel the overflowed tools drop into: bordered, below the bar, collapsed until asked for. */
    private fun buildOverflowMenu(): View {
        overflowMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.shape_bordered)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return overflowMenu
    }

    private fun toggleOverflowMenu() {
        val manager = overflow ?: return
        if (manager.isOverflowMenuOpen()) manager.closeOverflowMenu() else manager.openOverflowMenu()
    }

    private fun closeOverflowMenu() {
        overflow?.takeIf { it.isOverflowMenuOpen() }?.closeOverflowMenu()
    }

    /**
     * A tap anywhere that is not the bar or the panel puts the overflow away — placing the caret in the
     * text should not have to be preceded by dismissing a menu. Deliberately **not** consumed: unlike
     * the notebook's canvas, where a stray touch would start a stroke, here the touch is the user
     * choosing where to type and it must still land.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            overflow?.isOverflowMenuOpen() == true &&
            !isInside(formatBar, event) && !isInside(overflowMenu, event)
        ) {
            closeOverflowMenu()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isInside(view: View, event: android.view.MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val xy = IntArray(2).also { view.getLocationOnScreen(it) }
        val x = event.rawX
        val y = event.rawY
        return x >= xy[0] && x <= xy[0] + view.width && y >= xy[1] && y <= xy[1] + view.height
    }

    /** A format-bar tool: the shared toolbar-button target around a Tabler glyph. */
    private fun formatIcon(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton =
        iconButton(iconRes, hint, size = toolbarButtonSize, inset = toolbarButtonInset, onClick = onClick)

    /**
     * The one icon button this screen builds, so every bar shares a hit area, a background and a
     * long-press that names the tool.
     *
     * Tabler outline glyphs at 24dp, drawn in `inkBlack` at stroke width 2 — the same set and the same
     * weight as the notebook's own toolbar, which is what makes the two read as one app rather than two.
     * [inset] is the padding that leaves the glyph its 24dp inside a [size] target.
     */
    private fun iconButton(
        iconRes: Int,
        hint: String,
        size: Int,
        inset: Int,
        /** False only for the overflow button itself, which would otherwise close then re-open. */
        closesOverflow: Boolean = true,
        onClick: () -> Unit,
    ): AppCompatImageButton =
        AppCompatImageButton(this).apply {
            setImageResource(iconRes)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            setPadding(inset, inset, inset, inset)
            // Long-press names the tool and teaches its shortcut — an icon bar carries no labels — and
            // the same string is what a screen reader announces.
            contentDescription = hint
            // Never take focus: the editor must keep the caret and selection the button acts on.
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(2) }
            // Using a tool puts the overflow away: it opened to reach that tool, and its job is done.
            setOnClickListener { if (closesOverflow) closeOverflowMenu(); onClick() }
            setOnLongClickListener { Toast.makeText(context, hint, Toast.LENGTH_SHORT).show(); true }
        }

    // ── Write / Preview ───────────────────────────────────────────────────────

    private fun setPreviewing(on: Boolean) {
        if (previewing == on) return
        previewing = on
        // Preview is read-only prose; no checking there (and no popup — the editor is gone).
        proofread.setPaused(on)
        if (on) {
            // Switching to reading is a natural save point, and the writing chrome goes with it.
            persist()
            closeOverflowMenu()
            hideIme()
            editor.visibility = View.GONE
            writingChrome.visibility = View.GONE
            previewScroll.visibility = View.VISIBLE
            renderPreview()
        } else {
            previewScroll.visibility = View.GONE
            writingChrome.visibility = View.VISIBLE
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
     * chords that otherwise cycle soft keyboards. On Ratta the connection stays (hardware typing
     * arrives only through it there), so its IME sits upstream and may claim a chord first.
     */
    private fun handleShortcut(event: KeyEvent): Boolean {
        val shift = event.isShiftPressed
        // Page flips move the document, not the text, so they work in both modes.
        when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> { flipPage(-1); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { flipPage(1); return true }
        }
        // Preview is read-only; only the mode toggle stays live there.
        if (previewing) {
            if (event.keyCode == KeyEvent.KEYCODE_P && !shift) { setPreviewing(false); return true }
            return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_F -> if (shift) { reflowText(); return true }
            KeyEvent.KEYCODE_B -> if (!shift) { wrapInline("**"); return true }
            KeyEvent.KEYCODE_I -> if (!shift) { wrapInline("*"); return true }
            KeyEvent.KEYCODE_X -> if (shift) { wrapInline("~~"); return true }
            KeyEvent.KEYCODE_E -> if (!shift) { wrapInline("`"); return true }
            KeyEvent.KEYCODE_K ->
                if (shift) { runFormat(MarkdownFormatter::insertImage); return true }
                else { runFormat(MarkdownFormatter::insertLink); return true }
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
     *
     * **Except on Ratta**, where hardware keys are routed through the IME and the IME translates
     * them only while it is *shown* — hide it and the firmware drops the keys before the app sees
     * anything at all (measured on the Nomad: with the soft keyboard hidden, not one key event
     * reached `dispatchKeyEvent`; shown, typing worked and only the key-ups passed through). So on
     * Ratta an attached keyboard is not a reason to hide the soft keyboard: the IME stays up and
     * connected, exactly as it would for soft typing. The long-press-Write override still forces it
     * away — that is the user trading typing for screen room, and the toast announces the state.
     */
    private fun applyKeyboardMode() {
        val wantSoftKeyboard = softKeyboardOverride
            ?: (isRattaDevice() || !physicalKeyboardAttached())
        editor.suppressImeSession = !wantSoftKeyboard && !isRattaDevice()
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
 * [AppCompatEditText] that can refuse the IME entirely, plus the proofread surface work: it draws
 * the dashed flag underlines and reports taps for the suggestion popup.
 *
 * When [suppressImeSession] is set, [onCreateInputConnection] returns null: the framework never
 * binds an input method to this field, so no soft keyboard appears and — crucially — no IME sits
 * upstream of the app in the hardware key path, claiming shortcuts on its way past. The editor
 * still handles physical keys through its own key listener.
 *
 * The underlines are drawn here rather than by the spans themselves because a `CharacterStyle`
 * cannot draw a *dashed* line — `UnderlineSpan` is solid, and the design gives spelling a dashed
 * inkBlack line and grammar a dotted one. [ProofreadFlagSpan] and [GrammarFlagSpan] therefore
 * carry no style, and this view paints under every span each draw pass.
 */
private class MarkdownEditText(context: Context) : AppCompatEditText(context) {

    var suppressImeSession = false

    /** Called with the character offset of a confirmed single tap — the proofread popup's hook. */
    var onWordTap: ((Int) -> Unit)? = null

    /**
     * Confirmed-single-tap detection, so the popup never rides a double-tap: a double tap is the
     * framework's select-word gesture, and a sheet on top of a fresh selection would break
     * select-to-copy on every flagged word. `onSingleTapConfirmed` fires only after the
     * double-tap window has passed — and never for drags or long-presses — well after `super`
     * has placed the caret.
     *
     * The character offset is resolved in `onSingleTapUp`, not at confirmation: the confirmation
     * arrives ~300 ms after the finger lifted, and a tap that summons the soft keyboard has
     * resized and scrolled this view by then — the event's x/y against the *new* layout name a
     * different character.
     */
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Resolved here — only for tap-shaped lifts, never scroll/long-press ends — while
            // the pre-IME layout is still current.
            tappedOffset = getOffsetForPosition(e.x, e.y)
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tappedOffset >= 0) onWordTap?.invoke(tappedOffset)
            return false
        }
    })

    /** Offset under the last tap-shaped finger-up, against the layout that was actually tapped. */
    private var tappedOffset = -1

    private val density = resources.displayMetrics.density

    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.inkBlack)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        // Dashed = spelling. On-off lengths chosen to survive e-ink: long enough to render as
        // marks, short enough to read as a dash and not a rule.
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }

    private val grammarPaint = Paint(flagPaint).apply {
        // Dotted = grammar. Round caps turn the near-zero dash segments into dots the stroke's
        // width across — a different texture from the spelling dash at reading distance.
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(1f, 2.5f * density), 0f)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (suppressImeSession) null else super.onCreateInputConnection(outAttrs)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawProofreadFlags(canvas)
    }

    private fun drawProofreadFlags(canvas: Canvas) {
        val text = text ?: return
        if (text.isEmpty()) return
        val layout = layout ?: return
        // onDraw runs on every keystroke, caret blink, and scroll frame, and each underline
        // costs a line measurement — so only the flags in the viewport are considered, not
        // every flag in the document.
        val topLine = layout.getLineForVertical(scrollY)
        val bottomLine = layout.getLineForVertical(scrollY + height)
        val visStart = layout.getLineStart(topLine)
        val visEnd = layout.getLineEnd(bottomLine)
        val spelling = text.getSpans(visStart, visEnd, ProofreadFlagSpan::class.java)
        val grammar = text.getSpans(visStart, visEnd, GrammarFlagSpan::class.java)
        if (spelling.isEmpty() && grammar.isEmpty()) return
        canvas.save()
        // onDraw's canvas is already scrolled; only the text origin's padding is left to add.
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        for (span in spelling) underlineSpan(canvas, layout, text, span, flagPaint)
        for (span in grammar) underlineSpan(canvas, layout, text, span, grammarPaint)
        canvas.restore()
    }

    private fun underlineSpan(canvas: Canvas, layout: Layout, text: Spanned, span: Any, paint: Paint) {
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        val drop = 2f * density
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        // A long word can soft-wrap mid-word, so a flag may span lines even though a word
        // never contains a newline.
        for (line in firstLine..lastLine) {
            val x1 = if (line == firstLine) layout.getPrimaryHorizontal(start) else layout.getLineLeft(line)
            var x2 = if (line == lastLine) layout.getPrimaryHorizontal(end) else layout.getLineRight(line)
            // At a wrap boundary the end offset's position belongs to the next line's start.
            if (line == lastLine && x2 <= x1) x2 = layout.getLineRight(line)
            if (x2 <= x1) continue
            val y = layout.getLineBaseline(line) + drop
            canvas.drawLine(x1, y, x2, y, paint)
        }
    }
}
