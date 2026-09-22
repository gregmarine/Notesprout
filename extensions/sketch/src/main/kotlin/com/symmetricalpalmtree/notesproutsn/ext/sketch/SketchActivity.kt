package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PageMode
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.gpaper.core.RasterPatch
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.EinkRefresh
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.sketch.databinding.ActivitySketchBinding
import com.symmetricalpalmtree.notesproutsn.extension.ByteChunks
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ISketchHost
import com.symmetricalpalmtree.notesproutsn.extension.ImageHeader
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchPageState
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke
import com.symmetricalpalmtree.notesproutsn.ink.PaperScreenActivity
import com.symmetricalpalmtree.notesproutsn.ink.awaitPenIdle
import com.symmetricalpalmtree.notesproutsn.notebook.CollapsedChrome
import com.symmetricalpalmtree.notesproutsn.notebook.PageGestures
import com.symmetricalpalmtree.notesproutsn.notebook.PaperChrome
import com.symmetricalpalmtree.notesproutsn.notebook.PaperToolbar
import com.symmetricalpalmtree.notesproutsn.notebook.UndoRedoStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The extension-owned **Sketch** screen (arc 43 / K5; UI-rule tier 2) — a raster page beside the
 * notebook's ink, in the extension's own process, built on `:ext-ink`'s [PaperScreenActivity]: the
 * chrome bars and their toggle, the collapsed corner button, the free band, the stylus-vs-finger
 * dispatch, the pen-idle gate and **the EPD handoff** are all there. What is here is what a *raster
 * page* is.
 *
 * **The caller check is the first statement**, before anything is inflated: the screen is exported
 * (it has to be — the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * ## What is different from every other paper screen
 *
 * - **There is no store and there are no rows.** The pixels live in the host's `.soil` and cross the
 *   seam as chunked lossless WebP ([SketchSaver] out, [loadPage] in). Nothing here writes to disk,
 *   ever.
 * - **A page is two rasters, one picture** (arc 45 "Ink", the user's decision of 2026-09-17): a
 *   **graphite** image the pencil bakes into and the rubber rubs, and an **ink** image the gel pen
 *   and "Bring in ink" bake into and **nothing ever erases** — *"in the real world, ink is more
 *   permanent than pencil."* Neither is a user-facing layer: there is no z-order to choose and
 *   nothing to toggle, because what the artist sees is the two flattened with a darken composite,
 *   which is order-independent and therefore has no top and no bottom. Routing is g-paper's, by
 *   stroke style, at the one site `RasterLayer.of`; this screen only ever *follows* the layer the
 *   engine names — in its undo entries, its dirty flags, its two rows and its two parks.
 * - **A mark is not an object.** `pageMode = RASTER` is set once, before any content: the engine
 *   composites each mark into its style's page-sized image at pen-up and drops the stroke, so
 *   `onStrokeCommitted` still fires and is **deliberately ignored** — storing that stroke would be
 *   storing a row for something that is already pixels.
 * - **Undo is pixels — and, since K5b, one page.** The before-image of everything one contact
 *   changed is read on a 64 px grid ([RasterTiles]) between `onRasterWillChange` and `onPenLifted`,
 *   and taken back with `swapPageRaster(layer, …)`, which leaves the arrays holding the other side — so one
 *   entry is its own redo. **An entry names its raster** and reads only that one, so a page with two
 *   images costs an undo exactly what a page with one did. The history is bounded by **bytes** as
 *   well as by count ([SketchEdit.UNDO_BUDGET_BYTES]). The one entry that holds no pixels is a page
 *   insert or delete ([SketchEdit.Structural]): it holds the host's *name* for that edit, and
 *   replaying it is a Binder call that runs the notebook's own undo arm ([applyStructural]).
 * - **Undo and redo are gestures only** (decision 12): the two- and three-finger stationary
 *   double-taps every SN paper screen has. No arrows — an arrow that cannot be redrawn when its
 *   state changes (which is every moment the pen is armed on this panel) is an arrow that lies.
 * - **The screen turns, inserts and deletes its own pages** (decision 7, as the user amended it on
 *   2026-09-15 — K5b) and the notebook catches up when it closes. At either edge of a *turn* the
 *   host answers the same page and the screen stays put, silently ([PageTurn]); a swipe past the
 *   **last** page makes one instead, and a two-finger swipe makes one either side, exactly as the
 *   notebook and the Scratch Pad do. An insert or a delete is **the notebook's** structural edit —
 *   the snapshot that makes it reversible goes on the notebook's undo stack, never on this one —
 *   while this screen's *pixel* history is re-indexed around it ([UndoRedoStack.remap]) and keeps a
 *   name for it, so **this screen's own undo gesture reverses a page too** (the user's follow-up
 *   decision, 2026-09-15: nobody should have to go back to the notebook to take back a delete).
 * - **Plain white paper, always** (decision 10): no template ever crosses the seam.
 * - **The tools are chosen and remembered** (arc 44 / T3, remade by arc 46 "Palette"): a graphite
 *   pencil of one width and sixteen shades, a gel pen of one width and the same sixteen shades,
 *   and the rubber. Both pens are `Tool.PEN` to the engine, so which one is armed lives in
 *   [SketchToolState] and the bar paints from it; a kind's shade is picked in the [PaletteBar]
 *   hung under its own button on a re-tap, and **each pen button reports its own shade** by
 *   carrying it as a fill inside its glyph ([ShadeIcon]) — on the top bar, on the mini toolbar
 *   and on the corner button. The choice is **device state, not page state** — it is kept in the
 *   host's prefs over two seam tails (`toolSettings` / `putToolSettings`), one setting for every
 *   notebook, never in the `.soil` and never in a backup, because an extension writes nothing to
 *   disk itself.
 *
 * ## Frame silence
 *
 * No app frame while `paper.isPenActive`. The page indicator waits for the gate ([SketchToolbar]),
 * and so does the debounced save's copy. The frames that do not are the notebook's recorded
 * exceptions in their sketch form: the "Opening…" box's hide when the page lands, a problem dialog
 * at a deliberate tap (a failed bake, a failed final flush), and the chrome flip at a finger
 * double-tap (arc 33's exception 6, never pen-idle-gated).
 */
class SketchActivity : PaperScreenActivity() {

    private lateinit var binding: ActivitySketchBinding
    private lateinit var toolbar: SketchToolbar
    private lateinit var saver: SketchSaver

    /** The shade panel (arc 46 "Palette") — Atelier's sixteen tones, hung under whichever pen
     *  button was re-tapped (top bar or mini row). Null until `onCreate` builds it, which a refused
     *  caller never reaches. */
    private var paletteBar: PaletteBar? = null

    /**
     * This sitting's history — pixels, so it is bounded by bytes as well as by count. It survives a
     * page turn (each entry carries the page it happened on) and dies with the screen.
     */
    private val undo = UndoRedoStack<SketchEdit>(
        cost = { it.bytes },
        budgetBytes = SketchEdit.UNDO_BUDGET_BYTES,
    )

    /** Serialises every page / undo / bake / flush operation. */
    private val pageOps = Mutex()

    /** Keeps the tool pushes in pick order (arc 44 / T3): each one is its own IO hop, and two quick
     *  picks racing there could leave the device remembering the first. The mutex is fair. */
    private val toolPushes = Mutex()

    /** The page on the glass, as the host last described it. Null before the first load. */
    private var currentPage: SketchPageState? = null

    /** The open contact's before-image, from its first change to the pen lifting. */
    private var openEdit: RasterEditBuilder? = null

    /**
     * Main-thread nanoseconds spent reading the open contact's before-image — the cost of every
     * `onRasterWillChange` it received, summed. Reported with the entry (K6's measurement: what a
     * corner-to-corner hairline costs at pen-up before and after g-paper reports per run).
     */
    private var openEditReadNanos = 0L

    // ── What the skeleton asks for ───────────────────────────────────────────

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null

    override fun armTool(tool: Tool) = toolbar.arm(tool)

    /** **Two** tools on the mini toolbar, not the usual four (arc 43 / K2 grew the parameter for
     *  exactly this): the pen and the rubber are all this surface answers. The pen slot is two
     *  *kinds* since arc 44 ([collapsedPenKinds]), so the row reads Pencil · Pen · Eraser — the top
     *  bar's own order. The pencil wears `ic_pen` — Tabler's pencil glyph, and the user's call. */
    override fun collapsedTools(): List<Tool> = listOf(Tool.PEN, Tool.ERASER)

    /**
     * The pen's two kinds on the mini toolbar (arc 44 / T3): the graphite pencil and the gel pen,
     * both `Tool.PEN`. A pick of the already-armed kind opens the [PaletteBar] **under that row's
     * own button** for that kind and leaves the row up beneath it — the notebook Insert bar's
     * shape, and the reason the entry is handed its anchor: the top bar's pen buttons are `GONE`
     * while the chrome is collapsed and keep stale edges, so a bar hung under one would land under
     * nothing.
     *
     * **The row and the corner button report the shades too** — the same filled glyphs the top
     * bar's own buttons wear ([ShadeIcon]), so collapsing the chrome never costs the person the one
     * place a tone is shown. The row's two pen buttons carry theirs always; the corner button wears
     * the armed kind's while a pen kind is the armed tool, since under the rubber it is wearing the
     * rubber's glyph. The ARGB is the token `CollapsedChrome` compares, so nothing repaints for a
     * pick that lands on the shade already showing.
     */
    override fun collapsedPenKinds(): CollapsedChrome.PenKinds = CollapsedChrome.PenKinds(
        primaryHint = getString(R.string.cd_tool_pencil),
        altIconRes = R.drawable.ic_ballpen,
        altHint = getString(R.string.cd_tool_pen),
        altArmed = { toolbar.state.isPen },
        onPick = { alt -> armPen(alt) },
        onReTap = { _, anchor -> togglePaletteBar(anchor) },
        primaryIcon = {
            val ink = toolbar.state.pencilReport
            CollapsedChrome.PenIcon(ink) { ShadeIcon.pencil(this, ink) }
        },
        altIcon = {
            val ink = toolbar.state.penReport
            CollapsedChrome.PenIcon(ink) { ShadeIcon.pen(this, ink) }
        },
    )

    /** The shade panel is this screen's own floating chrome: the pen refuses under it and a finger
     *  landing on it is not a page gesture. */
    override fun extraFloatingRects(): List<Rect> = paletteBar?.rects() ?: emptyList()

    override fun extraFloatingContains(x: Int, y: Int): Boolean = paletteBar?.contains(x, y) == true

    /** The mini toolbar's rows are coming down — the panel hung under one of them goes with them.
     *  No exclusion push here: [CollapsedChrome]'s own `onChanged` follows. */
    override fun onCollapsedClosing() {
        takeDownPaletteBar()
    }

    /** …and a contact **inside** that panel must not take the rows down under it. */
    override fun keepCollapsedUnder(x: Int, y: Int): Boolean = paletteBar?.contains(x, y) == true

    /** Back · Bring in ink · Show pages — the top bar's three doors, **mirrored**, so the row shows
     *  exactly what the bar shows and a tap performs the bar button's own click. Three is past
     *  `CollapsedTools.INLINE_MAX`, so they sit behind `…`. */
    override fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(
        backEntry(),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_pencil_down, binding.btnBringInk),
        CollapsedChrome.Entry.mirroring(R.drawable.ic_page, binding.btnShowPages),
    )

    // ── Create ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing — before anything is inflated. A refused caller is already finished.
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) {
            Slog.d(TAG) { "refused caller ${callingPackage ?: "(none)"}" }
            return
        }
        binding = ActivitySketchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)   // 0 on Ratta — chrome sits flush at the top edge

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        // **Before any content**: the mode drops what the view holds, exactly as a page turn does,
        // and it is never flipped under ink.
        paper.pageMode = PageMode.RASTER
        // No pen gestures on a sketch: a hatch is not a scribble and a closed shading loop is not a
        // selection. Armed before the listener attaches — the engine reads them as it wires up.
        paper.smartLassoEnabled = false
        paper.scribbleEraseEnabled = false
        paper.setPaperListener(paperListener)
        Slog.d(TAG) { "engine=${paper.engineId}" }

        saver = SketchSaver(
            copyPage = { layer -> paper.getPageRaster(layer) },
            awaitPenIdle = { paper.awaitPenIdle() },
        )

        toolbar = SketchToolbar(
            paper = paper,
            topBar = binding.topBar,
            btnBack = binding.btnBack,
            btnPencil = binding.btnPencil,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnBringInk = binding.btnBringInk,
            btnShowPages = binding.btnShowPages,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            pageIndicator = binding.pageIndicator,
            onBack = { exit(Activity.RESULT_CANCELED) },
            onBringInk = { bringInInk() },
            onShowPages = { exit(SketchContract.RESULT_SKETCH_SHOW_PAGES) },
            onPrevPage = { turnPage(SketchContract.PAGE_PREV) },
            onNextPage = { turnPage(SketchContract.PAGE_NEXT) },
            // An actual tool change — including a pencil↔gel-pen switch, which never moves
            // `paper.tool`: the shade panel shows the kind that is leaving.
            onToolTapped = { dismissCollapsed(); hidePaletteBar() },
            // The armed kind's own button: the pencil's or the pen's (arc 46).
            onPenReTap = { alt -> togglePaletteBar(if (alt) binding.btnPen else binding.btnPencil) },
            onPenKindPicked = { alt -> pickTools(toolbar.state.withTool(penKind(alt))) },
            onSynced = { syncCollapsed() },   // arc 36: the corner button repaints with the bar
            // Release builds never attach it: the door is compiled out with the branch.
            onIndicatorLongPress = if (BuildConfig.DEBUG) ({ fillTestPattern() }) else null,
        )
        paletteBar = PaletteBar(
            root = binding.root,
            bar = binding.paletteBar,
            anchor = binding.btnPencil,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            armed = { toolbar.state },
            onPicked = { picked -> pickTools(picked) },
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            // The surface accepts no ink until the page is truly on it: a mark composited now would
            // be thrown away by the load that follows, with nowhere to have been recorded.
            blockAll = { !opened },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { false },   // nothing on this surface claims finger input
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit(Activity.RESULT_CANCELED) }
        })
        pushExclusions()

        if (SketchSession.host == null) {
            // `begin` never ran, or the host tore the showing down under us. Nothing to show, and
            // nothing that could be saved — say so and leave through the handoff like every exit.
            Log.w(TAG, "no host binder for this showing")
            failOpen()
            return
        }
        SketchSession.flushHook = flushHook
        SketchSession.beginListener = beginListener
        lifecycleScope.launch { openPage() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openPage() {
        val state = callHost { it.current() }.getOrElse {
            Log.w(TAG, "the host would not answer current(): ${it.javaClass.simpleName}")
            failOpen()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        loadPage(state, firstLoad = true)
        // Before `opened`, which is what the block-all rect waits on: the tools are in place before
        // the first mark is possible, so nothing is ever drawn with a pencil the person did not
        // choose. It costs one more Binder call on the open path — a prefs read, no window moves.
        restoreTools()
        opened = true
        pushExclusions()   // swap the block-all rect for the real chrome rects
        // The page is on the paper — take the box down. Deliberately NOT pen-idle-gated:
        // `isPenActive` counts hover, and the pen is already over the glass on the way to drawing,
        // which would hold the box up over the page the user asked for.
        binding.openingOverlay.visibility = View.GONE
        Slog.d(TAG) { "page ${state.pageIndex + 1}/${state.pageCount} open ${state.rasterSizes()}" }
    }

    /**
     * Put the page [state] describes on the paper — **both of its rasters** (arc 45 / G3): pull each
     * one's WebP out of the host's own read window, decode it, and load it onto that layer — the
     * host-responsibilities page-swap order with `loadPageRaster` where `loadStrokes` would be
     * (`clearForContentSwap` → `setPageSize` → the content calls, one EPD refresh, no blank flash).
     *
     * **Both layers are always loaded, null for an absent one.** `loadPageRaster(layer, null)` drops
     * that layer in the engine; after `clearForContentSwap` it is a no-op, on the first load it is a
     * no-op, and it costs nothing — so the face never has to reason about which rasters the
     * *previous* page happened to have. A page with ink and no graphite following one with graphite
     * and no ink is simply two loads, as every other page is.
     *
     * **Both decoded first, then both loaded back-to-back** (0.1.42 re-pin, 2026-09-19). The
     * engine copies each bitmap in, and on Supernote it rebuilds its dithered display of the
     * whole page at every load — so loading graphite, decoding ink, then loading ink showed the
     * pencil first and the ink a moment later (two ~100 ms rebuilds with a decode between them).
     * With the decodes done first the two loads land together, the engine folds them into one
     * rebuild and one present, and the page appears whole. The peak is the same three page-sized
     * bitmaps it always was (the second decode, the engine's first copy, the engine's second
     * copy — ~9.5 MB each on the Nomad, ~18.4 MB on the Manta), because the first decode is
     * recycled the instant its load returns.
     *
     * **A page with a save still in the air waits for it, and then asks again** (2026-09-19). Since
     * a page turn stopped awaiting the encode ([SketchSaver.flushForTurn]), the rows of the page
     * just left are written some seconds after the turn — so a turn straight *back* to that page
     * would otherwise read the row as it was before the last strokes, and the drawing would seem to
     * lose them. This is the one place on the face that reads a page's rasters back through the
     * host, so this is the one place the rule has to hold. Two halves to it:
     *
     * - **Wait for that page's own pushes and for nobody else's** ([SketchSaver.awaitPushes]). A
     *   page nothing is owed for — every ordinary turn — returns at once and waits for nothing.
     * - **Then re-ask the host for its state.** The [SketchPageState] handed in carries the byte
     *   and chunk counts of the read windows the host loaded *before* the push landed, so waiting
     *   alone would only have waited to read stale numbers. `current()` reloads both windows for
     *   the host's target page — which at every call site here is the page being loaded — and
     *   answers the counts that go with them. It costs one Binder call, and only in the case that
     *   needed one.
     *
     * **The header guard runs before each decode** ([ImageHeader]) and a mismatch starts that layer
     * blank with a line in the log rather than handing a foreign blob's idea of its own size to the
     * allocator.
     */
    private suspend fun loadPage(requested: SketchPageState, firstLoad: Boolean) {
        val state = awaitOwnSave(requested)
        // A contact that never got its pen-up (the panel slept mid-sweep) leaves a half-gathered
        // entry tagged with the page being left. Carried across the turn it would go on collecting
        // the next page's cells under the old page's key. That gathering is one movement of the hand
        // that cannot be taken back now; the honest loss.
        openEdit = null
        dismissCollapsed()   // a floating row never survives a content swap
        hidePaletteBar()     // nor a panel hung under one — arc 44 / T3
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(state.width, state.height)
        paper.setTemplate(null)   // plain white always (decision 10) — no template ever crosses
        val decoded = ArrayList<Pair<RasterLayer, Bitmap?>>(SketchLayers.all.size)
        for (layer in SketchLayers.all) {
            val bytes = readSketch(state, layer)
            val bitmap = withContext(Dispatchers.IO) { RasterImage.decode(bytes, state.width, state.height) }
            if (isFinishing || isDestroyed) { bitmap?.recycle(); decoded.forEach { it.second?.recycle() }; return }
            decoded += layer to bitmap
        }
        for ((layer, bitmap) in decoded) {
            // Silent since g-paper 0.1.33 (K6): a page we loaded ourselves is our own news, so no
            // will-change/changed pair arrives and nothing here has to swallow one.
            paper.loadPageRaster(layer, bitmap)
            bitmap?.recycle()
        }
        currentPage = state
        saver.pageKey = state.pageKey
        saver.markClean()
        toolbar.setPage(state.pageIndex + 1, state.pageCount)
        if (!firstLoad) refreshPanelAfterTurn()
    }

    /**
     * End a page change with a **full panel refresh** (2026-09-19, the user's finding and decision
     * on the Nomad: "the sketch face could use a full refresh at page turn").
     *
     * Since the Supernote pencil went direct on the panel and the page on the glass became a dither
     * (g-paper 0.1.42), a page of high-contrast dots is exactly the frame the compositor's partial
     * update ghosts worst — and a sketch face turns pages between them all day. [EinkRefresh] asks
     * the firmware's own e-ink service to clear and repaint; on anything that does not answer it is
     * one log line, once, and nothing thereafter.
     *
     * **Posted, never called inline.** [loadPage] has just handed the engine both rasters and the
     * engine rebuilds and presents them; a refresh asked before that present would clear the panel
     * and let the new page land on it partially — the ghosting it exists to remove, put back. The
     * post runs after this load's own frame.
     *
     * **A page change, and only a page change.** Every call site of [loadPage] with
     * `firstLoad = false` is one — a turn, an insert, a delete, a structural replay's landing, the
     * replay walk's last step. The first open of the face is not (the activity transition refreshes
     * the panel by itself), and neither is a raster undo/redo, which swaps tiles in place and never
     * comes through here.
     */
    private fun refreshPanelAfterTurn() {
        if (!REFRESH_ON_TURN) return
        paper.asView().post { if (!isFinishing && !isDestroyed) EinkRefresh.fullRefresh(this) }
    }

    /**
     * [loadPage]'s first half (2026-09-19): if this page has a background save still in the air,
     * wait for it and re-ask the host for the state, so what is about to be read is the row the
     * push wrote and not the row it is replacing.
     *
     * **The cheap question first.** `isPushPending` is a map lookup; on every ordinary turn it is
     * false and this costs nothing at all — no wait, no extra Binder call. Only a turn straight back
     * to a page drawn on seconds ago takes the slow side.
     *
     * The re-ask is `current()`, which loads both read windows for **the host's target page**. Every
     * call site of [loadPage] is a state the host has just made its target — `current()` itself, a
     * turn, an insert, a delete, a structural replay, the walk's last step — so "the target" and
     * "the page being loaded" are the same page by construction; a `current()` that answered a
     * different key would mean the two had drifted, and the state that comes back is the one to
     * believe in any case. A host that refuses the call leaves the page as it was asked for, with a
     * line: the read that follows is then the same read it would have been before this change.
     *
     * **A second copy of the page still on the glass closes the window this wait opens.** The paper
     * still holds the *outgoing* page while this waits, and [SketchSaver.pageKey] still names it —
     * a mark landing here would be composited into that raster and then thrown away by the
     * `markClean()` at the end of the load. The turn's own flush ran before the host was asked to
     * move, so this second one is `Idle` unless something landed in between; when something did, it
     * is copied and pushed under the page it was drawn on, like every other save. It leaves only the
     * `current()` call's own width of window, which is what every load has always had. The second
     * `awaitPushes` after it is for the one case where the glass **is** the page being reloaded (a
     * structural replay landing where it started): the late flush is then that page's own push too,
     * and reading before it landed would be the very thing this method exists to prevent.
     */
    private suspend fun awaitOwnSave(requested: SketchPageState): SketchPageState {
        if (!saver.isPushPending(requested.pageKey)) return requested
        val t0 = SystemClock.elapsedRealtime()
        saver.awaitPushes(requested.pageKey)
        if (!saver.flushForTurn()) Log.w(TAG, "a late mark could not be copied before the load; the raster stays dirty")
        saver.awaitPushes(requested.pageKey)
        val fresh = callHost { it.current() }.getOrElse {
            Log.w(TAG, "the page's state could not be re-read after its save: ${it.javaClass.simpleName}")
            return requested
        }
        Slog.d(TAG) {
            "waited ${SystemClock.elapsedRealtime() - t0} ms for this page's own save before loading it " +
                "${fresh.rasterSizes()}"
        }
        return fresh
    }

    /** One raster's read window, chunk by chunk — an empty answer is a page with no such raster,
     *  which is the window's shape for it (one empty chunk) and not a failure. A layer the state
     *  says is absent is answered without a Binder call at all. */
    private suspend fun readSketch(state: SketchPageState, layer: RasterLayer): ByteArray {
        val wire = SketchLayers.wireOf(layer)
        if (!state.hasLayer(wire)) return ByteArray(0)
        val count = state.chunksOf(wire)
        return callHost { host ->
            val chunks = ArrayList<ByteArray>(count)
            for (i in 0 until count) chunks += host.readSketchChunk(wire, i)
            ByteChunks.join(chunks)
        }.getOrElse {
            Log.w(TAG, "the page's $layer raster could not be read: ${it.javaClass.simpleName}")
            ByteArray(0)
        }
    }

    // ── The tools (arc 44 / T3) ──────────────────────────────────────────────

    /**
     * Put this device's remembered tools on the pen, or the face's defaults.
     *
     * **Null is the answer, not a failure**: the seam's word for "nothing has been remembered here
     * yet" — a first showing, cleared app data — and the defaults live in [SketchToolState] rather
     * than in the host precisely so that answer can be given honestly.
     *
     * **There is no host-version test here, and there does not need to be one.** The two tails sit
     * behind `SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS`, and the number this extension
     * declares in its manifest (19) is what it *requires of the host*: a host below 19 never
     * discovers this package at all, so the call cannot land on a transaction code that is not
     * there. The declaration **is** the guard — the K5b page tails' arrangement exactly. A refusal
     * that arrives anyway is a log line and the defaults, like every other call here.
     */
    private suspend fun restoreTools() {
        val remembered = callHost { it.toolSettings() }.getOrElse {
            Log.w(TAG, "the host would not answer toolSettings(): ${it.javaClass.simpleName}")
            null
        }
        if (isFinishing || isDestroyed || closing) return
        val state = SketchToolState.fromSettings(remembered)
        toolbar.apply(state)
        Slog.d(TAG) { "tools restored: ${if (remembered == null) "defaults" else "remembered"} — $state" }
    }

    /**
     * A tool choice the person made: on the engine, and on the device.
     *
     * The push is **fire and forget** — the pen is already armed by the time it goes, and a failure
     * means only that this pick will not survive the session, which is a log line and never a
     * dialog interrupting a hand that is drawing. A few small integers are not content (the seam
     * says so), so they may be logged.
     */
    private fun pickTools(state: SketchToolState) {
        toolbar.apply(state)
        lifecycleScope.launch {
            toolPushes.withLock {
                callHost { it.putToolSettings(state.toSettings()) }.onFailure {
                    Log.w(TAG, "the tools could not be remembered: ${it.javaClass.simpleName}")
                }
            }
        }
        Slog.d(TAG) { "tools picked: $state" }
    }

    /** Which kind the two pen buttons stand for — the alt one is the gel pen. */
    private fun penKind(alt: Boolean): Int =
        if (alt) SketchContract.TOOL_PEN else SketchContract.TOOL_PENCIL

    /**
     * Arm a pen **kind** from the mini toolbar: the kind first, then the tool through the toolbar's
     * own `arm` — which does the one pen-gated render release and the syncs, and is a no-op on the
     * surface when `Tool.PEN` is already what is armed. The top bar's own buttons take the other
     * road ([PaperToolbar] arms the tool itself and calls back for the kind), and both end at
     * [pickTools].
     */
    private fun armPen(alt: Boolean) {
        pickTools(toolbar.state.withTool(penKind(alt)))
        toolbar.arm(Tool.PEN)
    }

    /** Open the shade panel under [anchor], or close it — the armed pen button's re-tap toggle,
     *  from the top bar (the pencil's or the pen's button) or from the mini toolbar (that row's). */
    private fun togglePaletteBar(anchor: View? = null) {
        val bar = paletteBar ?: return
        if (bar.isShowing) {
            hidePaletteBar()
            return
        }
        if (!opened || closing) return
        val shown = if (anchor == null) bar.show() else bar.show(anchor)
        if (shown) pushExclusions()
    }

    /** Idempotent; answers whether it was showing, so a caller inside [CollapsedChrome]'s close can
     *  leave the one exclusion push to it. */
    private fun takeDownPaletteBar(): Boolean {
        val bar = paletteBar ?: return false
        if (!bar.isShowing) return false
        bar.hide()
        return true
    }

    /** Idempotent — every dismiss path but the collapsed chrome's calls this one. */
    private fun hidePaletteBar() {
        if (takeDownPaletteBar()) pushExclusions()
    }

    /**
     * The outside-contact dismissal — the eraser sub-bar's rule on every other paper screen: any
     * pointer landing anywhere but the panel itself, the armed pen button whose own re-tap toggles
     * it, or the collapsed rows it may be hanging under, takes it down. That covers a bare pen tap, a
     * stroke, a finger gesture and every other button on either bar without any of them having to
     * know this bar exists.
     *
     * Excluding the two toggling buttons is load-bearing: a contact that both dismissed the bar and
     * then re-opened it would make the toggle re-open what it meant to close, every time — the lasso
     * popup's original trap.
     */
    private fun dismissPaletteBarOnContact(ev: MotionEvent, index: Int) {
        val bar = paletteBar ?: return
        if (!bar.isShowing) return
        val x = ev.getX(index).toInt()
        val y = ev.getY(index).toInt()
        // `floatingContains` is this screen's own bar (through `extraFloatingContains`) **and** the
        // collapsed rows it may be hanging under — window coordinates, which on a full-bleed
        // immersive screen are the root's.
        if (floatingContains(x, y)) return
        val toggler = if (toolbar.state.isPen) binding.btnPen else binding.btnPencil
        if (PaperToolbar.rectOf(toggler)?.contains(x, y) == true) return
        hidePaletteBar()
    }

    /** Every pointer going down, not just the first — with a hand resting on the glass the pen
     *  arrives as `ACTION_POINTER_DOWN` (the notebook's O2 finding, the base class's rule). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            dismissPaletteBarOnContact(ev, ev.actionIndex)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ── Page turns ───────────────────────────────────────────────────────────

    /**
     * Ask the host to move one page, and follow it.
     *
     * **This page's pixels are frozen first, not written first** ([SketchSaver.flushForTurn],
     * 2026-09-19). The copy is taken before anything moves — so what will be written is decided and
     * nothing the next page's hand does can change it — and the WebP encode and the push go on in
     * the background while this turn lands. The user's own finding is the reason: the encode is
     * 0.5–3.5 s on a real pencil page, and awaiting it made a flip straight after drawing take
     * seconds while a flip after a pause was instant. A copy that could not be taken leaves that
     * raster dirty and re-arms the retry; the turn goes ahead rather than trapping the hand on one
     * page, exactly as a failed flush always did.
     *
     * What keeps that honest is [loadPage], which waits for a page's own pushes before it reads that
     * page's rasters back — so a turn *away* from a page just drawn on is free, and only a turn
     * straight *back* to it pays anything.
     *
     * **At the edge the host answers the same page** and the screen stays put: no dialog, no toast,
     * and the arrows never disable. Nothing is read there either, so the in-flight push is no
     * hazard — the state the host answers with is compared by key and dropped.
     */
    private fun turnPage(direction: Int) = runPageOp { turnPageNow(direction) }

    /** [turnPage]'s body, already inside the page-op lock — so the swipe-past-the-last-page gesture
     *  can decide between a turn and an insert without taking the lock twice. */
    private suspend fun turnPageNow(direction: Int) {
        val from = currentPage ?: return
        if (!saver.flushForTurn()) Log.w(TAG, "the page's pixels could not be copied before the turn; the raster stays dirty")
        val to = callHost { it.requestPage(direction) }.getOrElse {
            Log.w(TAG, "the host would not turn the page: ${it.javaClass.simpleName}")
            hostGone()
            return
        }
        if (PageTurn.isEdge(from.pageKey, to.pageKey)) {
            Slog.d(TAG) { "page turn refused at the edge (page ${from.pageIndex + 1}/${from.pageCount})" }
            return
        }
        loadPage(to, firstLoad = false)
        Slog.d(TAG) { "turned to page ${to.pageIndex + 1}/${to.pageCount} ${to.rasterSizes()}" }
    }

    // ── Page insert and delete (K5b) ────────────────────────────────────

    /**
     * Ask the host to make a page on the side [direction] names, and land on it.
     *
     * **This page's pixels go first**, exactly as a turn flushes them and for the same reason: the
     * host's read window is about to move, and a save left in flight would land on the page being
     * left.
     *
     * The insert is the **notebook's** structural edit — the host records the snapshot that makes it
     * reversible on the notebook's own undo stack — but this screen's history gains a *name* for it
     * ([rememberStructural]), so the undo gesture here reverses it too.
     *
     * The history also needs the **arithmetic**: a page inserted at position `p` pushes every later
     * page one along, and every entry recorded against one of those pages is now a page out of step
     * with the replay's walk ([PageTurn.reindexAfterInsert]).
     */
    private suspend fun insertPageNow(direction: Int) {
        if (currentPage == null) return
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the insert; its pixels are parked")
        val to = callHost { it.insertPage(direction) }.getOrElse {
            Log.w(TAG, "the host would not insert a page: ${it.javaClass.simpleName}")
            showProblem(R.string.sketch_page_failed_title, R.string.sketch_page_failed_body)
            return
        }
        val at = to.pageIndex
        undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        // Recorded AFTER the re-index, so the entry is never shifted by its own insert — and
        // through `record`, which clears the redo side: a new edit forks the history here exactly
        // as it does on the host's ledger, and the two must fork together.
        rememberStructural(SketchEdit.PageInserted(to.structuralToken, to.pageKey, at), to)
        loadPage(to, firstLoad = false)
        Slog.d(TAG) { "inserted page ${to.pageIndex + 1}/${to.pageCount}" }
    }

    /**
     * Ask the host to delete the page on the glass, and land on whatever it answers with.
     *
     * **The doomed page IS flushed first** — which K5b's first half did not do, and the second half
     * changes deliberately. While a delete could only be taken back from the notebook, encoding a
     * page's rasters for rows about to be soft-deleted was work for nothing. Now the same gesture that
     * deletes the page can put it back, and what comes back is whatever was last **saved**: the save
     * is debounced by seconds, so a mark made shortly before the long-press would otherwise be the
     * one thing the undo could not return. A few hundred milliseconds on a path the person has just
     * spent a dialog on is the cheaper side of that trade. A flush that fails parks its bytes and the
     * delete goes ahead, exactly as a turn's does.
     *
     * After the flush the timers come down and the page is marked clean, so no debounced save can
     * fire into the gap. A push already in the air when the row goes cannot be prevented from
     * here: it arrives at a host that no longer has the page and is refused with
     * `IllegalArgumentException("Unknown page")`, which [SketchSaver] parks. `end()` re-pushes the
     * park once, is refused again, and drops it with a line — which is correct: those pixels were
     * for a page the person deleted. The park is cleared here for the ordinary case, where something
     * was already sitting in it.
     *
     * Deleting the notebook's **only** page answers a fresh blank page (the session's own rule) and
     * this simply loads whatever comes back.
     */
    private suspend fun deletePageNow() {
        val here = currentPage ?: return
        val gone = here.pageKey
        val at = here.pageIndex
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the delete; its pixels are parked")
        saver.cancelTimers()
        saver.markClean()
        val to = callHost { it.deletePage(gone) }.getOrElse {
            Log.w(TAG, "the host would not delete the page: ${it.javaClass.simpleName}")
            showProblem(R.string.sketch_page_failed_title, R.string.sketch_page_failed_body)
            return
        }
        // Whatever was owed for the page that has gone is owed no longer — on **both** rasters:
        // each is its own slot and its own row, and the page they belonged to is not there any more.
        for (layer in SketchLayers.all) SketchSession.pending.clear(gone, layer)
        undo.remap(dropPixelsOf(gone, at))
        // The page can be asked back for from here (K5b's second half) — its entry names the host's
        // record of the delete, and the host still holds it.
        rememberStructural(SketchEdit.PageDeleted(to.structuralToken, gone, at), to)
        loadPage(to, firstLoad = false)
        Slog.d(TAG) { "deleted a page; now page ${to.pageIndex + 1}/${to.pageCount} ${to.rasterSizes()}" }
    }

    /**
     * Put a structural edit into this screen's history so its own undo gesture can reach it (K5b).
     *
     * The entry is only a **name** for the edit the host made and recorded on the notebook's own
     * stack ([SketchEdit.Structural]); a host that minted no name for it is one this screen cannot
     * ask to replay it, so nothing is recorded and the notebook's own undo is the only way back —
     * which is where it was before this half of K5b, and is said out loud rather than left as an
     * entry that would silently fail at the gesture.
     */
    private fun rememberStructural(edit: SketchEdit.Structural, landedOn: SketchPageState) {
        if (edit.token.isEmpty()) {
            Log.w(TAG, "the host named no page edit; it can only be undone from the notebook")
            return
        }
        undo.record(edit)
        Slog.d(TAG) { "page history: ${edit.javaClass.simpleName} at ${landedOn.pageIndex + 1}/${landedOn.pageCount}" }
    }

    /**
     * The history's re-index for a page that has **gone** — deleted, or an insert taken back (K5b).
     *
     * Two rules in one pass, and the difference between them is the whole care here:
     *
     * - **Pixel entries for that page are dropped, by KEY.** Their before-images belong to a row
     *   that is no longer on the paper, and the key is the only thing that certainly names the page
     *   that went (a recorded index can be a step stale). Their page may come back — but its pixels
     *   come back with it, out of the `.soil`, which is the copy that matters.
     * - **Structural entries are never dropped, not even the ones naming that same page.** They
     *   mirror the notebook's own stack one for one, and dropping one out of the middle would leave
     *   the two histories out of step — so "insert a page, then delete it" keeps both entries, and
     *   undoing twice does exactly what it says: the page comes back, then it goes again.
     */
    private fun dropPixelsOf(gone: String, at: Int): (SketchEdit) -> SketchEdit? = { edit ->
        if (edit is SketchEdit.RasterChanged && edit.pageKey == gone) null
        else edit.withIndex(PageTurn.reindexAfterDelete(edit.pageIndex, at))
    }

    /**
     * The delete door — a one-finger long-press on the paper, the Scratch Pad's shape exactly: **one
     * question rather than a one-row sheet**, because this screen has a single page action and a
     * sheet whose only row leads to a confirm would be two taps for one decision.
     *
     * The body is **content-aware**: the host is asked what else the page is carrying
     * ([ISketchHost.pageContent]) and the dialog names only that. A page with nothing on it but the
     * sketch gets no body at all — the user's call, and the right one: the sketch is on the glass in
     * front of the person about to delete it, so warning them about it would be explaining the
     * obvious while the real warning (handwriting they cannot see from here, a document they cannot
     * see at all) is the thing worth saying.
     */
    private fun confirmDeletePage() {
        if (!opened || closing) return
        // Ungated releaseRender() is safe here only because the long-press fired through
        // PageGestures' own gate: it never arms while the pen is active and re-checks at fire.
        paper.releaseRender()
        runPageOp { askAboutDelete() }
    }

    private suspend fun askAboutDelete() {
        val here = currentPage ?: return
        val bits = callHost { it.pageContent(here.pageKey) }.getOrElse {
            Log.w(TAG, "the page's content could not be read: ${it.javaClass.simpleName}")
            hostGone()
            return
        }
        if (isFinishing || isDestroyed || closing) return
        val ink = bits and SketchContract.PAGE_HAS_INK != 0
        val document = bits and SketchContract.PAGE_HAS_DOCUMENT != 0
        val body = when {
            ink && document -> R.string.delete_page_body_both
            ink -> R.string.delete_page_body_ink
            document -> R.string.delete_page_body_document
            else -> 0
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.delete_page_title)
            .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { deletePageNow() } }
            .setNegativeButton(R.string.cancel, null)
        if (body != 0) builder.setMessage(body)
        Dialogs.style(builder.create()).show()
    }

    // ── g-paper → the page ───────────────────────────────────────────────────

    private val paperListener: PaperListener = object : PaperListener {

        /**
         * **Deliberately ignored.** On a raster page the engine has already composited the mark into
         * its style's page image and dropped the object by the time this fires; it reports the stroke only so
         * that timestamps and counts come from one place. Storing it would be storing a row for
         * something that is pixels, and this screen has no rows at all. The undo entry for the same
         * mark is made from the pixels that were there **before** it — see [onRasterWillChange] and
         * [onPenLifted].
         */
        override fun onStrokeCommitted(stroke: Stroke) = Unit

        /**
         * One of the page's two rasters is **about to** change — the one moment the pixels that are
         * there can still be read, and so the one moment an undo entry can be made of them.
         *
         * **The layered form is the one the engine calls** (g-paper 0.1.39). The un-layered pair is
         * deliberately *not* overridden here: it is silent for ink by the engine's own design, so a
         * face that kept it would take a graphite before-image for a gel-pen mark — a corrupted undo
         * rather than a missing one.
         *
         * The rect is fed to the open contact's builder, which decides how much of it actually needs
         * reading: a rubbing sweep crosses the same cells dozens of times and the grid keeps each
         * one once ([RasterTiles]). The read is of **that layer** — a tile can only be swapped back
         * into the raster it came from.
         *
         * **One contact is one raster, and the layer change below is a belt on that rule.** A mark's
         * runs are all its style's layer and a sweep is all graphite, so a second layer inside one
         * open contact would be the engine breaking its own contract; if it ever happens the entry
         * gathered so far is closed on its own layer and a fresh one opens, which is honest in both
         * directions and never mixes two images' pixels into one patch list.
         */
        override fun onRasterWillChange(layer: RasterLayer, rect: Rect) {
            if (!opened || closing) return
            val state = currentPage ?: return
            val open = openEdit
            if (open != null && open.layer != layer) {
                Slog.d(TAG) { "one contact reported ${open.layer} then $layer; the first entry was closed" }
                closeOpenEdit()
            }
            val builder = openEdit
                ?: RasterEditBuilder(state.pageKey, state.pageIndex, layer, state.width, state.height).also {
                    openEdit = it
                    openEditReadNanos = 0L
                }
            val t0 = System.nanoTime()
            builder.touch(rect.left, rect.top, rect.right, rect.bottom) { cell ->
                // The engine's array, straight into the tile — no copy. It goes back to the engine
                // as it stands, and the swap leaves it holding the other side of the edit.
                paper.readPageRaster(
                    layer,
                    Rect(cell.left, cell.top, cell.left + cell.width, cell.top + cell.height),
                )?.pixels
            }
            openEditReadNanos += System.nanoTime() - t0
        }

        /**
         * One of the page's rasters changed — a mark composited at pen-up, or one batch of a rubbing
         * sweep. This is a raster page's news that anything happened, so it is what arms the save,
         * **for that raster alone**: a pencil scribble leaves the ink raster clean and the next save
         * does not re-encode it. The rect is not read: an image is written whole.
         */
        override fun onRasterChanged(layer: RasterLayer, rect: Rect) {
            if (!opened || closing) return
            saver.markDirty(layer)
            saver.schedule()
        }

        /**
         * The pen left the paper: one movement of the hand is over, so the entry for it is written
         * down. A contact too big to take back records nothing and says so once — it cannot happen
         * with a page-aligned grid, and an undo that half-restores a sweep would be worse than one
         * that admits it cannot.
         */
        override fun onPenLifted() {
            closeOpenEdit()
        }

        override fun onToolChanged(tool: Tool) = toolbar.sync(tool)
    }

    /** Close the open contact's before-image into one history entry. Idempotent. */
    private fun closeOpenEdit() {
        val builder = openEdit ?: return
        openEdit = null
        if (builder.tooBig) {
            Log.w(TAG, "that contact covered more than the history can hold; it cannot be taken back")
            return
        }
        val edit = builder.build() ?: return
        undo.record(edit)
        val readMs = openEditReadNanos / 1_000_000
        Slog.d(TAG) {
            "undo entry: ${layerName(edit.layer)}, ${edit.tiles.size} tiles, ${edit.bytes} B, " +
                "read $readMs ms on the main thread (${undo.undoBytes} B held)"
        }
    }

    // ── Gestures ─────────────────────────────────────────────────────────────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one — the notebook grows where you draw (K5b, the
            // notebook's and the pad's own rule). The count comes from the host's last answer, and
            // the host is asked either way, so a stale count costs one extra Binder call at worst.
            val here = currentPage ?: return@runPageOp
            if (here.pageIndex < here.pageCount - 1) turnPageNow(SketchContract.PAGE_NEXT)
            else insertPageNow(SketchContract.PAGE_NEXT)
        }
        override fun onFlipPrevious() = turnPage(SketchContract.PAGE_PREV)
        // K5b: a two-finger swipe makes a page on the side it went — the notebook's gesture, on the
        // notebook's pages, recorded on the notebook's undo stack.
        override fun onInsertAfter() = runPageOp { insertPageNow(SketchContract.PAGE_NEXT) }
        override fun onInsertBefore() = runPageOp { insertPageNow(SketchContract.PAGE_PREV) }
        override fun onUndo() = runPageOp { doReplay(undoing = true) }
        override fun onRedo() = runPageOp { doReplay(undoing = false) }
        // K5b: the long-press asks; it never acts — the pad's shape (one question, not a one-row
        // sheet), with a body that names what else goes with the page.
        override fun onPageSheetRequested() = confirmDeletePage()
        // Arc 33: a finger double-tap hides / shows the chrome. Nothing on this surface answers a
        // single tap, so there is no collision rule here.
        override fun onFingerDoubleTap(x: Float, y: Float) {
            // The shade panel belongs to the chrome that is flipping: it is hung off a button that
            // is about to be `GONE`, or off rows that are about to be.
            hidePaletteBar()
            toggleChrome()
        }
        // 2026-09-21: the one-finger swipe down — the notebook's Contents gesture, unassigned on
        // this surface — asks for the settle by hand: every mark still on the glass as the dither
        // it was drawn under goes to its true tone (g-paper 0.1.47's door). Since g-paper 0.1.52
        // (Phase 37) this and a page load are the ONLY settles: the 2.5 s pause, the tool and
        // shade picks, the rub, the undo and the chrome opens all used to settle, and each landed
        // a settle the hand had not asked for ("only … the swipe gesture, page flip, or closing
        // the sketch"). A finger, never the pen, so the settle may post straight to the panel;
        // pen-gated by the detector like every finger gesture here. Nothing waiting: a no-op.
        override fun onSwipeDown() {
            Slog.d(TAG) { "swipe down: settle the display" }
            paper.settleDisplay()
        }
        // Everything else stays the no-op default: no trail, no selection.
    }

    // ── Undo and redo ────────────────────────────────────────────────────────

    /**
     * Take one edit back — or put it back — by swapping pixels, **and never by reloading the page.**
     *
     * That last part is the one thing about this to get right. The entry's pixels go onto the paper
     * by a swap, and a reload afterwards would read the page's stored row — which still holds the
     * image as it was *before* the swap, because the save that writes the swapped page has not
     * happened yet — and put it straight back. An undo undone by its own page load, and one that
     * would read as the gesture simply not working.
     *
     * Three things about the swap are load-bearing:
     *
     * - **The page turn comes first.** An edit made on another page is walked back to through
     *   [PageTurn], flushing the page being left, so the image the swap lands on is the current one.
     *   A page that cannot be reached (the walk hit an edge, or the index has moved under us) drops
     *   the entry with a line rather than stamping a before-image onto the wrong page.
     * - **The pen-idle gate is waited on.** The swap presents a frame, and a frame presented while
     *   the pen is on the glass is withheld by the ink pipeline and never appears.
     * - **A mark that landed while it waited makes the swap wrong, so it is not made.** The entry
     *   goes back **beneath** what landed since ([UndoRedoStack.pushUndoBeneath]) — the order the
     *   history is true in — and the gesture simply has to be repeated, which now takes the new mark
     *   first.
     */
    private suspend fun doReplay(undoing: Boolean) {
        val edit = (if (undoing) undo.popUndo() else undo.popRedo()) ?: return
        val generation = undo.generation
        val applied = try {
            when (edit) {
                // Pixels: swapped here, on this screen, with nothing crossing the seam.
                is SketchEdit.RasterChanged -> applyEdit(edit, generation, undoing)
                // A page: the host's edit and the host's replay — this screen only names it (K5b).
                is SketchEdit.Structural -> applyStructural(edit, generation, undoing)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Failed mid-replay: put the entry back so the history never silently loses a step.
            // **The one put-back path**, for both kinds — a replay that cannot land throws rather
            // than restoring the entry itself, so there is exactly one line that decides this.
            if (undoing) undo.pushUndo(edit) else undo.pushRedo(edit)
            throw t
        }
        if (!applied) return
        if (undoing) undo.pushRedo(edit) else undo.pushUndo(edit)
    }

    /** Returns whether the swap actually landed — false means the entry was put back, or dropped. */
    private suspend fun applyEdit(edit: SketchEdit.RasterChanged, generation: Int, undoing: Boolean): Boolean {
        val here = currentPage ?: return false
        if (edit.pageKey != here.pageKey && !walkTo(edit)) {
            Log.w(TAG, "an edit's page could not be reached; the entry was dropped")
            return false
        }
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            Slog.d(TAG) { "a mark landed while the replay waited for the pen; the entry was put back beneath it" }
            return false
        }
        // The tiles are disjoint (the grid guarantees it), so order cannot matter — but the engine's
        // rule for overlapping patches is "reverse read order to undo, read order to redo", and
        // saying it here costs nothing and keeps the call honest against a future that overlaps.
        val tiles = if (undoing) edit.tiles.asReversed() else edit.tiles
        // **The entry's own layer**, because a `RasterPatch` carries none: a tile read from graphite
        // swapped into ink would paint the wrong image with the wrong pixels, and the engine could
        // not tell.
        paper.swapPageRaster(
            edit.layer,
            tiles.map { RasterPatch(Rect(it.left, it.top, it.left + it.width, it.top + it.height), it.pixels) },
        )
        saver.markDirty(edit.layer)
        saver.schedule()
        Slog.d(TAG) { "${if (undoing) "undo" else "redo"}: ${edit.tiles.size} ${layerName(edit.layer)} tiles swapped" }
        return true
    }

    /**
     * Take back — or put back — one **page** insert or delete (K5b, the user's follow-up decision of
     * 2026-09-15: his own undo gesture on this screen has to reverse a page delete, so he never has
     * to go back to the notebook to undo one).
     *
     * Nothing is swapped here. The entry is a name for an edit the **host** made and recorded on the
     * notebook's own undo stack, so the replay is a Binder call that runs the notebook's own undo
     * arm — the page comes back with its handwriting, its document and its sketch, at the position
     * it had — and the host moves its own stack in step. One history, seen from two screens.
     *
     * The order at the top is an ordinary turn's, because that is what this is about to be:
     *
     * - **This page's pixels go first.** The window is about to move to another page, and a save
     *   left in flight would land on the page being left. A flush that fails parks its bytes and the
     *   replay goes ahead rather than trapping the hand.
     * - **The pen-idle gate is waited on**, and a mark that landed while it waited makes the replay
     *   something the person did not ask for — so the entry goes back **beneath** what landed
     *   ([UndoRedoStack.pushUndoBeneath]) and the gesture simply has to be repeated. [applyEdit]'s
     *   rule exactly, for the same reason and in the same words.
     *
     * A token the host no longer knows (the showing was rebuilt underneath us, or the ledger's bound
     * let the oldest edit go) is an `IllegalArgumentException` and **drops the entry**: this screen
     * cannot name that edit any more, and the notebook's own undo still can. Anything else is a real
     * failure — it is said in a dialog and thrown, so [doReplay]'s one catch puts the entry back.
     */
    private suspend fun applyStructural(edit: SketchEdit.Structural, generation: Int, undoing: Boolean): Boolean {
        if (currentPage == null) return false
        if (!saver.flushAndAwait()) Log.w(TAG, "the page could not be saved before the page replay; its pixels are parked")
        paper.awaitPenIdle()
        if (isFinishing || isDestroyed || closing) return false
        if (undo.generation != generation) {
            undo.pushUndoBeneath(edit, generation)
            Slog.d(TAG) { "a mark landed while the page replay waited for the pen; the entry was put back beneath it" }
            return false
        }
        val to = callHost { if (undoing) it.undoPage(edit.token) else it.redoPage(edit.token) }.getOrElse { t ->
            if (t is IllegalArgumentException) {
                Log.w(TAG, "the host no longer knows that page edit; the entry was dropped")
                return false
            }
            showProblem(R.string.sketch_page_failed_title, R.string.sketch_page_failed_body)
            throw t
        }
        // Whether the page this entry is about is on the paper afterwards decides the arithmetic:
        // undoing a delete and redoing an insert bring it back, the other two take it away.
        //
        // **The entry's own index is the position, in both directions** — deliberately, rather than
        // the host's answer in the one direction that offers one. The two are the same number when
        // nothing has drifted, and taking it from one place is what makes an undo and the redo after
        // it exact inverses ([PageTurn.reindexAfterInsert] then `reindexAfterDelete` at the same
        // position is the identity): an entry re-indexed by two different numbers would leave the
        // history a page out after a round trip. The entry being replayed is already off the stack,
        // so it is never shifted by its own position.
        //
        // **A replay never drops pixel entries — only a fresh delete does** ([dropPixelsOf] is the
        // delete door's). Undoing an insert takes the page away, but every mark made on it since is
        // sitting on the REDO side, one step above this entry, waiting for the redo that brings the
        // page back — dropping them here is what lost the drawing in "insert, draw, undo, undo,
        // redo, redo" (the user's Nomad walk, 2026-09-15). And redoing a delete has nothing left to
        // drop: the delete itself dropped them, and a mark made after its undo clears the redo side
        // the delete entry was on. An entry for a page that is not on the paper cannot be replayed
        // in any case — its walk fails and drops it then, with a line.
        val at = edit.pageIndex
        val back = if (undoing) edit is SketchEdit.PageDeleted else edit is SketchEdit.PageInserted
        if (back) undo.remap { it.withIndex(PageTurn.reindexAfterInsert(it.pageIndex, at)) }
        else undo.remap { it.withIndex(PageTurn.reindexAfterDelete(it.pageIndex, at)) }
        loadPage(to, firstLoad = false)
        Slog.d(TAG) {
            "${if (undoing) "undo" else "redo"} of ${edit.javaClass.simpleName}: " +
                "now page ${to.pageIndex + 1}/${to.pageCount} ${to.rasterSizes()}"
        }
        return true
    }

    /**
     * Walk back to the page [edit] was made on, one `requestPage` at a time, bounded by the distance
     * it recorded ([PageTurn.maxSteps]) and stopped early by an edge. The page being left is flushed
     * first, exactly as an ordinary turn flushes it — and since 2026-09-19 in exactly the same way:
     * the copy is awaited, the encode is not, and the [loadPage] at the end waits for that page's
     * own pushes if the walk happens to have come back to one.
     */
    private suspend fun walkTo(edit: SketchEdit.RasterChanged): Boolean {
        var here = currentPage ?: return false
        val direction = PageTurn.directionTowards(here.pageIndex, edit.pageIndex) ?: return false
        if (!saver.flushForTurn()) Log.w(TAG, "the page's pixels could not be copied before the replay's turn; the raster stays dirty")
        var steps = PageTurn.maxSteps(here.pageIndex, edit.pageIndex)
        var moved = false
        while (steps-- > 0 && here.pageKey != edit.pageKey) {
            val next = callHost { it.requestPage(direction) }.getOrNull() ?: break
            if (PageTurn.isEdge(here.pageKey, next.pageKey)) break   // the edge: it is not that way
            here = next
            moved = true
        }
        // Whatever happened, the screen follows where the host's target actually ended up. A walk
        // that gave up halfway has already moved the host's window, and leaving the two disagreeing
        // about which page is showing would make the next turn read an edge that is not there.
        if (moved) loadPage(here, firstLoad = false)
        return here.pageKey == edit.pageKey
    }

    // ── "Bring in ink" (decision 8) ──────────────────────────────────────────

    /**
     * The page's own handwriting, composited onto the sketch — **as drawn, in black pen**, one undo
     * entry, and one way only: pixels never become strokes again. It lands in the **ink** raster,
     * where the rubber can never reach it: the bake is a black `PEN` and g-paper routes every style
     * but `PENCIL` to ink ([InkBake]'s own note says why that is the right home for it).
     *
     * The door **closes** any [RasterEditBuilder] still gathering before the `addStrokes` and again
     * after it, because a composited bake produces no pen-up and `onPenLifted` is what closes an
     * ordinary contact's. Opening one is the listener's, not the door's — a builder carries the
     * raster it is reading and only the engine knows which one a bake touches — so the builder fills
     * itself through [onRasterWillChange] while the call runs, on the layer the engine names.
     *
     * A page with no bare ink answers **0 chunks** — a legal answer, not a refusal — and gets an
     * alert that says so. A page dense past the transfer caps arrives cut; what arrived is baked.
     */
    private fun bringInInk() {
        if (!opened || closing) return
        runPageOp {
            val state = currentPage ?: return@runPageOp
            val count = callHost { it.requestInk(state.pageKey) }.getOrElse {
                Log.w(TAG, "the page's ink could not be staged: ${it.javaClass.simpleName}")
                showProblem(R.string.sketch_bring_ink_failed_title, R.string.sketch_bring_ink_failed_body)
                return@runPageOp
            }
            if (count == 0) {
                showProblem(R.string.sketch_no_ink_title, R.string.sketch_no_ink_body)
                return@runPageOp
            }
            val wire = callHost { host ->
                val all = ArrayList<WireStroke>()
                for (i in 0 until count) all += host.readInkChunk(i)
                all
            }.getOrElse {
                Log.w(TAG, "the page's ink could not be read: ${it.javaClass.simpleName}")
                showProblem(R.string.sketch_bring_ink_failed_title, R.string.sketch_bring_ink_failed_body)
                return@runPageOp
            }
            val strokes = InkBake.toBakedStrokes(wire)
            if (strokes.isEmpty()) {
                showProblem(R.string.sketch_no_ink_title, R.string.sketch_no_ink_body)
                return@runPageOp
            }
            composite(strokes)
            Slog.d(TAG) { "brought in ${strokes.size} stroke(s) over $count chunk(s)" }
        }
    }

    /**
     * Composite [strokes] onto the page as **one** history entry — the bake door's body, and the
     * debug fill door's. Any contact still gathering is closed first: its tiles are its own movement
     * of the hand, not this one's.
     *
     * The armed pen is put back afterwards ([SketchToolbar.restorePen]): a raster bake takes each
     * stroke's own colour, width and style rather than the armed pen's, so nothing is disturbed
     * today — and that one line is what keeps it true if the engine's bake ever starts reading the
     * pen instead.
     *
     * **The builder is no longer opened here** (arc 45 / G3). It carries a raster now, and which
     * raster a bake lands on is the engine's answer — `RasterLayer.of(style)` per stroke — not this
     * door's to guess. So the door only *closes* any contact still gathering, and the listener opens
     * the builder on the layer the engine names at the bake's first will-change. A bake whose
     * strokes are all one style (which both doors' are — "Bring in ink" is all `PEN`, the debug fill
     * door all the armed tool's) is still exactly one entry; a mixed one would record one entry per
     * raster, which is the honest shape for something that changed two images. (Which is also why
     * the page is no longer a parameter: the builder the listener opens reads [currentPage], the
     * one page a bake can possibly land on.)
     */
    private fun composite(strokes: List<Stroke>) {
        closeOpenEdit()
        try {
            paper.addStrokes(strokes)
        } finally {
            closeOpenEdit()
            toolbar.restorePen()
        }
        // A belt on the engine's own `onRasterChanged(layer, …)`, which has already marked each of
        // these: one entry per raster the bake actually touched, never both by default.
        for (layer in strokes.mapTo(LinkedHashSet()) { RasterLayer.of(it.style) }) saver.markDirty(layer)
        saver.schedule()
    }

    /**
     * **Debug only — a walk aid, never in a release build.** A long press on the page indicator
     * composites a fixed diagonal lattice of pencil strokes, recorded as one undo entry exactly like
     * a bake.
     *
     * It exists because adb cannot draw: the Supernote's firmware ink is invisible to `screencap`
     * and there is no gesture a shell can inject that puts graphite on a raster page. Without this
     * door an agent-driven walk can prove that a save *runs*, but never that a non-blank page
     * round-trips — which is the one thing worth proving about a surface whose drawing has no other
     * copy. The branch is `if (BuildConfig.DEBUG)` at the one call site, so release never compiles
     * the listener in.
     */
    private fun fillTestPattern() {
        if (!BuildConfig.DEBUG) return
        // Through the page-op lock like every other thing that touches the paper: a walk taps this
        // while a turn or a replay may still be in flight.
        runPageOp { fillTestPatternNow() }
    }

    private fun fillTestPatternNow() {
        val state = currentPage ?: return
        // The ARMED tools, not a fixed pencil (arc 44 / T3): the door's whole job is to put
        // something on a raster page that adb can prove, and a walk that wants to prove a shade or
        // a lead bakes as it previews needs the pattern drawn with the one that is chosen.
        val tools = toolbar.state
        val w = state.width.toFloat()
        val h = state.height.toFloat()
        val strokes = ArrayList<Stroke>(TEST_PATTERN_LINES)
        for (i in 0 until TEST_PATTERN_LINES) {
            val t = (i + 1f) / (TEST_PATTERN_LINES + 1f)
            val points = ArrayList<StrokePoint>(TEST_PATTERN_POINTS)
            for (p in 0 until TEST_PATTERN_POINTS) {
                val u = p / (TEST_PATTERN_POINTS - 1f)
                points += StrokePoint(
                    x = (w * 0.1f) + u * (w * 0.8f),
                    y = (h * t * 0.8f) + (h * 0.1f) + u * (h * 0.08f),
                    pressure = 0.5f,
                )
            }
            strokes += Stroke(
                id = "test-$i-${System.nanoTime()}",
                points = points,
                color = tools.penColor,
                width = tools.penWidth,
                style = tools.penStyle,
            )
        }
        composite(strokes)
        Log.w(TAG, "debug fill door: ${strokes.size} test strokes composited")
        logEncodeTable(RasterLayer.of(tools.penStyle))
    }

    /**
     * **Debug only — G3's measurement instrument, and the whole answer to the phase's open
     * question** ([RasterImage.WEBP_EFFORT]): what a lossless WebP of a real, freshly filled page
     * costs in bytes and in milliseconds at each effort, with PNG beside it for the number this arc
     * is trading against.
     *
     * The copy is taken on Main like every other page copy (the engine only touches its images
     * there), the encoding happens on IO because it is six page encodes in a row, and every row
     * lands in `logcat` at `Log.w` so a walk can read the table off a device with nothing else to
     * build. **Bytes and milliseconds only — never a pixel.**
     */
    private fun logEncodeTable(layer: RasterLayer) {
        if (!BuildConfig.DEBUG) return
        val copy = paper.getPageRaster(layer) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (row in RasterImage.encodeTable(copy).lineSequence()) {
                    if (row.isNotEmpty()) Log.w(TAG, "encode table (${layerName(layer)}): $row")
                }
            } finally {
                copy.recycle()
            }
        }
    }

    // ── Page operations ──────────────────────────────────────────────────────

    /** Serialise every page / undo / bake / flush operation; ignore anything while not open or once
     *  closing. */
    private fun runPageOp(block: suspend () -> Unit) {
        if (!opened || closing) return
        lifecycleScope.launch {
            pageOps.withLock {
                if (!opened || closing) return@withLock
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "page op failed", t)
                }
            }
        }
    }

    // ── The host binder ──────────────────────────────────────────────────────

    /** One Binder call, off the main thread, with every failure captured — the caller decides what a
     *  failure means, because "the host is gone" and "that page has no ink" arrive the same way. */
    private suspend fun <T> callHost(block: (ISketchHost) -> T): Result<T> {
        val host = SketchSession.host ?: return Result.failure(IllegalStateException("no showing"))
        return try {
            Result.success(withContext(Dispatchers.IO) { block(host) })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** The notebook is no longer reachable: say so, then leave through the handoff like every exit.
     *  The park keeps whatever pixels never landed — `end()` is where they get their last try. */
    private fun hostGone() {
        if (closing || isFinishing || isDestroyed) return
        closing = true
        saver.cancelTimers()
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_host_gone_title)
                .setMessage(R.string.sketch_host_gone_body)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    /** A screen that opened nothing is explained, not toasted — then it leaves the way every exit
     *  does. */
    private fun failOpen() {
        openingOverlay?.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_host_gone_title)
                .setMessage(R.string.sketch_host_gone_body)
                .setPositiveButton(R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create()
        ).show()
    }

    // ── The showing's two hooks ──────────────────────────────────────────────

    private val flushHook = object : SketchSession.FlushHook {
        override fun flushBlocking() = saver.flushBlocking()
        override fun pushBlocking(pageKey: String, layer: RasterLayer, bytes: ByteArray) =
            saver.pushBlocking(pageKey, layer, bytes)
    }

    /**
     * The host restarted. A page key is the page's own id, so it is still the same page on the other
     * side — there is no key to re-check the way the document editor has to. What is owed is
     * whatever was parked, and whatever is on the glass that the new host has never seen.
     */
    private val beginListener = SketchSession.BeginListener {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            Slog.d(TAG) { "host reconnected — re-offering what is owed" }
            saver.retryParked()
            saver.saveNow()
        }
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    /**
     * Every exit — Back, the top bar's Back, Show pages — **awaits the flush** and only then hands
     * the pipeline off and finishes. The host's result callback runs `end()` → unbind → revoke the
     * moment we finish, and a save left in flight would hit a revoked binder.
     *
     * A flush that fails is a drawing with no other copy, so it is a **dialog**, not a log line:
     * Try again, or Leave anyway — and "leave anyway" is not "lose", because the pixels stay parked
     * and `end()` gets one more try at them on the binder that is still valid.
     */
    private fun exit(resultCode: Int) {
        if (closing) return
        closing = true
        dismissCollapsed()
        hidePaletteBar()
        saver.cancelTimers()
        leaveWhenFlushed(resultCode)
    }

    private fun leaveWhenFlushed(resultCode: Int) {
        appScope.launch {
            val ok = withContext(NonCancellable) { saver.flushForExit() }
            if (isFinishing || isDestroyed) return@launch
            if (ok) finishWithHandoff(resultCode) else askAboutUnsaved(resultCode)
        }
    }

    private fun askAboutUnsaved(resultCode: Int) {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.sketch_not_saved_title)
                .setMessage(R.string.sketch_not_saved_body)
                .setPositiveButton(R.string.sketch_try_again) { _, _ -> leaveWhenFlushed(resultCode) }
                .setNegativeButton(R.string.sketch_leave_anyway) { _, _ -> finishWithHandoff(resultCode) }
                .setCancelable(false)
                .create()
        ).show()
    }

    override fun onScreenPaused() {
        if (!opened || closing) return
        // A durability point while backgrounded, on the saver's own scope — ours is cancelled at
        // ON_DESTROY, and a half-drawn page is the one thing worth surviving that.
        saver.saveNow()
    }

    override fun onScreenDestroyed() {
        // A refused caller (`HostCallerCheck` finished us before anything was built) still gets this
        // callback: nothing below exists then, and a `lateinit` throw here kills the whole extension
        // process — with the LEGITIMATE face in it (walked on the Nomad, K5).
        if (!::saver.isInitialized) return
        saver.cancelTimers()
        // The hooks belong to this screen and to nothing else: the service must not call into a
        // destroyed Activity's saver at `end()`.
        SketchSession.flushHook = null
        SketchSession.beginListener = null
    }

    private companion object {
        const val TAG = "SketchActivity"

        /** Outlives the Activity so a flush in flight always completes. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /**
         * Whether a page change ends with a full panel refresh ([refreshPanelAfterTurn]).
         *
         * **The user's walk decides whether this stays** — and, if it does, whether
         * [EinkRefresh.MODE] should be something other than the 0 the probe used. It is a constant
         * rather than a setting because there is nothing for a person to choose here: either the
         * panel is better for it on every turn or it is better for it on none.
         */
        const val REFRESH_ON_TURN = false

        /** The debug fill door's lattice — enough to be unmistakable in a `screencap`, few enough to
         *  composite in one call. */
        const val TEST_PATTERN_LINES = 12
        const val TEST_PATTERN_POINTS = 24
    }
}

/** A raster's name for a log line — a word, never a pixel (arc 45 / G3). */
private fun layerName(layer: RasterLayer): String = if (layer == RasterLayer.INK) "ink" else "graphite"

/**
 * What a page is carrying, for a log line: both rasters' byte totals, graphite first
 * ([SketchLayers.all]'s order), in the shape `(g 133482 B, ink 21000 B)`.
 *
 * **Counts, never content** — a byte total says how much was drawn and nothing about what.
 */
private fun SketchPageState.rasterSizes(): String = "(g $graphiteBytes B, ink $inkBytes B)"
