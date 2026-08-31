package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * **M6 gave the screen its provenance and its legs** — the [SourceStrip] (where this document came
 * from, Reflow, and Bring in) and [PageFlipController] (the arrows, `Ctrl+PgUp` / `Ctrl+PgDn`, and
 * the no-save zone a flip opens). Both are collaborators rather than methods here for the reason the
 * module's size rule exists; what this class keeps is the wiring, the header, and [lastState] — the
 * last answer the host gave, which is what the arrows' edge check reads instead of asking again.
 *
 * **M7 gave it a second place to stand** — the notebook document, one merged final draft per
 * notebook, reached by the header's [ScopeToggle]. A scope switch is a page flip in every way that
 * matters and runs through the flip controller's own machinery; the strip changes its words rather
 * than its shape; and [RestoredState] carries the **mode-routing guard's editor half** — a recreated
 * screen's buffer is adopted only when the bundle's key is the key the load landed on, so notebook
 * text can never be pushed under a page key or the other way round.
 *
 * **M8 gave the text document its two controls** — [ShowPagesButton] (the exit to the pages this
 * screen opened *instead of*) and [RenameControl] (the title, tappable, because a text document has
 * no library card on screen to long-press). Both are absent for every other notebook, both are drawn
 * from [lastState], and neither touches M6's rule that the back arrow is the ONE leave door.
 *
 * **M10 gave it a reader over the writer's shoulder** — [ProofreadController], which owns the
 * dictionary, the debounce, the flags and its sheets. Four lines of it are here: where it is built,
 * where Preview pauses it, where an adopted buffer is re-checked, and where it is let go.
 */
class DocumentEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentEditorBinding

    /** Timers, threads and the binder; every decision inside it is [AutosaveGovernor]'s. The caret
     *  rides along with every save trigger — see [DocumentSaver]'s `caretSink`. */
    private val saver = DocumentSaver(
        snapshot = { currentText() },
        caretSnapshot = { if (this::binding.isInitialized) binding.editor.selectionEnd else 0 },
        caretSink = { key, caret -> EditorPrefs.rememberCaretAsync(key, caret) },
        // The strip may only claim provenance the host has actually stored, and must stop claiming
        // it the moment the host says the claim died with its process.
        onDraftAnchored = { if (this::strip.isInitialized) strip.show(DocumentContract.SOURCE_DRAFTED) },
        onDraftDowngraded = { if (this::strip.isInitialized) strip.show(DocumentContract.SOURCE_NONE) },
    )

    private var overflow: FormatBarOverflow? = null

    /** Spelling and grammar over the buffer, and everything that surrounds them (M10). */
    private lateinit var proofread: ProofreadController

    /** The find bar's own wiring — its query, its count, and the five controls that act on the
     *  editor's selection. Built with the rest of the chrome. */
    private lateinit var find: FindReplaceBar

    /** Reflow, list renumbering, word count and the caret scroll — the tools that take the buffer
     *  and the selection and nothing else. */
    private lateinit var tools: EditorTools

    /** Provenance, Reflow and Bring in (M6). */
    private lateinit var strip: SourceStrip

    /** The page arrows' machinery, and the no-save zone they open (M6) — and, since M7, the scope
     *  switch, which runs the same path for the same reasons. */
    private lateinit var flips: PageFlipController

    /** The header's page ↔ notebook control, and the chrome that follows it (M7). */
    private lateinit var scopeToggle: ScopeToggle

    /** The text document's exit to its pages, and its rename-from-the-title (M8). Both are drawn
     *  from [lastState] and both are absent for every other kind of notebook. */
    private lateinit var showPages: ShowPagesButton
    private lateinit var rename: RenameControl

    /** The format bar's fourteen tools and the four chord-only ones, over the buffer. */
    private lateinit var format: FormatActions

    /** Every `Ctrl` chord this screen answers, and the ones it deliberately does not. */
    private lateinit var shortcuts: EditorShortcuts

    /** The size both surfaces are drawn at, and the sheet that picks it. */
    private lateinit var textSize: TextSizeControl

    /** The last state the host answered with — what the arrows' edge check and the strip's line are
     *  read from, so neither costs a Binder call. Null until the first load lands. */
    private var lastState: DocumentPageState? = null

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

    /** The bundle this instance woke up holding, and the guard that decides whether any of it may
     *  be adopted. Built in `onCreate`, consumed at the end of the load. */
    private lateinit var restored: RestoredState

    /** This instance's session hooks, held by identity so `onDestroy` can only clear its own. */
    private val beginListener = EditorSession.BeginListener { onHostBegan() }
    private val flushHook = object : EditorSession.FlushHook {
        override fun unsavedSnapshot(): Pair<String, String>? = unsavedSnapshotBlocking()

        // The service's teardown push rides the saver's own lock (M11) — see DocumentSaver.
        override fun pushBlocking(pageKey: String, text: String) =
            saver.pushLockedBlocking(pageKey, text)
    }

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

        restored = RestoredState(savedInstanceState)

        buildChrome()
        installWatcher()
        // After the screen's own watcher, so proofread's runs second — never mid-list-continuation.
        proofread = ProofreadController.install(this, binding.editor, lifecycleScope)

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
     * The bundle's shape, its size rule and its keys are all [RestoredState]'s — including the
     * **target key** (M7), which is what lets the restore refuse a buffer belonging to another
     * document. What this method owns is only the four live values.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val built = this::binding.isInitialized
        val text = if (built) currentText() else null
        RestoredState.save(
            outState,
            key = saver.pageKey,
            text = text?.takeIf { saver.isDirty(it) && it.length <= MAX_BUNDLED_CHARS },
            caret = if (built) binding.editor.selectionEnd else RestoredState.NO_CARET,
            previewing = previewing,
            draftPending = saver.draftPending,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only ever clear what this instance installed — see the registration note in onCreate.
        if (EditorSession.beginListener === beginListener) EditorSession.beginListener = null
        if (EditorSession.flushHook === flushHook) EditorSession.flushHook = null
        if (EditorAutomation.peer === automationPeer) EditorAutomation.peer = null
        saver.cancelTimers()
        // The reading popups' coroutine paths ride the now-cancelled lifecycle scope; this is the
        // hide those paths can no longer run. A refused caller never built the chrome.
        if (this::flips.isInitialized) flips.close()
        if (this::strip.isInitialized) strip.close()
        if (this::proofread.isInitialized) proofread.dispose()
    }

    // ── Chrome ────────────────────────────────────────────────────────────────

    private fun buildChrome() {
        // The back arrow is the ONE leave door (user call, M6 review): every way out saves and
        // nothing discards, so a ✓ Done beside it did the same thing twice. The debug hook's
        // `done` still leaves with RESULT_OK — the host treats both results identically.
        binding.btnClose.setOnClickListener { leave(Activity.RESULT_CANCELED) }
        binding.btnWrite.setOnClickListener { setPreviewing(false) }
        binding.btnPreview.setOnClickListener { setPreviewing(true) }
        // Live in Preview too: reading comfort is most of what a text size is for.
        binding.btnTextSize.setOnClickListener { overflow?.close(); textSize.prompt() }
        // Live in Preview too, and for the same kind of reason: walking the notebook is reading as
        // much as it is writing (og's rule).
        binding.btnPagePrev.setOnClickListener { flipPage(DocumentContract.PAGE_PREV) }
        binding.btnPageNext.setOnClickListener { flipPage(DocumentContract.PAGE_NEXT) }
        for (button in listOf(
            binding.btnClose, binding.btnTextSize, binding.btnWrite,
            binding.btnPreview, binding.btnPagePrev, binding.btnPageNext,
        )) {
            // btnScope's hint is ScopeToggle's — it changes with the face.
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }
        updateModeButtons()

        format = FormatActions(
            binding = binding,
            isPreviewing = { previewing },
            onSearch = { find.open() },
            onWordCount = { tools.showWordCount() },
            onProofread = { proofread.promptProofread() },
        )
        textSize = TextSizeControl(
            context = this,
            binding = binding,
            scope = lifecycleScope,
            isPreviewing = { previewing },
            renderPreview = { renderPreview() },
        )
        tools = EditorTools(
            context = this,
            binding = binding,
            isPreviewing = { previewing },
            leavePreview = { setPreviewing(false) },
            onEdited = { saver.saveNow() },
        )
        strip = SourceStrip(
            activity = this,
            binding = binding,
            saver = saver,
            scope = lifecycleScope,
            // Before the first state there is no notebook document to be in: the page scope is
            // what the strip and the button are drawn for until the host says otherwise.
            documentScope = { lastState?.scope ?: DocumentContract.SCOPE_PAGE },
            canBringIn = { !flips.inFlight && !leaving },
            onReflow = { overflow?.close(); tools.reflow() },
            onBroughtIn = { state ->
                showTarget(state)
                if (previewing) renderPreview()
                tools.keepCaretVisible()
            },
        )
        strip.install()
        flips = PageFlipController(
            activity = this,
            binding = binding,
            saver = saver,
            scope = lifecycleScope,
            // The match count is stale the moment the buffer is another page's; the query itself
            // survives in the field, which is what a writer looking for the same word wants.
            onFlipStarting = { find.close(); overflow?.close() },
            installText = ::installLoadedText,
            onAdopted = { state ->
                showTarget(state)
                if (previewing) renderPreview()
                tools.keepCaretVisible()
            },
        )
        scopeToggle = ScopeToggle(
            activity = this,
            binding = binding,
            scopeNow = { lastState?.scope },
            busy = { flips.inFlight || strip.inFlight },
            leaving = { leaving },
            onTapped = { overflow?.close() },
            switchTo = { flips.switchScope(it) },
        )
        scopeToggle.install()
        // M8's two text-document controls. Both read `lastState`, so both are silent — and, for the
        // button, GONE — until the host has said what kind of notebook this is.
        showPages = ShowPagesButton(
            binding = binding,
            scope = lifecycleScope,
            stateNow = { lastState },
            busy = { flips.inFlight || strip.inFlight },
            leaving = { leaving },
            leave = { leave(Activity.RESULT_OK) },
        )
        showPages.install()
        rename = RenameControl(
            activity = this,
            binding = binding,
            scope = lifecycleScope,
            stateNow = { lastState },
            leaving = { leaving },
            onRetitled = { lastState = it },
        )
        rename.install()
        find = FindReplaceBar(
            context = this,
            binding = binding,
            isPreviewing = { previewing },
            leavePreview = { setPreviewing(false) },
            keepCaretVisible = { tools.keepCaretVisible() },
            onReplacedAll = { saver.saveNow() },
        )
        find.install()
        // Last: every act it runs belongs to something built above it.
        shortcuts = EditorShortcuts(
            format = format,
            isPreviewing = { previewing },
            setPreviewing = { setPreviewing(it) },
            flipPage = { flipPage(it) },
            closeOverflow = { overflow?.close() },
            openFind = { find.open() },
            reflow = { tools.reflow() },
        )

        // The caret must survive the keyboard taking the room it was sitting in; the scroll that
        // does it, and the watch that triggers it, are both EditorTools'.
        tools.watchHeight()

        val controls = FormatBar.build(
            bar = binding.formatBar,
            onTool = { format.run(it) },
            onToolUsed = { overflow?.close() },
            onOverflow = { overflow?.toggle() },
        )
        // The bar re-cuts itself whenever its width changes; the manager owns that watch.
        overflow = FormatBarOverflow(
            binding.formatBar, binding.overflowPanel, controls.dividerOverflow, controls.btnOverflow,
        ).apply { watchWidth() }
    }

    /** A tap outside the format bar and its panel puts the overflow away; the touch still lands. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        overflow?.dismissIfOutside(event)
        return super.dispatchTouchEvent(event)
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
                    // The mode-routing guard, FIRST — before the caret lookup, so a bundle dropped
                    // for naming another target still gets the stored caret rather than the top.
                    // Never the text: only that it went.
                    if (!restored.bind(state.pageKey)) {
                        Slog.d(TAG) { "restored buffer dropped — target changed" }
                    }
                    // Only worth asking when the bundle has no answer — a recreated screen's own
                    // caret always wins, so a lookup then would be read and thrown away. A fresh
                    // seed opens at the top too: there is no "where you left off" for text that has
                    // never been on screen.
                    val caret = if (restored.caret == RestoredState.NO_CARET && !state.seeded) {
                        EditorPrefs.caret(state.pageKey)
                    } else {
                        0
                    }
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
        // A seeded window is a draft the host built and has NOT stored: what is on screen is
        // unsaved, and the save that lands it carries the claim that stamps the watermark the host
        // parked. Opening the editor on a written page IS the act of drafting it (og), and this is
        // where that becomes true.
        saver.adoptWindow(loaded.text, state.seeded)
        // A recreation can be holding an unstored seed of its own — and only if the guard in
        // `load()` kept it, because a claim from another document would stamp a watermark this one
        // never earned.
        if (restored.draftPending) saver.armDraft()
        showTarget(state)

        // Both surfaces are sized before the text lands, so nothing is laid out twice. Not persisted
        // — this is the value that was just read back.
        textSize.apply(loaded.textSizeSp, persist = false)

        // A recreated editor prefers its own saved buffer over the pull, and treats it as UNSAVED:
        // `savedText` stays what the host handed over, so the first debounce writes the difference.
        val opening = restored.takeText() ?: loaded.text
        installLoadedText(opening)
        // Open where the writer left off — the bundle's caret on a recreation, the remembered one
        // otherwise. Falling back to the TOP rather than the end: a document is usually read before
        // it is added to, and landing at the bottom hides everything written.
        val caret = if (restored.caret != RestoredState.NO_CARET) restored.caret else loaded.caret
        binding.editor.setSelection(caret.coerceIn(0, opening.length))

        // Which surface was up outlives a dropped bundle: it is a fact about the reader, not the
        // document.
        if (restored.previewing) setPreviewing(true)
        if (saver.isDirty(opening)) saver.schedule()
        if (!previewing) binding.editor.requestFocus()
        Slog.d(TAG) { "loaded ${loaded.text.length} chars in ${state.textChunks} chunk(s)" }
    }

    /**
     * The header and the strip for [state] — every adopted target goes through here, whether it
     * arrived from the open, from a flip or from a Bring in, so the three can never disagree.
     *
     * The strip's line comes from the host's answer *unless* an unstored draft is on screen: the
     * host is describing what it holds, and a seed in the buffer is not that yet.
     *
     * [lastState] is set FIRST: the toggle and the strip both read the scope from it.
     */
    private fun showTarget(state: DocumentPageState) {
        lastState = state
        binding.title.text = state.title.ifEmpty { getString(R.string.document_title) }
        // −1 is the notebook scope (M7) — not a page, so nothing to number.
        binding.pageIndicator.text = if (state.pageIndex >= 0) {
            getString(R.string.document_page_indicator, state.pageIndex + 1, state.pageCount)
        } else ""
        scopeToggle.apply(state.scope)
        // M8: the two text-document controls, from the same state as everything else in this header.
        showPages.apply()
        rename.apply()
        strip.show(if (saver.draftPending) DocumentContract.SOURCE_DRAFTED else state.source)
    }

    /** Install text that is not an edit — a load or a flip. It must not sit on the undo stack, and
     *  it must not be mistaken for the writer typing. */
    private fun installLoadedText(text: String) {
        applyingEdit = true
        binding.editor.setText(text)
        applyingEdit = false
        // setText dropped the old spans with the old Editable, so every adoption re-flags in full.
        proofread.checkDocument()
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
        // GONE, never disabled: a disabled button is visually silent on e-ink, and there is no
        // notebook to walk — or second place to stand — when nothing could be read.
        binding.btnPagePrev.visibility = View.GONE
        binding.btnPageNext.visibility = View.GONE
        binding.btnScope.visibility = View.GONE
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
        // Preview is read-only prose: no checking there, and no popup — the editor is gone.
        proofread.setPaused(on)
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

    /** Render the current Markdown through the shared engine, once, on entering Preview. The render
     *  itself is [PreviewRender]'s — it needs the two views and the display, and nothing else here. */
    private fun renderPreview() = PreviewRender.render(binding, resources)

    // ── Page flips ────────────────────────────────────────────────────────────

    /**
     * One tap on an arrow, or one `Ctrl+PgUp` / `Ctrl+PgDn`. The decision is [FlipRules]'; the act
     * is [PageFlipController]'s.
     *
     * The edge check is **local**, against the last state the host answered with: a tap past the
     * last page must not cost a Binder round trip to be told there is nothing there. And at an edge
     * the arrows stay exactly where they are and say so — a disabled button is visually silent on
     * e-ink, so the toast is the only thing that can report a tap that changed nothing.
     */
    private fun flipPage(direction: Int) {
        val state = lastState
        val outcome = FlipRules.check(
            busy = flips.inFlight || strip.inFlight,
            leaving = leaving,
            hasTarget = state != null,
            direction = direction,
            pageIndex = state?.pageIndex ?: 0,
            pageCount = state?.pageCount ?: 1,
        )
        when (outcome) {
            FlipRules.Outcome.BLOCKED -> Unit
            FlipRules.Outcome.AT_FIRST -> toast(R.string.document_first_page)
            FlipRules.Outcome.AT_LAST -> toast(R.string.document_last_page)
            FlipRules.Outcome.GO -> flips.flip(direction)
        }
    }

    private fun toast(@StringRes messageRes: Int) =
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()

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
                if (at >= 0 && s != null) tools.continueListAt(s, at)
                saver.schedule()
            }
        })
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    /** The whole chord table is [EditorShortcuts]' — including which chords are deliberately NOT
     *  claimed (Ctrl+Z/Y/A/C/V/X belong to the `EditText`). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (this::shortcuts.isInitialized && shortcuts.handle(event)) return true
        return super.dispatchKeyEvent(event)
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
                // A ladder, not one shot (M11): the recreated host's `.soil` open is asynchronous,
                // and a single failed probe left the fresh session's window forever unestablished —
                // every later save refused by key until the words died at teardown. The no-screen
                // path (the service's pending push) always had this ladder; the live screen gets
                // the same one.
                val key = withContext(Dispatchers.IO) {
                    var got: String? = null
                    for (attempt in 1..RECONNECT_STATE_ATTEMPTS) {
                        val host = EditorSession.host ?: break   // no showing — nothing to ask
                        got = try {
                            host.current().pageKey
                        } catch (e: Exception) {
                            Slog.d(TAG) { "reconnect state attempt $attempt failed: ${e.javaClass.simpleName}" }
                            null
                        }
                        if (got != null) break
                        if (attempt < RECONNECT_STATE_ATTEMPTS) delay(RECONNECT_STATE_RETRY_MS)
                    }
                    got
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

        override fun textSize(): Float = textSize.sp
        override fun setTextSize(sp: Float) = textSize.apply(sp)

        // ── M6 ────────────────────────────────────────────────────────────────
        // Both of these start work that finishes later; a walk polls `get_state` / `page_label` /
        // `source_label` afterwards rather than expecting a reply to carry the result.

        override fun flip(direction: Int) = flipPage(direction)
        override fun bringIn(mode: Int) = strip.bringIn(mode)
        override fun sourceLabel(): String = strip.label()

        // ── M7 ────────────────────────────────────────────────────────────────

        override fun scope(): Int = lastState?.scope ?: DocumentContract.SCOPE_PAGE
        override fun toggleScope() = scopeToggle.tap()
        override fun merge(mode: Int): Boolean =
            (lastState?.scope == DocumentContract.SCOPE_NOTEBOOK).also { if (it) strip.bringIn(mode) }

        // ── M8 ────────────────────────────────────────────────────────────────
        // The notebook's NAME is user content like the document is: it crosses here and is never
        // logged either.

        // Qualified: the peer's own members are named after the controls they drive, and an
        // unqualified `showPages` inside this object would read as the method, not the button.
        override fun showPages(): Boolean = this@DocumentEditorActivity.showPages.tap()
        override fun rename(name: String): Boolean = this@DocumentEditorActivity.rename.rename(name)
        override fun title(): String = binding.title.text.toString()
    }

    private companion object {
        const val TAG = "DocumentEditor"

        /** The buffer rides `onSaveInstanceState` only below this: a bundle is a Binder transaction
         *  and the budget is ~1 MB. 256k chars is half of it as UTF-16, with room for the rest. */
        const val MAX_BUNDLED_CHARS = 256_000

        /** How long the teardown backstop waits for Main to hand over the buffer. */
        const val FLUSH_HOP_MS = 1_000L

        /** The reconnect probe's ladder (M11) — the service's pending-push numbers, shared shape:
         *  ~5 s covers a recreated host's asynchronous database open. */
        const val RECONNECT_STATE_ATTEMPTS = 10
        const val RECONNECT_STATE_RETRY_MS = 500L
    }
}
