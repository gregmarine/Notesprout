package com.notesprout.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.NotebookMetaStore
import com.notesprout.android.data.NotebookCompactor
import androidx.room.withTransaction
import com.notesprout.android.core.BitmapDecode
import com.notesprout.android.core.DocumentPreferences
import com.notesprout.android.core.Slog
import com.notesprout.android.core.markdown.DocumentDraft
import com.notesprout.android.crypto.EncryptionInfo
import com.notesprout.android.crypto.KeyResolver
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.crypto.PassphraseCache
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilMigrator
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.DayHistoryRepository
import com.notesprout.android.data.DocumentRepository
import com.notesprout.android.data.copyPageAfter
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.TextObject
import com.notesprout.android.data.TextRender
import com.notesprout.android.data.LineObject
import com.notesprout.android.data.LineOrientation
import com.notesprout.android.data.LineRender
import com.notesprout.android.data.toLineRender
import com.notesprout.android.data.assembleHeadingResolved
import com.notesprout.android.data.insertHeadingSubtree
import com.notesprout.android.data.insertTextSubtree
import com.notesprout.android.data.loadHeadingsSubtree
import com.notesprout.android.data.loadTextsSubtree
import com.notesprout.android.data.replaceHeadingSubtree
import com.notesprout.android.data.replaceTextSubtree
import com.notesprout.android.data.toRow
import com.notesprout.android.data.pageData
import com.notesprout.android.data.notebookMetadata
import com.notesprout.android.data.templateDataOrNull
import com.notesprout.android.data.writeOnto
import com.notesprout.android.data.LAYER_FLAGS_DEFAULT
import com.notesprout.android.data.index.toNotebookObject
import com.notesprout.android.data.toShapeRender
import com.notesprout.android.data.updateColumns
import com.notesprout.android.data.LineStyle
import com.notesprout.android.data.LinkChrome
import com.notesprout.android.data.LinkRender
import com.notesprout.android.data.insertLinkSubtree
import com.notesprout.android.data.loadLinkSubtree
import com.notesprout.android.data.loadLinksSubtree
import com.notesprout.android.data.replaceLinkSubtree
import com.notesprout.android.data.translate
import com.notesprout.android.data.LinkTarget
import com.notesprout.android.data.toEmbeddedLine
import com.notesprout.android.data.toPayload
import com.notesprout.android.data.toLineRender
import com.notesprout.android.data.TYPE_HEADING
import com.notesprout.android.data.TYPE_LINE
import com.notesprout.android.data.TYPE_LINK
import com.notesprout.android.data.TYPE_SHAPE
import com.notesprout.android.data.TYPE_STICKY_NOTE
import com.notesprout.android.data.TYPE_TEXT
import com.notesprout.android.data.ShapeRender
import com.notesprout.android.data.StickyNoteRender
import com.notesprout.android.data.insertStickyNoteSubtree
import com.notesprout.android.data.loadStickyNotesSubtree
import com.notesprout.android.data.replaceStickyNoteSubtree
import com.notesprout.android.data.toStickyNoteObject
import com.notesprout.android.core.markdown.TextObjectRenderer
import android.text.TextPaint
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookMetadata
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.PageData
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.StrokeData
import com.notesprout.android.data.strokeBlob
import com.notesprout.android.data.toBoundingBoxJson
import com.notesprout.android.data.toStrokeRow
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.ScratchpadRepository
import com.notesprout.android.notebook.ScratchpadPreferences
import com.notesprout.android.data.index.ObjectType
import com.notesprout.android.data.index.templateObject
import com.notesprout.android.data.links.BackEntry
import com.notesprout.android.data.links.LinkBackStack
import com.notesprout.android.data.recents.RecentsManager
import com.notesprout.android.data.recents.TemplateRecentsManager
import com.notesprout.android.notebook.RecentsDialog
import kotlinx.coroutines.CancellationException
import com.notesprout.android.state.AppStateManager
import com.notesprout.android.state.NotebookOpenFailure
import com.notesprout.android.state.AppSurface
import com.notesprout.android.state.AppViewState
import com.notesprout.android.state.SurfaceEntry
import com.notesprout.android.state.SurfaceStack
import com.notesprout.android.data.soilFile
import com.notesprout.android.databinding.ActivityNotebookBinding
import com.notesprout.android.databinding.DialogEditHeadingTextBinding
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.STICKY_NOTE_ICON_SIZE_DP
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.SnapPreferences
import com.notesprout.android.notebook.ToolPreferencesManager
import com.notesprout.android.notebook.LassoGeometry
import com.notesprout.android.notebook.LineObjectDialog
import com.notesprout.android.notebook.TextEditDialog
import com.notesprout.android.notebook.CustomizeToolbarDialog
import com.notesprout.android.notebook.ToolbarOverflowManager
import com.notesprout.android.notebook.ToolbarLayoutManager
import com.notesprout.android.notebook.PenColorPreferences
import com.notesprout.android.notebook.PenColorPanelController
import com.notesprout.android.notebook.CustomColorDialog
import com.notesprout.android.data.toolbar.ToolbarAxis
import com.notesprout.android.data.toolbar.ToolbarConfig
import com.notesprout.android.data.toolbar.ToolbarPlacement
import com.notesprout.android.data.toolbar.ToolbarPreferencesManager
import com.notesprout.android.data.deepCopy
import com.notesprout.android.data.translated
import com.notesprout.android.history.UndoRedoAction
import com.notesprout.android.history.UndoRedoManager
import com.notesprout.android.recognition.HandwritingRecognizer
import com.notesprout.android.recognition.HandwritingRecognizerProvider
import com.notesprout.android.toc.TocDialog
import com.notesprout.android.toc.TocRepository
import com.notesprout.android.core.isBooxDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import java.io.File
import java.util.UUID

class NotebookActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Notesprout"

        /** Page-load stroke counts at/above this parse in parallel across cores; below, inline. */
        private const val PARALLEL_PARSE_THRESHOLD = 300

        /** Max pages of parsed strokes held in the prefetch LRU cache (current + neighbours + slack). */
        private const val MAX_CACHED_PAGES = 6

        /** Instance-state keys for an in-flight document-editor session (see onSaveInstanceState). */
        private const val STATE_DOCUMENT_PAGE_ID    = "document_page_id"
        private const val STATE_DOCUMENT_PAGE_INDEX = "document_page_index"
        private const val STATE_DOCUMENT_SRC        = "document_src_updated_at"

        /** Intent extra key — the index UUID for the notebook (ObjectEntity id). */
        const val EXTRA_NOTEBOOK_ID   = "notebook_id"
        /** Intent extra key — the display name for the notebook. */
        const val EXTRA_NOTEBOOK_NAME = "notebook_name"
        /**
         * Intent extra (boolean) — set when this notebook is opened by *following a link* (or a
         * link back-swipe). A "fresh" open from MainActivity/Recents omits it, which is the signal
         * to clear the [LinkBackStack]; a via-link open preserves the trail. See onCreate.
         */
        const val EXTRA_VIA_LINK = "via_link"
        /**
         * Intent extra (String) — a page row id to open to instead of the last-opened page. Set when
         * following an other-notebook *page* link (or a cross-notebook back-swipe). If the page is
         * missing the last-opened page is shown and a toast is surfaced. See [loadStrokes].
         */
        const val EXTRA_INITIAL_PAGE_ID = "initial_page_id"

        /**
         * Intent extra (Boolean) — when true, paste [CalendarTransfer.pending] onto the current page
         * once the initial page has loaded. Set by CalendarActivity's "Send to Notebook → other".
         */
        const val EXTRA_PASTE_PENDING = "paste_pending"

        /** Set once recovery has been offered, so a still-failing open can't loop the prompt.
         *  Lives on the Intent because [recreate] is how a recovered notebook is reopened. */
        const val EXTRA_RECOVERY_ATTEMPTED = "recovery_attempted"

        // One-finger page-turn gesture thresholds.
        private const val PAGE_SWIPE_MIN_DISTANCE_FRAC  = 0.30f  // min |dx| to qualify at all
        private const val PAGE_SWIPE_LONG_DISTANCE_FRAC = 0.50f  // |dx| that qualifies regardless of velocity
        private const val PAGE_SWIPE_MIN_VELOCITY_MULT  = 1.0f   // x scaledMinimumFlingVelocity
    }

    /**
     * Result of [loadCurrentPage]: everything needed to display a page. The GPU RenderNode
     * committed layer records directly from these in-memory lists (see [displayPage]).
     */
    private data class PageLoadResult(
        val strokes: List<LiveStroke>,
        val templateBitmap: Bitmap?,
        val headings: List<HeadingStroke> = emptyList(),
        val textObjects: List<TextRender> = emptyList(),
        val lineObjects: List<LineRender> = emptyList(),
        val links: List<LinkRender> = emptyList(),
        val stickyNotes: List<StickyNoteRender> = emptyList(),
        val shapeObjects: List<com.notesprout.android.data.ShapeRender> = emptyList(),
    )

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var drawingView: NotebookView
    private lateinit var overflowManager: ToolbarOverflowManager
    private lateinit var toolbarLayoutManager: ToolbarLayoutManager

    /** The pen-colour swatch panel docked to [btnPen]. See [togglePenColorPanel]. */
    private lateinit var penColorPanel: PenColorPanelController
    private var toolbarConfig: ToolbarConfig = ToolbarConfig()
    /** True while we're waiting for ACTION_UP to close the overflow menu after a button tap. */
    private var overflowCloseOnUp = false
    /** True when the DOWN that set [overflowCloseOnUp] landed on [btnInsertShape] — the overflow
     *  must stay open so all three toolbars (main, overflow, shapes) remain on screen together. */
    private var overflowKeepOpenForShapePicker = false
    private var isEraserActive = false

    // ── Snap-to-guide mode ────────────────────────────────────────────────────
    private var isSnapEnabled = false

    // ── Lasso selection state ─────────────────────────────────────────────────
    private var isLassoMode = false
    private var isLassoEraserMode = false
    private var isShapeTransformMode = false
    /** True when lasso mode was entered via a smart-lasso gesture (not by tapping the lasso button).
     *  Dismissing the selection exits lasso mode and returns to pen mode instead of staying in lasso. */
    private var isSmartLassoSession = false
    /** IDs of objects selected by the most recent lasso gesture. */
    val selectedObjectIds = mutableSetOf<String>()

    // ── Link objects (Session 2 target picker) ────────────────────────────────
    // The picker round-trips through an Activity result, so the work-in-progress is stashed here
    // while it's open: the captured selection to wrap (create) and/or the id of the link being
    // edited. [pendingLinkEditId] non-null ⇒ the open picker is editing that link in place.
    private var pendingLinkStrokes:     List<LiveStroke>    = emptyList()
    private var pendingLinkHeadings:    List<HeadingStroke> = emptyList()
    private var pendingLinkTexts:       List<TextRender>    = emptyList()
    private var pendingLinkLines:       List<LineRender>    = emptyList()
    private var pendingLinkShapes:      List<ShapeRender>   = emptyList()
    private var pendingLinkEditId:      String?             = null
    private var pendingLinkStrokesOnly: Boolean             = false

    // ── One-finger page swipe state ───────────────────────────────────────────
    // Deliberate full-width horizontal swipe turns pages.  Distance is the primary
    // gate: ≥30% screen width required; ≥50% qualifies regardless of velocity; a
    // quick flick covering ≥30% also qualifies.  Horizontal dominance is always
    // required.  If a second finger lands mid-swipe and the gesture already
    // qualifies, it is committed immediately (palm tolerance) — otherwise cancelled.
    private var pageSwipeActive = false
    private var pageSwipeStartX = 0f
    private var pageSwipeStartY = 0f
    private var pageSwipeVelocityTracker: VelocityTracker? = null

    // Two-finger horizontal swipe → insert a new page before/after the current page.
    private var twoFingerSwipeActive = false
    private var twoFingerSwipeStartX = 0f
    private var twoFingerSwipeStartY = 0f
    private var twoFingerSwipeVelocityTracker: VelocityTracker? = null

    // ── Multi-finger double-tap: 2-finger = undo, 3-finger = redo ─────────────
    // Stationary (tap slop) and short (long-press timeout). Movement guard ensures
    // no conflict with 2-finger swipe (page insert). Peak-count tracking ensures
    // 3-finger gestures never trigger the 2-finger undo branch.
    private var mfTapPeakCount = 0
    private var mfTapArmed = false
    private var mfTapMoved = false
    private var mfTapDownTime = 0L
    private var mfTapCentroidStartX = 0f
    private var mfTapCentroidStartY = 0f
    private var twoFingerTapFirstTime = 0L
    private var twoFingerTapFirstX = 0f
    private var twoFingerTapFirstY = 0f
    private var threeFingerTapFirstTime = 0L
    private var threeFingerTapFirstX = 0f
    private var threeFingerTapFirstY = 0f

    // ── Toolbar hide/show double-tap gesture (Session 7) ──────────────────────
    // A one-finger double-tap on the canvas collapses/restores the toolbar. Finger-only and
    // tap-vs-drag gated (short, near-stationary, single-pointer) so it never collides with the
    // page-swipe / two-finger page-insert gestures, both of which are large/multi-touch. Stylus
    // events never reach the detector, so writing/erasing are untouched.
    private var toggleTapDownTime = 0L
    private var toggleTapDownX = 0f
    private var toggleTapDownY = 0f
    private var toggleTapMoved = false
    private var toggleFirstTapTime = 0L
    private var toggleFirstTapX = 0f
    private var toggleFirstTapY = 0f
    private val toggleTouchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val toggleDoubleTapSlopPx by lazy { ViewConfiguration.get(this).scaledDoubleTapSlop }

    // ── Page long-press menu gesture ──────────────────────────────────────────
    // A one-finger stationary long-press on the canvas opens the "Page" action menu (template,
    // page index, insert/delete/copy/paste, erase) — the actions that used to be toolbar buttons.
    // Armed on ACTION_DOWN (single finger, on the canvas, not over chrome or a followable
    // link/sticky, and not in a selection/placement mode); a posted runnable fires at the
    // long-press timeout while the finger is still held. Any movement past touch slop, a second
    // finger, an UP/CANCEL, or the pen-activity gate cancels it. Finger-only: the stylus never
    // reaches this, so writing is untouched. A long-press is longer than the toolbar-toggle tap
    // window, so the two gestures never collide.
    private val pageMenuLongPressHandler = Handler(Looper.getMainLooper())
    private var pageMenuLongPressRunnable: Runnable? = null
    private var pageMenuDownX = 0f
    private var pageMenuDownY = 0f
    /** True when the toggle gesture's DOWN landed inside a followable link bbox — suppresses the
     *  double-tap-hide so a link follow isn't also a toolbar toggle (Session 4, plan step 4). */
    private var toggleDownOnLink = false

    // ── Link follow gesture (Session 4) ───────────────────────────────────────
    // A finger single-tap inside a link bbox follows the link, in pen mode only [A4]. Tap-vs-drag
    // gated like the toolbar-toggle gesture; stylus events never reach it, so writing over a link
    // with the pen is untouched. The vertical swipe-up that walks the back-stack is handled inside
    // the one-finger page-swipe detector ([evaluateSwipeUpBack]).
    private var linkTapDownX = 0f
    private var linkTapDownY = 0f
    private var linkTapDownTime = 0L
    private var linkTapMoved = false
    /** The link the current finger gesture started on (pen mode only), or null. */
    private var linkTapCandidate: LinkRender? = null
    /** The sticky note the current finger gesture started on (pen mode only), or null. */
    private var stickyNoteTapCandidate: StickyNoteRender? = null

    /**
     * True while finger gestures are being ignored because the pen owns the surface
     * ([NotebookView.isPenActive]). Latched so [cancelFingerGestures] runs once per suppression
     * window rather than on every palm MOVE event.
     */
    private var fingerGesturesSuppressed = false

    /** Index UUID of the open notebook — set once in onCreate from EXTRA_NOTEBOOK_ID. */
    private var notebookId: String = ""

    /** This Activity instance's identity on the [SurfaceStack]. */
    private var surfaceToken: String = ""

    /** Derived absolute path to the .soil file — set once in onCreate. */
    private var notebookSoilPath: String? = null

    /** Display name from EXTRA_NOTEBOOK_NAME — used for the activity title. */
    private var notebookDisplayName: String = ""

    /** Lazy access to the index repository for snapshot/page-count/updatedAt sync. */
    private val indexRepo: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }
    private val dayHistoryRepo: DayHistoryRepository by lazy { DayHistoryRepository() }

    /** Lazy access to the scratch pad repository — used for "Send to Scratch Pad". */
    private val scratchpadRepo: ScratchpadRepository by lazy {
        ScratchpadRepository(NotesproutIndex.db(), NotesproutIndex.scratchpadDao())
    }

    /** Last page count pushed to the index; -1 = not yet synced. */
    private var lastSyncedPageCount: Int = -1

    /** Room DB instance for the open notebook. Null before open and after close. */
    private var soilDatabase: SoilDatabase? = null
    private var sessionStartTime: Long = 0L

    /** Resolved SQLCipher key for the open notebook; null for plaintext notebooks. */
    private var soilKey: String? = null

    /** Encryption metadata for the open notebook — set during the async open, before loadStrokes(). */
    private var encryptionInfo: EncryptionInfo = EncryptionInfo.NONE

    /**
     * In-memory notebook metadata row.  Loaded once when the notebook is opened.
     * Holds the notebook UUID (used as parentId for all pages) and the last-opened
     * page UUID (used to restore position on re-open).
     * Null for notebooks that pre-date the metadata row (falls back gracefully).
     */
    private var notebookMetadata: NotebookMetadata? = null

    /** In-memory pin state, read synchronously by the Page menu; loaded on open, updated on toggle. */
    private var notebookPinned = false

    // ── Real-time recognition (RTR) ─────────────────────────────────────────────

    /** Non-null only while RTR is enabled for this notebook and the recognizer is ready. */
    private var rtrScheduler: com.notesprout.android.recognition.RtrScheduler? = null

    // ── Page state ────────────────────────────────────────────────────────────

    /** All live pages in sorted order. Populated (and refreshed) by [setupPageIds]. */
    private var pages: MutableList<NotebookObject> = mutableListOf()

    /** Index into [pages] for the currently displayed page. */
    private var currentPageIndex: Int = 0

    /** ID of the currently displayed page row. Set by [setupPageIds]. */
    private var currentPageId: String = ""

    /** ID of the content layer under [currentPageId]. Set by [setupPageIds]. */
    private var currentLayerId: String = ""

    // ── Prefetch stroke cache ─────────────────────────────────────────────────
    /** Parsed strokes for a page + the [getMaxContentUpdatedAt] version they were parsed at. */
    private class CachedStrokes(val strokes: List<LiveStroke>, val version: Long)

    /**
     * LRU cache of parsed strokes keyed by pageId, so a page turn to a prefetched (or recently
     * visited) neighbour skips the ~260ms JSON parse. Bounded to [MAX_CACHED_PAGES]. Entries are
     * validated on use against the DB content version, so an edit (which bumps the version) forces a
     * re-parse — sharing the cached [LiveStroke]s with the view is safe because nothing mutates a
     * stroke's points in place (moves create new strokes). Guarded by [strokeCacheLock] (touched from
     * the load path and the prefetch coroutine).
     */
    private val strokeCache = object : LinkedHashMap<String, CachedStrokes>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStrokes>): Boolean =
            size > MAX_CACHED_PAGES
    }
    private val strokeCacheLock = Any()
    private var prefetchJob: Job? = null

    // ── Persisted stroke tracking ─────────────────────────────────────────────

    /**
     * IDs of strokes confirmed to exist in the DB for the current page/layer.
     * Populated by [deserializeStrokesFromDb] after each page load and extended by
     * [saveStrokes] after each incremental save.  Reduced by the [onStrokeErased]
     * callback and cleared by the page-clear handler.
     *
     * Avoids redundant [StrokeData.toJson] serialization in [saveStrokes] — the
     * IGNORE in INSERT OR IGNORE skips already-present rows at the SQL level, but
     * JSON encoding still ran for every stroke before Fix #2a.  Tracking persisted
     * IDs cuts serialization work to zero for strokes already in the DB.
     *
     * Accessed from both the IO thread ([loadStrokesFromDb], [saveStrokes]) and the
     * main thread ([onStrokeErased], clear handler).  Access is sequential in normal
     * usage (navigation/save/erase are mutually exclusive user actions), so a plain
     * [MutableSet] is sufficient.  [saveStrokes] takes a snapshot before the
     * transaction to insulate against any edge-case main-thread mutation.
     */
    private val persistedStrokeIds = mutableSetOf<String>()

    /**
     * The template bitmap currently displayed on the canvas.  Kept in sync by [displayPage]
     * so the undo/redo optimised stroke path can pass it to [buildRenderBitmap] without
     * re-reading the DB.  Null = plain white background.
     */
    private var currentTemplateBitmap: Bitmap? = null

    // ── Undo/redo ─────────────────────────────────────────────────────────────

    /**
     * Notebook-level undo/redo history.  Replaced wholesale on [onStart] restoration;
     * cleared and file deleted on [closeNotebook].
     */
    private var undoRedoManager = UndoRedoManager()

    // ── Template library pick launcher ───────────────────────────────────────

    /**
     * Launched when the user taps "Browse Templates…" in the in-notebook template picker.
     * Opens [TemplateBrowserActivity] in MODE_PICK; on return applies the selected template
     * (or Blank) to the current page.
     */
    private val templatePickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val templateId = result.data?.getStringExtra(TemplateBrowserActivity.RESULT_TEMPLATE_ID)
            ?: return@registerForActivityResult
        onTemplatePicked(templateId)
    }

    /**
     * Called on the main thread after the user picks a template from [TemplateBrowserActivity].
     * [templateId] is "" for Blank; otherwise a library TemplateObject id.
     */
    private fun onTemplatePicked(templateId: String) {
        if (templateId.isEmpty()) {
            // Blank — clear template immediately.
            applyTemplateToCurrentPage("", null)
            return
        }
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val (soilRowId, bitmap) = withContext(Dispatchers.IO) {
                insertLibraryTemplateIntoSoil(templateId, db)
            }
            if (soilRowId.isNotEmpty()) {
                applyTemplateToCurrentPage(soilRowId, bitmap)
                TemplateRecentsManager.recordUse(this@NotebookActivity, templateId)
            }
        }
    }

    /**
     * Loads a library template by [libraryTemplateId] from the global index, encodes it into a
     * `.soil` `type="template"` row, and inserts it into [db].
     *
     * Returns a [Pair] of the new soil row id (or "" on failure) and the decoded full-resolution
     * Bitmap (or null). The Bitmap is decoded via [BitmapDecode.decodeSampled] (bounded).
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun insertLibraryTemplateIntoSoil(
        libraryTemplateId: String,
        db: SoilDatabase,
    ): Pair<String, Bitmap?> {
        val entity = indexRepo.getTemplate(libraryTemplateId) ?: run {
            Slog.d(TAG) { "insertLibraryTemplateIntoSoil: entity not found for $libraryTemplateId" }
            return Pair("", null)
        }
        val tObj = entity.templateObject() ?: run {
            Slog.d(TAG) { "insertLibraryTemplateIntoSoil: failed to parse TemplateObject for $libraryTemplateId" }
            return Pair("", null)
        }
        if (tObj.image.isEmpty()) {
            Slog.d(TAG) { "insertLibraryTemplateIntoSoil: empty image for $libraryTemplateId" }
            return Pair("", null)
        }

        // Decode full-res bitmap for immediate display (bounded to avoid OOM).
        val imageBytes = Base64.decode(tObj.image, Base64.DEFAULT)
        val bitmap = BitmapDecode.decodeSampled(imageBytes, BitmapDecode.MAX_DIMENSION, BitmapDecode.MAX_DIMENSION)

        val newId = UUID.randomUUID().toString()
        val now   = System.currentTimeMillis()
        val fullScreenBounds = BoundingBox(0f, 0f, tObj.width.toFloat(), tObj.height.toFloat()).toJson()
        val notebookParentId = notebookMetadata?.id ?: MainActivity.NIL_UUID

        // Columnar (Phase 2b) template row: name→text, decoded image bytes→blob, size→boundingBox.
        db.notebookDao().insertObject(
            NotebookObject(
                id          = newId,
                type        = "template",
                parentId    = notebookParentId,
                boundingBox = fullScreenBounds,
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = "",
                text        = entity.name,
                blob        = imageBytes,
            ),
        )
        Slog.d(TAG) { "insertLibraryTemplateIntoSoil: inserted soil row $newId for library template '$libraryTemplateId'" }
        return Pair(newId, bitmap)
    }

    // ── Copy/paste clipboard ──────────────────────────────────────────────────

    /** Page ID waiting to be pasted. Null means clipboard is empty. */
    private var pendingCopyPageId: String? = null

    // ── Lasso copy/paste toolbar state ───────────────────────────────────────

    /** btnLasso's bounds in root-view coordinates — computed after layout; the lasso popup is
     *  anchored to whichever edge faces away from the bar. */
    private var lassoToolbarAnchor: RectF? = null

    // ── Page index launcher ───────────────────────────────────────────────────

    private val pageIndexLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data

            // Push paste actions recorded during the index session onto the undo stack.
            // Group the whole index-session paste batch into ONE undo step (mirrors PagesDeleted)
            // so a single undo reverses every page pasted together.
            val pastedIds     = data?.getStringExtra(PageIndexActivity.EXTRA_PASTED_PAGE_IDS)
            val pastedIndices = data?.getStringExtra(PageIndexActivity.EXTRA_PASTED_PAGE_INDICES)
            if (!pastedIds.isNullOrEmpty() && !pastedIndices.isNullOrEmpty()) {
                val ids     = pastedIds.split(",")
                val indices = pastedIndices.split(",").mapNotNull { it.toIntOrNull() }
                val refs = ids.zip(indices).map { (pageId, pageIndex) ->
                    UndoRedoAction.PastedPageRef(pageId, pageIndex)
                }
                if (refs.isNotEmpty()) undoRedoManager.push(UndoRedoAction.PagesPasted(refs))
            }

            // Push delete actions recorded during the index session onto the undo stack.
            val deletedIds        = data?.getStringExtra(PageIndexActivity.EXTRA_DELETED_PAGE_IDS)
            val deletedIndices    = data?.getStringExtra(PageIndexActivity.EXTRA_DELETED_PAGE_INDICES)
            val deletedTimestamps = data?.getStringExtra(PageIndexActivity.EXTRA_DELETED_PAGE_TIMESTAMPS)
            if (!deletedIds.isNullOrEmpty() && !deletedIndices.isNullOrEmpty() && !deletedTimestamps.isNullOrEmpty()) {
                val ids        = deletedIds.split(",")
                val indices    = deletedIndices.split(",").mapNotNull { it.toIntOrNull() }
                val timestamps = deletedTimestamps.split(",").mapNotNull { it.toLongOrNull() }
                // Group the whole index-session delete batch into one undo step so a single
                // undo restores every page deleted together (mirrors LassoDeleted).
                val refs = ids.zip(indices).zip(timestamps).map { (idIndex, ts) ->
                    UndoRedoAction.DeletedPageRef(idIndex.first, idIndex.second, ts)
                }
                if (refs.isNotEmpty()) undoRedoManager.push(UndoRedoAction.PagesDeleted(refs))
            }

            // Push move actions recorded during the index session onto the undo stack.
            // Each move OPERATION becomes one PagesMoved undo step (split by EXTRA_MOVED_OP_SIZES);
            // cross-operation ordering is handled naturally by the undo stack. Within an operation
            // both undo and redo process pages forward (see PagesMoved / movePagesRelativeRaw docs).
            val movedIds      = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_PAGE_IDS)
            val movedPrev     = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_PREV_AFTER_IDS)
            val movedNewAfter = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_NEW_AFTER_IDS)
            val movedOpSizes  = data?.getStringExtra(PageIndexActivity.EXTRA_MOVED_OP_SIZES)
            if (!movedIds.isNullOrEmpty() && !movedOpSizes.isNullOrEmpty()) {
                val ids       = movedIds.split(",")
                val prevs     = movedPrev?.split(",") ?: emptyList()
                val newAfters = movedNewAfter?.split(",") ?: emptyList()
                val allRefs = ids.indices.map { i ->
                    UndoRedoAction.MovedPageRef(
                        pageId = ids[i],
                        previousAfterPageId = prevs.getOrNull(i)?.takeIf { it.isNotEmpty() },
                        newAfterPageId = newAfters.getOrNull(i)?.takeIf { it.isNotEmpty() },
                    )
                }
                // Split the flattened refs back into per-operation batches and push one each.
                var offset = 0
                movedOpSizes.split(",").mapNotNull { it.toIntOrNull() }.forEach { size ->
                    val batch = allRefs.subList(offset, (offset + size).coerceAtMost(allRefs.size))
                    if (batch.isNotEmpty()) undoRedoManager.push(UndoRedoAction.PagesMoved(batch.toList()))
                    offset += size
                }
            }

            // Push template-change actions recorded during the index session onto the undo stack.
            // Group the whole batch into ONE TemplatesChanged step (mirrors PagesDeleted) so a single
            // undo reverts every page set together. Ids are .soil template-row ids ("" → null = blank).
            val templatePageIds = data?.getStringExtra(PageIndexActivity.EXTRA_TEMPLATE_PAGE_IDS)
            val templatePrevIds = data?.getStringExtra(PageIndexActivity.EXTRA_TEMPLATE_PREV_IDS)
            val templateNewIds  = data?.getStringExtra(PageIndexActivity.EXTRA_TEMPLATE_NEW_IDS)
            if (!templatePageIds.isNullOrEmpty()) {
                val ids   = templatePageIds.split(",")
                val prevs = templatePrevIds?.split(",") ?: emptyList()
                val news  = templateNewIds?.split(",") ?: emptyList()
                val refs = ids.indices.map { i ->
                    UndoRedoAction.TemplateChangeRef(
                        pageId             = ids[i],
                        previousTemplateId = prevs.getOrNull(i)?.takeIf { it.isNotEmpty() },
                        newTemplateId      = news.getOrNull(i)?.takeIf { it.isNotEmpty() },
                    )
                }
                if (refs.isNotEmpty()) undoRedoManager.push(UndoRedoAction.TemplatesChanged(refs))
            }

            val xnbRemovedPageIds   = data?.getStringExtra(PageIndexActivity.EXTRA_XNB_REMOVED_PAGE_IDS)
            val xnbRemovedDeletedAt = data?.getStringExtra(PageIndexActivity.EXTRA_XNB_REMOVED_DELETED_AT)
            if (!xnbRemovedPageIds.isNullOrEmpty() && !xnbRemovedDeletedAt.isNullOrEmpty()) {
                val ids = xnbRemovedPageIds.split(",")
                val ts  = xnbRemovedDeletedAt.toLongOrNull() ?: 0L
                if (ids.isNotEmpty() && ts > 0) {
                    undoRedoManager.push(UndoRedoAction.CrossNotebookPagesRemoved(ids, ts))
                }
            }

            val anySessionActions = !pastedIds.isNullOrEmpty() || !deletedIds.isNullOrEmpty() ||
                !movedIds.isNullOrEmpty() || !templatePageIds.isNullOrEmpty() ||
                !xnbRemovedPageIds.isNullOrEmpty()
            if (anySessionActions) updateUndoRedoButtons()

            // If the user chose "Open ‹DestName›" after a cross-notebook op, seal this notebook
            // and launch the destination (mirrors switchToRecentNotebook / openLinkedNotebook).
            val openNotebookId   = data?.getStringExtra(PageIndexActivity.EXTRA_OPEN_NOTEBOOK_ID)
            val openNotebookName = data?.getStringExtra(PageIndexActivity.EXTRA_OPEN_NOTEBOOK_NAME)
            if (!openNotebookId.isNullOrEmpty() && !openNotebookName.isNullOrEmpty()) {
                lifecycleScope.launch {
                    val parentId = withContext(Dispatchers.IO) {
                        indexRepo.getNotebook(openNotebookId)?.parentId
                    } ?: ""
                    AppStateManager.save(this@NotebookActivity, AppViewState(parentId, false))
                    closeNotebook()
                    startActivity(
                        Intent(this@NotebookActivity, NotebookActivity::class.java).apply {
                            putExtra(EXTRA_NOTEBOOK_ID,   openNotebookId)
                            putExtra(EXTRA_NOTEBOOK_NAME, openNotebookName)
                        }
                    )
                    finish()
                }
                return@registerForActivityResult
            }

            val selected = data?.getIntExtra(PageIndexActivity.EXTRA_SELECTED_PAGE_INDEX, -1) ?: -1
            when {
                // User tapped a card — navigate to that page (forces full reload).
                selected >= 0 -> navigateToPage(selected)
                // No card tapped but session actions occurred — reload in place so the
                // pages list, page count, and canvas reflect the paste/delete/move changes.
                anySessionActions -> navigateToPage(currentPageIndex)
            }
        }
    }

    // ── Link target picker launcher (Session 2) ───────────────────────────────
    // On OK, build the chosen chrome + target. If a link was being edited
    // ([pendingLinkEditId] set) update it in place; otherwise wrap the stashed selection into a
    // new link. Pending state is cleared either way (also on cancel — see the early returns).
    private val linkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Refresh the page list on every return — the picker may have inserted a page into
        // the current notebook's .soil even if the user then cancelled.
        reloadPagesPreservingCurrent()

        val editId      = pendingLinkEditId
        val strokes     = pendingLinkStrokes
        val headings    = pendingLinkHeadings
        val texts       = pendingLinkTexts
        val lines       = pendingLinkLines
        val shapes      = pendingLinkShapes
        val strokesOnly = pendingLinkStrokesOnly
        pendingLinkEditId      = null
        pendingLinkStrokesOnly = false
        pendingLinkStrokes = emptyList(); pendingLinkHeadings = emptyList()
        pendingLinkTexts = emptyList();   pendingLinkLines = emptyList()
        pendingLinkShapes = emptyList()

        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val chrome = data.getStringExtra(LinkTargetPickerActivity.EXTRA_RESULT_CHROME)
            ?.let { runCatching { LinkChrome.valueOf(it) }.getOrNull() } ?: return@registerForActivityResult
        val target: LinkTarget = run {
            val kind = data.getStringExtra(LinkTargetPickerActivity.EXTRA_RESULT_TARGET_KIND)
            val pageId     = data.getStringExtra(LinkTargetPickerActivity.EXTRA_RESULT_PAGE_ID)
            val otherNbId  = data.getStringExtra(LinkTargetPickerActivity.EXTRA_RESULT_NOTEBOOK_ID)
            when (kind) {
                LinkTargetPickerActivity.TARGET_OTHER_NOTEBOOK ->
                    LinkTarget.OtherNotebook(otherNbId ?: return@registerForActivityResult)
                LinkTargetPickerActivity.TARGET_OTHER_NOTEBOOK_PAGE ->
                    LinkTarget.OtherNotebookPage(
                        otherNbId ?: return@registerForActivityResult,
                        pageId ?: return@registerForActivityResult,
                    )
                else -> // TARGET_CURRENT_PAGE (and legacy results without a kind)
                    LinkTarget.CurrentNotebookPage(pageId ?: return@registerForActivityResult)
            }
        }

        val convertToText = result.data?.getBooleanExtra(LinkTargetPickerActivity.EXTRA_RESULT_CONVERT_TO_TEXT, false) ?: false
        val resultText = result.data?.getStringExtra(LinkTargetPickerActivity.EXTRA_RESULT_TEXT)

        lifecycleScope.launch(Dispatchers.IO) {
            if (editId != null) {
                updateLink(editId, chrome, target, newText = resultText)
            } else if (strokesOnly && convertToText && strokes.isNotEmpty()) {
                createLinkFromStrokesConvertingToText(strokes, chrome, target)
            } else if (strokes.isNotEmpty() || headings.isNotEmpty() || texts.isNotEmpty() || lines.isNotEmpty() || shapes.isNotEmpty()) {
                createLinkFromSelection(strokes, headings, texts, lines, shapes, chrome, target)
            }
        }
    }

    /** Launch the link target picker, pre-selecting [initialChrome]/[initialTarget] when editing. */
    private fun launchLinkPicker(
        initialChrome: LinkChrome?,
        initialTarget: LinkTarget?,
        strokesOnly: Boolean = false,
        initialText: String? = null,
    ) {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            val intent = Intent(this@NotebookActivity, LinkTargetPickerActivity::class.java).apply {
                putExtra(LinkTargetPickerActivity.EXTRA_NOTEBOOK_ID, notebookId)
                putExtra(LinkTargetPickerActivity.EXTRA_NOTEBOOK_NAME, notebookDisplayName)
                putExtra(LinkTargetPickerActivity.EXTRA_CURRENT_PAGE_INDEX, currentPageIndex)
                if (strokesOnly) putExtra(LinkTargetPickerActivity.EXTRA_STROKES_ONLY, true)
                if (initialText != null) putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_TEXT, initialText)
                if (initialChrome != null) putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_CHROME, initialChrome.name)
                when (initialTarget) {
                    is LinkTarget.CurrentNotebookPage -> {
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_TARGET_KIND, LinkTargetPickerActivity.TARGET_CURRENT_PAGE)
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_PAGE_ID, initialTarget.pageId)
                    }
                    is LinkTarget.OtherNotebook -> {
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_TARGET_KIND, LinkTargetPickerActivity.TARGET_OTHER_NOTEBOOK)
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_NOTEBOOK_ID, initialTarget.notebookId)
                    }
                    is LinkTarget.OtherNotebookPage -> {
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_TARGET_KIND, LinkTargetPickerActivity.TARGET_OTHER_NOTEBOOK_PAGE)
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_NOTEBOOK_ID, initialTarget.notebookId)
                        putExtra(LinkTargetPickerActivity.EXTRA_INITIAL_NOTEBOOK_PAGE_ID, initialTarget.pageId)
                    }
                    null -> { /* creating a new link — no pre-selection */ }
                }
            }
            linkPickerLauncher.launch(intent)
        }
    }

    /**
     * Re-read the page list after an external picker may have inserted current-notebook pages.
     * When [gotoPageId] resolves to a page, navigate to it (used by the calendar page export's
     * "Open" action); otherwise keep the currently-open page.
     */
    private fun reloadPagesPreservingCurrent(gotoPageId: String? = null) {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val refreshed = withContext(Dispatchers.IO) { db.notebookDao().getPagesSorted() }
            pages = refreshed.toMutableList()
            if (gotoPageId != null) {
                val gi = pages.indexOfFirst { it.id == gotoPageId }
                if (gi >= 0) { navigateToPage(gi); return@launch }
            }
            val idx = pages.indexOfFirst { it.id == currentPageId }
            if (idx >= 0) currentPageIndex = idx
            updatePageIndicator()
        }
    }

    /** The sticky note being edited in [StickyNoteEditorActivity] — stashed before launch,
     *  read in [editorLauncher] to know which note to persist content to. */
    private var pendingStickyNote: StickyNoteRender? = null
    private var pendingStickyInitialCreate = false

    /** Launched when the user taps the Scratch Pad button in the notebook toolbar. Session 7 will
     *  consume RESULT_OK to paste content returned from the scratch pad. */
    private val scratchpadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val content = ScratchpadTransfer.pending ?: return@registerForActivityResult
        ScratchpadTransfer.pending = null
        performScratchpadTransfer(content)
    }

    /**
     * Calendar hand-off (calendar opened from this notebook). Two paths can return here:
     * - "Send to Notebook → this notebook": paste [CalendarTransfer.pending] onto the current page.
     * - "Send page to notebook" export into this notebook: the calendar wrote new pages into this
     *   `.soil` directly, so reload the page list (navigating to the new page if "Open" was chosen).
     */
    private val calendarLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Always refresh — the calendar export may have inserted pages into this notebook's .soil
        // even when the user stayed in the calendar (RESULT_CANCELED) or chose "Open".
        val gotoPageId = result.data?.getStringExtra(CalendarActivity.EXTRA_RESULT_GOTO_PAGE_ID)
        reloadPagesPreservingCurrent(gotoPageId)

        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val content = CalendarTransfer.pending ?: return@registerForActivityResult
        CalendarTransfer.pending = null
        performScratchpadTransfer(content)
    }

    // ── Document editor (host side) ───────────────────────────────────────────
    // The editor never opens the .soil — this activity reads and writes on its behalf through
    // DocumentTransfer. See docs/documents.md.

    /** The page whose document is open in the editor, or "" when none is. */
    private var documentPageId: String = ""

    /** Index of that page. The editor can flip pages, so this drifts from [currentPageIndex]. */
    private var documentPageIndex: Int = -1

    /** That document's source watermark. Only a seed or a "bring in" moves it — never a keystroke. */
    private var documentSrcUpdatedAt: Long? = null

    /**
     * Flushes whatever the editor last published, then catches the notebook up to the page the editor
     * ended on. Fires even on a cancelled return, and after a low-memory kill of *this* activity behind
     * the editor: the recreated host restores [documentPageId] from instance state, and this is where
     * the text it kept alive lands.
     */
    private val documentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val text = DocumentTransfer.live
        val caret = DocumentTransfer.liveCaret
        val pageId = documentPageId
        val endedOn = documentPageIndex
        DocumentTransfer.clearSession()
        documentPageId = ""
        documentPageIndex = -1

        val db = soilDatabase
        if (text != null && pageId.isNotEmpty() && db != null) {
            val src = documentSrcUpdatedAt
            DocumentPreferences.saveCaret(this, pageId, caret)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { DocumentRepository.save(db.notebookDao(), pageId, text, src) }
                    .onFailure { Log.e(TAG, "Failed to save document for page $pageId", it) }
            }
        }

        // Follow the editor's page flips. Deferred to here on purpose: this navigates the drawing
        // surface, which must not be driven while the activity is stopped behind the editor.
        if (endedOn >= 0 && endedOn != currentPageIndex && endedOn < pages.size) {
            navigateToPage(endedOn)
        }
    }

    /** Launched when opening a sticky note's content editor. */
    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val out = StickyNoteEditorTransfer.output
        StickyNoteEditorTransfer.input  = null
        StickyNoteEditorTransfer.output = null
        // The editor is gone; stop holding a lambda that captures this Activity.
        StickyNoteEditorTransfer.persistToHost = null
        val pending          = pendingStickyNote ?: return@registerForActivityResult
        pendingStickyNote    = null
        val wasInitialCreate = pendingStickyInitialCreate
        pendingStickyInitialCreate = false

        val afterRender = if (out != null) {
            StickyNoteRender(
                id            = pending.id,
                boundingBox   = pending.boundingBox,
                strokes       = out.strokes,
                headings      = out.headings,
                textObjects   = out.textObjects,
                lines         = out.lines,
                shapes        = out.shapes,
                contentWidth  = out.contentWidth,
                contentHeight = out.contentHeight,
            )
        } else {
            pending
        }

        val db     = soilDatabase
        val pageId = currentPageId.takeIf { it.isNotEmpty() }
        val density = resources.displayMetrics.density

        if (out != null && db != null && pageId != null) {
            val afterObj  = afterRender.toStickyNoteObject(density)
            val beforeObj = pending.toStickyNoteObject(density)
            if (afterObj != beforeObj) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.withTransaction {
                            db.notebookDao().replaceStickyNoteSubtree(afterRender, System.currentTimeMillis(), density)
                        }
                        noteContentEdit(db, pageId)
                    }
                    val updatedNotes = drawingView.getStickyNotes().map {
                        if (it.id == afterRender.id) afterRender else it
                    }
                    drawingView.loadStickyNotes(updatedNotes)
                    val currentStrokes  = drawingView.getStrokes()
                    val currentHeadings = drawingView.getHeadings()
                    val templateBmp     = currentTemplateBitmap
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, stickyNotes = updatedNotes)
                    }
                    if (bitmap != null) drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
                    undoRedoManager.push(
                        UndoRedoAction.StickyNoteContentEdited(
                            noteId = pending.id,
                            pageId = pageId,
                            before = pending,
                            after  = afterRender,
                        )
                    )
                    updateUndoRedoButtons()
                    if (wasInitialCreate) selectStickyNoteIcon(afterRender)
                }
                return@registerForActivityResult
            }
        }
        if (wasInitialCreate) selectStickyNoteIcon(afterRender)
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — equivalent to Flutter's SystemUiMode.immersiveSticky.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Toolbar ───────────────────────────────────────────────────────────
        binding.btnClose.setOnClickListener {
            closeNotebook()
            finish()
        }

        // Page actions (insert before/after, delete, erase, template, page index, copy, paste)
        // now live in the canvas long-press "Page" menu — see showPageMenu(). No toolbar buttons.

        // Pen tool button — activates pen mode (default). Tapping it when the pen is *already*
        // active opens the colour panel instead; the same two-role pattern btnLasso uses for its
        // clipboard popup. btnPen.isSelected is false in every other tool mode, so it alone is a
        // sufficient test — but it must be read before the mode exits below flip it.
        binding.btnPen.setOnClickListener {
            val wasPenActive = binding.btnPen.isSelected
            hideLassoPopupToolbar()
            hideShapeInsertToolbar()
            if (isShapeTransformMode) exitShapeTransformMode()
            if (isLassoMode) exitLassoMode()
            if (isLassoEraserMode) exitLassoEraserMode()
            if (isEraserActive) {
                isEraserActive = false
                drawingView.setEraserMode(false)
            }
            binding.btnPen.isSelected = true
            binding.btnEraser.isSelected = false
            binding.btnLassoEraser.isSelected = false
            binding.btnLasso.isSelected = false
            ToolPreferencesManager.save(this, ActiveTool.PEN)
            if (wasPenActive) togglePenColorPanel() else hidePenColorPanel()
        }

        binding.btnEraser.setOnClickListener {
            hideLassoPopupToolbar()
            hideShapeInsertToolbar()
            hidePenColorPanel()
            if (isShapeTransformMode) exitShapeTransformMode()
            if (isLassoMode) exitLassoMode()
            if (isLassoEraserMode) exitLassoEraserMode()
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnEraser.isSelected = isEraserActive
            binding.btnPen.isSelected = !isEraserActive
            binding.btnLassoEraser.isSelected = false
            binding.btnLasso.isSelected = false
            ToolPreferencesManager.save(this, if (isEraserActive) ActiveTool.ERASER else ActiveTool.PEN)
        }

        binding.btnLassoEraser.setOnClickListener {
            hideLassoPopupToolbar()
            hideShapeInsertToolbar()
            hidePenColorPanel()
            if (!isLassoEraserMode) {
                enterLassoEraserMode()
                ToolPreferencesManager.save(this, ActiveTool.LASSO_ERASER)
            }
            // Tapping the active lasso eraser button is a no-op.
        }

        // Insert Text button retired — strokes are converted to text instead.

        binding.btnInsertLines.setOnClickListener {
            val db = soilDatabase ?: return@setOnClickListener
            val pw = drawingView.asView().width.takeIf { it > 0 } ?: return@setOnClickListener
            val ph = drawingView.asView().height.takeIf { it > 0 } ?: return@setOnClickListener
            LineObjectDialog(
                context  = this,
                pageWidthPx  = pw,
                pageHeightPx = ph,
                density  = resources.displayMetrics.density,
                onInsert = { lines -> insertLineObjects(db, lines) },
            ).show()
        }

        binding.btnInsertStickyNote.setOnClickListener {
            insertStickyNote()
        }

        binding.btnInsertShape.setOnClickListener {
            if (binding.shapeInsertToolbar.visibility == View.VISIBLE) {
                hideShapeInsertToolbar()
            } else {
                showShapeInsertToolbar()
            }
        }

        binding.btnInsertShapeRectangle.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.RECTANGLE)
        }
        binding.btnInsertShapeEllipse.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.ELLIPSE)
        }
        binding.btnInsertShapeTriangle.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.TRIANGLE)
        }
        binding.btnInsertShapeDiamond.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.DIAMOND)
        }
        binding.btnInsertShapeTrapezoid.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.TRAPEZOID)
        }
        binding.btnInsertShapePentagon.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.PENTAGON)
        }
        binding.btnInsertShapeHexagon.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.HEXAGON)
        }
        binding.btnInsertShapeStar.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.STAR)
        }
        binding.btnInsertShapeArch.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.ARCH)
        }
        binding.btnInsertShapeLine.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.LINE)
        }
        binding.btnInsertShapeArrow.setOnClickListener {
            insertShape(com.notesprout.android.data.ShapeType.ARROW)
        }

        binding.btnLasso.setOnClickListener {
            hidePenColorPanel()
            if (isLassoEraserMode) exitLassoEraserMode()
            if (!isLassoMode) {
                enterLassoMode()
                ToolPreferencesManager.save(this, ActiveTool.LASSO)
            } else {
                // Already in lasso mode — toggle popup if clipboard has content.
                if (NotesproutClipboard.hasContent()) {
                    if (binding.lassoPopupToolbar.visibility == View.VISIBLE) {
                        hideLassoPopupToolbar()
                    } else {
                        updateLassoPopupToolbar()
                    }
                }
                // Empty clipboard: silent no-op.
            }
        }

        // Table of Contents button retired — reachable via the canvas gesture only.

        binding.btnRecents.setOnClickListener {
            lifecycleScope.launch {
                // Resolve recents off-thread, then drop the currently-open notebook from the list.
                val resolved = RecentsManager.resolve(this@NotebookActivity)
                    .filter { it.notebookId != notebookId }
                RecentsDialog(
                    context = this@NotebookActivity,
                    entries = resolved,
                    onRecentSelected = { selectedId -> switchToRecentNotebook(selectedId) }
                ).show()
            }
        }

        // Export button moved to the canvas long-press "Page" menu — see showPageMenu().

        // Text-recognition button moved to the canvas long-press "Page" menu — see showPageMenu().

        // Pin button moved to the top of the canvas long-press "Page" menu — see showPageMenu().

        binding.btnLock.setOnClickListener {
            val nbId = notebookId.takeIf { it.isNotEmpty() } ?: return@setOnClickListener
            showEncryptFromToolbarDialog(nbId)
        }

        binding.btnCalendar.setOnClickListener {
            calendarLauncher.launch(
                CalendarActivity.intentFromNotebook(this, notebookId, notebookDisplayName)
            )
        }

        binding.btnScratchpad.setOnClickListener {
            val intent = Intent(this, ScratchpadActivity::class.java).apply {
                putExtra(ScratchpadActivity.EXTRA_FROM_NOTEBOOK_ID,        notebookId)
                putExtra(ScratchpadActivity.EXTRA_FROM_NOTEBOOK_NAME,      notebookDisplayName)
            }
            scratchpadLauncher.launch(intent)
        }

        // Tasks is a plain launch, not a for-result one: nothing comes back from it into the page
        // the way a scratch-pad or calendar selection does.
        binding.btnTasks.setOnClickListener { TasksActivity.launch(this) }
        binding.btnDocument.setOnClickListener {
            soilDatabase?.let { openDocumentEditor(it) }
        }

        binding.btnUndo.setOnClickListener { performUndo() }
        binding.btnRedo.setOnClickListener { performRedo() }
        updateUndoRedoButtons()  // both disabled initially (empty stacks)

        // Initial button states — tool is restored after drawingView is created below.
        binding.btnPen.isSelected = true
        binding.btnEraser.isSelected = false
        binding.btnLassoEraser.isSelected = false
        binding.btnLasso.isSelected = false

        // ── Back press ────────────────────────────────────────────────────────
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeNotebook()
                finish()
            }
        })

        // ── Drawing view ──────────────────────────────────────────────────────
        drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
        isSnapEnabled = SnapPreferences.load(this)
        drawingView.isSnapEnabled = isSnapEnabled
        // Restore the pen's ink colour — one global value, the same way the active tool below is.
        drawingView.setPenColor(PenColorPreferences.load(this))
        // Restore the last-used tool — survives notebook switches and app restarts.
        when (ToolPreferencesManager.load(this)) {
            ActiveTool.ERASER      -> {
                isEraserActive = true
                drawingView.setEraserMode(true)
                binding.btnPen.isSelected    = false
                binding.btnEraser.isSelected = true
            }
            ActiveTool.LASSO       -> enterLassoMode()
            ActiveTool.LASSO_ERASER -> enterLassoEraserMode()
            ActiveTool.PEN         -> { /* already default from button init above */ }
        }

        // Erase callback — soft-delete the stroke's DB row as soon as it leaves memory.
        drawingView.onStrokeErased = { strokeId ->
            // Remove from the persisted-ID registry so saveStrokes doesn't try to
            // re-insert a stroke that has already been soft-deleted from the DB.
            persistedStrokeIds.remove(strokeId)
            val db = soilDatabase
            val pageId  = currentPageId
            val layerId = currentLayerId
            if (db != null) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.notebookDao().softDeleteById(strokeId, System.currentTimeMillis())
                    }
                    // Record undo action after the DB write completes.
                    if (pageId.isNotEmpty() && layerId.isNotEmpty()) {
                        undoRedoManager.push(UndoRedoAction.StrokeErased(pageId, layerId, strokeId))
                        updateUndoRedoButtons()
                    }
                }
            }
        }

        // Heading erase callback — the view has already removed the heading from its in-memory
        // list before this fires. Persist the delete, push an undo action, and rebuild the
        // bitmap so the EPD panel reflects the erased heading.
        drawingView.onHeadingErased = { heading ->
            val deletedAt = System.currentTimeMillis()
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null) {
                // Deep-copy before any async work so the undo action holds stable data.
                val capturedHeading = HeadingStroke(
                    heading.id, RectF(heading.boundingBox),
                    heading.strokes.map { s -> s.deepCopy() },
                    recognizedText = heading.recognizedText,
                    level = heading.level,
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    db.notebookDao().softDeleteById(heading.id, deletedAt)
                    withContext(Dispatchers.Main) {
                        if (pageId.isNotEmpty()) {
                            // strokeIds is the union of ALL erased IDs (executeActionDb restores
                            // them from the DB via this field); headingIds is the typed subset.
                            undoRedoManager.push(UndoRedoAction.LassoErased(
                                strokeIds  = listOf(heading.id),
                                headingIds = listOf(heading.id),
                                pageId     = pageId,
                                headings   = listOf(capturedHeading),
                            ))
                            updateUndoRedoButtons()
                        }
                        rebuildBitmapAfterErase()
                    }
                }
            }
        }

        // Text-object erase callback — the view has already removed the text object from its
        // in-memory list. Persist the delete, push an undo action, and rebuild the bitmap.
        drawingView.onTextErased = { textObject ->
            val deletedAt = System.currentTimeMillis()
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null) {
                // Deep-copy before any async work so the undo action holds stable data.
                val captured = TextRender(
                    textObject.id, RectF(textObject.boundingBox), textObject.text, textObject.strokes,
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    db.notebookDao().softDeleteById(textObject.id, deletedAt)
                    withContext(Dispatchers.Main) {
                        if (pageId.isNotEmpty()) {
                            // strokeIds is the union of ALL erased IDs; textIds is the typed subset.
                            undoRedoManager.push(UndoRedoAction.LassoErased(
                                strokeIds   = listOf(textObject.id),
                                pageId      = pageId,
                                textIds     = listOf(textObject.id),
                                textObjects = listOf(captured),
                            ))
                            updateUndoRedoButtons()
                        }
                        rebuildBitmapAfterErase()
                    }
                }
            }
        }

        // Link-object erase callback — the hardware eraser deletes the whole link row (its embedded
        // content lives inside the row, so there are no child rows to touch). Undo restores it.
        drawingView.onLinkErased = { linkObject ->
            val deletedAt = System.currentTimeMillis()
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null) {
                val captured = linkObject.translate(0f, 0f)
                lifecycleScope.launch(Dispatchers.IO) {
                    db.notebookDao().softDeleteById(linkObject.id, deletedAt)
                    noteContentEdit(db, pageId)
                    withContext(Dispatchers.Main) {
                        if (pageId.isNotEmpty()) {
                            // strokeIds is the union of ALL erased IDs; linkIds is the typed subset.
                            undoRedoManager.push(UndoRedoAction.LassoErased(
                                strokeIds = listOf(linkObject.id),
                                pageId    = pageId,
                                linkIds   = listOf(linkObject.id),
                                links     = listOf(captured),
                            ))
                            updateUndoRedoButtons()
                        }
                        rebuildBitmapAfterErase()
                    }
                }
            }
        }

        // Sticky-note erase callback — hardware eraser deletes the row; undo restores it.
        drawingView.onStickyNoteErased = { noteObject ->
            val deletedAt = System.currentTimeMillis()
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null) {
                val captured = noteObject.translate(0f, 0f)
                lifecycleScope.launch(Dispatchers.IO) {
                    db.notebookDao().softDeleteById(noteObject.id, deletedAt)
                    noteContentEdit(db, pageId)
                    withContext(Dispatchers.Main) {
                        if (pageId.isNotEmpty()) {
                            undoRedoManager.push(UndoRedoAction.LassoErased(
                                strokeIds    = listOf(noteObject.id),
                                pageId       = pageId,
                                stickyNoteIds = listOf(noteObject.id),
                                stickyNotes   = listOf(captured),
                            ))
                            updateUndoRedoButtons()
                        }
                        rebuildBitmapAfterErase()
                    }
                }
            }
        }

        // Line-object erase callback — same pattern as text objects.
        drawingView.onLineErased = { lineObject ->
            val deletedAt = System.currentTimeMillis()
            val db = soilDatabase
            val pageId = currentPageId
            if (db != null) {
                val captured = lineObject.copy(boundingBox = RectF(lineObject.boundingBox))
                lifecycleScope.launch(Dispatchers.IO) {
                    db.notebookDao().softDeleteById(lineObject.id, deletedAt)
                    withContext(Dispatchers.Main) {
                        if (pageId.isNotEmpty()) {
                            // strokeIds is the union of ALL erased IDs; lineIds is the typed subset.
                            undoRedoManager.push(UndoRedoAction.LassoErased(
                                strokeIds = listOf(lineObject.id),
                                pageId    = pageId,
                                lineIds   = listOf(lineObject.id),
                                lines     = listOf(captured),
                            ))
                            updateUndoRedoButtons()
                        }
                        rebuildBitmapAfterErase()
                    }
                }
            }
        }

        // Persist new strokes immediately after each pen lift.
        // The EPD overlay stays active during writing; this is purely a DB save trigger.
        drawingView.onPenLifted = {
            val db = soilDatabase
            if (db != null) {
                lifecycleScope.launch {
                    // The stroke-save path must never kill the process. The contention that used to
                    // land here is gone (the sticky editor no longer opens its own connection), so
                    // this is now a general safety net rather than a fix for anything specific.
                    // On failure nothing is marked persisted, so the same strokes are retried on the
                    // next pen lift; the ink stays in memory either way. An empty list here just
                    // means no undo entries for a save that did not happen.
                    val newIds = runCatching { withContext(Dispatchers.IO) { saveStrokes(db) } }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e(TAG, "Stroke save failed — will retry on the next pen lift", e)
                        }
                        .getOrDefault(emptyList())
                    // Push one StrokeAdded per newly persisted stroke.
                    val pageId  = currentPageId
                    val layerId = currentLayerId
                    if (pageId.isNotEmpty() && layerId.isNotEmpty()) {
                        for (id in newIds) {
                            undoRedoManager.push(UndoRedoAction.StrokeAdded(pageId, layerId, id))
                        }
                        if (newIds.isNotEmpty()) updateUndoRedoButtons()
                    }
                }
            }
        }

        // Lasso gesture complete — auto-close the path and run hit test off main thread.
        drawingView.onLassoComplete = { drawnPath, startPoint ->
            // Dismiss popups whenever a lasso gesture completes.
            hideLassoPopupToolbar()
            hideShapeInsertToolbar()

            // Auto-close: straight line from end back to start, then close polygon.
            drawnPath.lineTo(startPoint.x, startPoint.y)
            drawnPath.close()

            selectedObjectIds.clear()
            val strokeSnapshot      = drawingView.getStrokes()
            val headingSnapshot     = drawingView.getHeadings()
            val textSnapshot        = drawingView.getTextObjects()
            val lineSnapshot        = drawingView.getLineObjects()
            val linkSnapshot        = drawingView.getLinks()
            val stickyNoteSnapshot  = drawingView.getStickyNotes()
            val shapeSnapshot       = drawingView.getShapeObjects()
            lifecycleScope.launch(Dispatchers.Default) {
                val lassoBounds = RectF()
                drawnPath.computeBounds(lassoBounds, true)

                // Minimum size guard: ignore accidental taps / trivially small paths.
                val density = resources.displayMetrics.density
                val minPx = 10f * density
                if (lassoBounds.width() < minPx && lassoBounds.height() < minPx) return@launch

                // Build a Region from the closed lasso polygon for point-in-polygon tests.
                val clipRect = Rect(
                    (lassoBounds.left   - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.top    - 1f).toInt().coerceAtLeast(0),
                    (lassoBounds.right  + 1f).toInt(),
                    (lassoBounds.bottom + 1f).toInt(),
                )
                val lassoRegion = Region()
                lassoRegion.setPath(drawnPath, Region(clipRect))

                val hitIds      = mutableSetOf<String>()
                val unionBounds = RectF()

                for (stroke in strokeSnapshot) {
                    // Phase 1: AABB pre-filter — O(1) per stroke.
                    if (!RectF.intersects(lassoBounds, stroke.boundingBox)) continue
                    // Phase 2: any stroke point inside the lasso polygon?
                    for (pt in stroke.points) {
                        if (lassoRegion.contains(pt.x.toInt(), pt.y.toInt())) {
                            hitIds.add(stroke.id)
                            unionBounds.union(stroke.boundingBox)
                            break
                        }
                    }
                }

                // Heading / text / line hit-test: select if the lasso overlaps any part of the
                // object's bounding box (touch semantics, matching strokes) — not just its center.
                for (heading in headingSnapshot) {
                    if (!RectF.intersects(lassoBounds, heading.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, heading.boundingBox)) {
                        hitIds.add(heading.id)
                        unionBounds.union(heading.boundingBox)
                    }
                }

                for (textObj in textSnapshot) {
                    if (!RectF.intersects(lassoBounds, textObj.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, textObj.boundingBox)) {
                        hitIds.add(textObj.id)
                        unionBounds.union(textObj.boundingBox)
                    }
                }

                for (lineObj in lineSnapshot) {
                    if (!RectF.intersects(lassoBounds, lineObj.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, lineObj.boundingBox)) {
                        hitIds.add(lineObj.id)
                        unionBounds.union(lineObj.boundingBox)
                    }
                }

                // Links participate in lasso selection (box-overlap, same semantics as headings).
                for (link in linkSnapshot) {
                    if (!RectF.intersects(lassoBounds, link.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, link.boundingBox)) {
                        hitIds.add(link.id)
                        unionBounds.union(link.boundingBox)
                    }
                }

                // Sticky notes participate in lasso selection (box-overlap semantics).
                for (note in stickyNoteSnapshot) {
                    if (!RectF.intersects(lassoBounds, note.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, note.boundingBox)) {
                        hitIds.add(note.id)
                        unionBounds.union(note.boundingBox)
                    }
                }

                for (shape in shapeSnapshot) {
                    if (!RectF.intersects(lassoBounds, shape.boundingBox)) continue
                    if (LassoGeometry.regionIntersectsBox(lassoRegion, shape.boundingBox)) {
                        hitIds.add(shape.id)
                        unionBounds.union(shape.boundingBox)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (hitIds.isEmpty()) return@withContext  // nothing caught — stay in lasso mode idle
                    selectedObjectIds.clear()
                    selectedObjectIds.addAll(hitIds)
                    drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                    // Pad the bounding box slightly so the dashed rect doesn't sit on the ink.
                    val pad = 8f * resources.displayMetrics.density
                    unionBounds.inset(-pad, -pad)
                    drawingView.setLassoOverlay(null, unionBounds)
                    updateFloatingSelectionToolbar(unionBounds)
                }
            }
        }

        // Tap to dismiss: clear the selection visual but stay in lasso mode.
        drawingView.onLassoTapToDismiss = {
            // Capture before clearing — a "no selection" tap (e.g. tap-to-paste after cut)
            // must NOT exit smart-lasso; that would tear down lasso mode before paste runs.
            val hadActiveSelection = selectedObjectIds.isNotEmpty()
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            if (isSmartLassoSession && hadActiveSelection) {
                // Smart-lasso sessions return to pen mode when a real selection is dismissed.
                exitLassoMode()
                drawingView.enableDrawing()
                binding.btnPen.isSelected = true
            }
        }

        // Stylus tap in lasso mode — trigger paste if clipboard has content and no selection.
        drawingView.onLassoTap = tap@{ tapX, tapY ->
            // Shape tap-to-transform: single shape selected, tap inside its bbox.
            if (selectedObjectIds.size == 1) {
                val selId = selectedObjectIds.first()
                val selShape = drawingView.getShapeObjects().find { it.id == selId }
                if (selShape != null && selShape.boundingBox.contains(tapX, tapY)) {
                    enterShapeTransformMode(selShape)
                    return@tap
                }
            }
            // Heading text edit tap — checked after shape, before paste or dismiss logic.
            if (selectedObjectIds.size == 1) {
                val selectedId = selectedObjectIds.first()
                val selectedHeading = drawingView.getHeadings().find {
                    it.id == selectedId && it.recognizedText != null
                }
                if (selectedHeading != null && selectedHeading.boundingBox.contains(tapX, tapY)) {
                    showHeadingTextEditDialog(selectedHeading)
                    return@tap
                }
                // Text object edit tap — only for non-blank text (recognized state).
                // Unrecognized objects (blank text, strokes rendered) are not editable via dialog.
                val selectedText = drawingView.getTextObjects().find { it.id == selectedId }
                if (selectedText != null && selectedText.text.isNotBlank() && selectedText.boundingBox.contains(tapX, tapY)) {
                    showTextEditDialogForTextObject(selectedText)
                    return@tap
                }
            }
            if (selectedObjectIds.isEmpty() && NotesproutClipboard.hasContent()) {
                hideLassoPopupToolbar()
                performLassoPaste(tapX, tapY)
            }
            // NOTE: link tap-to-follow in lasso mode is handled by the finger-only
            // [handleLinkFollowGesture] path (a stylus tap here keeps select/paste semantics).
        }

        // Drag threshold crossed — hide the floating toolbar during the drag move.
        drawingView.onDragStarted = {
            hideFloatingSelectionToolbar()
        }

        // Fresh lasso gesture started (cleared old selection) — hide the floating toolbar.
        drawingView.onLassoSelectionCleared = {
            hideFloatingSelectionToolbar()
        }

        // Lasso eraser gesture complete — soft-delete hit strokes/headings/text objects and push undo.
        drawingView.onLassoEraseComplete = { erasedIds ->
            val pageId = currentPageId.takeIf { it.isNotEmpty() }
            if (erasedIds.isNotEmpty() && pageId != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    erasedIds.forEach { id ->
                        soilDatabase?.notebookDao()?.softDeleteById(id, now)
                    }
                    withContext(Dispatchers.Main) {
                        val erasedSet = erasedIds.toSet()
                        val erasedHeadings    = drawingView.getHeadings().filter { it.id in erasedSet }
                        val erasedHeadingIds  = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                        val capturedHeadings  = erasedHeadings.map { h ->
                            HeadingStroke(h.id, RectF(h.boundingBox),
                                h.strokes.map { s -> s.deepCopy() },
                                recognizedText = h.recognizedText,
                                level = h.level)
                        }
                        val erasedTexts        = drawingView.getTextObjects().filter { it.id in erasedSet }
                        val erasedTextIds      = erasedTexts.mapTo(mutableSetOf()) { it.id }
                        val erasedLines        = drawingView.getLineObjects().filter { it.id in erasedSet }
                        val erasedLineIds      = erasedLines.mapTo(mutableSetOf()) { it.id }
                        val erasedLinks        = drawingView.getLinks().filter { it.id in erasedSet }
                        val erasedLinkIds      = erasedLinks.mapTo(mutableSetOf()) { it.id }
                        val erasedStickyNotes  = drawingView.getStickyNotes().filter { it.id in erasedSet }
                        val erasedStickyIds    = erasedStickyNotes.mapTo(mutableSetOf()) { it.id }
                        val erasedShapes       = drawingView.getShapeObjects().filter { it.id in erasedSet }
                        val erasedShapeIds     = erasedShapes.mapTo(mutableSetOf()) { it.id }
                        val updatedStrokes      = drawingView.getStrokes().filter { it.id !in erasedSet }
                        val updatedHeadings     = drawingView.getHeadings().filter { it.id !in erasedHeadingIds }
                        val updatedTexts        = drawingView.getTextObjects().filter { it.id !in erasedTextIds }
                        val updatedLines        = drawingView.getLineObjects().filter { it.id !in erasedLineIds }
                        val updatedLinks        = drawingView.getLinks().filter { it.id !in erasedLinkIds }
                        val updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in erasedStickyIds }
                        val updatedShapes       = drawingView.getShapeObjects().filter { it.id !in erasedShapeIds }
                        persistedStrokeIds.removeAll(erasedSet - erasedHeadingIds - erasedTextIds - erasedLineIds - erasedLinkIds - erasedStickyIds - erasedShapeIds)
                        drawingView.setStrokeListSilently(updatedStrokes)
                        drawingView.loadHeadings(updatedHeadings)
                        drawingView.loadTextObjects(updatedTexts)
                        drawingView.loadLineObjects(updatedLines)
                        drawingView.loadLinks(updatedLinks)
                        drawingView.loadStickyNotes(updatedStickyNotes)
                        drawingView.loadShapeObjects(updatedShapes)
                        undoRedoManager.push(UndoRedoAction.LassoErased(
                            strokeIds       = erasedIds.toList(),
                            pageId          = pageId,
                            headingIds      = erasedHeadingIds.toList(),
                            headings        = capturedHeadings,
                            textIds         = erasedTextIds.toList(),
                            textObjects     = erasedTexts,
                            lineIds         = erasedLineIds.toList(),
                            lines           = erasedLines,
                            linkIds         = erasedLinkIds.toList(),
                            links           = erasedLinks,
                            stickyNoteIds   = erasedStickyIds.toList(),
                            stickyNotes     = erasedStickyNotes,
                            shapeIds        = erasedShapeIds.toList(),
                            shapes          = erasedShapes,
                        ))
                        updateUndoRedoButtons()
                        val templateBmp = currentTemplateBitmap
                        val bitmap = withContext(Dispatchers.IO) {
                            drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
                        }
                        if (bitmap != null) {
                            drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                        } else {
                            drawingView.loadStrokes(updatedStrokes)
                        }
                    }
                }
            }
        }

        // Scribble-to-Erase: soft-delete all touched objects. The scribble stroke itself
        // is a gesture — the view already removed it from in-memory; it is never saved to DB.
        drawingView.onScribbleEraseComplete = { erasedObjectIds, erasedHeadings, erasedTextObjects, erasedLineObjects, erasedLinks, erasedStickyNotes, erasedShapes ->
            val db     = soilDatabase
            val pageId = currentPageId.takeIf { it.isNotEmpty() }
            val layerId = currentLayerId
            if (db != null && pageId != null && layerId.isNotEmpty() && erasedObjectIds.isNotEmpty()) {
                lifecycleScope.launch {
                    val deletedAt = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        db.withTransaction {
                            erasedObjectIds.forEach { db.notebookDao().softDeleteById(it, deletedAt) }
                        }
                        noteContentEdit(db, pageId)
                    }
                    val erasedHeadingIds = erasedHeadings.mapTo(mutableSetOf()) { it.id }
                    val capturedHeadings = erasedHeadings.map { h ->
                        HeadingStroke(
                            id             = h.id,
                            boundingBox    = RectF(h.boundingBox),
                            strokes        = h.strokes.map { s -> s.deepCopy() },
                            recognizedText = h.recognizedText,
                            level          = h.level,
                        )
                    }
                    val erasedTextIds     = erasedTextObjects.mapTo(mutableSetOf()) { it.id }
                    val erasedLineIds     = erasedLineObjects.mapTo(mutableSetOf()) { it.id }
                    val erasedLinkIds     = erasedLinks.mapTo(mutableSetOf()) { it.id }
                    val erasedStickyIds   = erasedStickyNotes.mapTo(mutableSetOf()) { it.id }
                    val erasedShapeIds    = erasedShapes.mapTo(mutableSetOf()) { it.id }
                    val erasedSet         = erasedObjectIds.toSet()

                    // View already removed the scribble stroke; only remove erased content here.
                    val updatedStrokes      = drawingView.getStrokes().filter { it.id !in erasedSet }
                    val updatedHeadings     = drawingView.getHeadings().filter { it.id !in erasedHeadingIds }
                    val updatedTexts        = drawingView.getTextObjects().filter { it.id !in erasedTextIds }
                    val updatedLines        = drawingView.getLineObjects().filter { it.id !in erasedLineIds }
                    val updatedLinks        = drawingView.getLinks().filter { it.id !in erasedLinkIds }
                    val updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in erasedStickyIds }
                    val updatedShapes       = drawingView.getShapeObjects().filter { it.id !in erasedShapeIds }
                    persistedStrokeIds.removeAll(erasedSet - erasedHeadingIds - erasedTextIds - erasedLineIds - erasedLinkIds - erasedStickyIds - erasedShapeIds)
                    drawingView.setStrokeListSilently(updatedStrokes)
                    drawingView.loadHeadings(updatedHeadings)
                    drawingView.loadTextObjects(updatedTexts)
                    drawingView.loadLineObjects(updatedLines)
                    drawingView.loadLinks(updatedLinks)
                    drawingView.loadStickyNotes(updatedStickyNotes)
                    drawingView.loadShapeObjects(updatedShapes)

                    undoRedoManager.push(UndoRedoAction.ScribbleErased(
                        erasedObjectIds = erasedObjectIds,
                        pageId          = pageId,
                        layerId         = layerId,
                        deletedAt       = deletedAt,
                        headingIds      = erasedHeadingIds.toList(),
                        headings        = capturedHeadings,
                        textIds         = erasedTextIds.toList(),
                        textObjects     = erasedTextObjects,
                        lineIds         = erasedLineIds.toList(),
                        lines           = erasedLineObjects,
                        linkIds         = erasedLinkIds.toList(),
                        links           = erasedLinks,
                        stickyNoteIds   = erasedStickyIds.toList(),
                        stickyNotes     = erasedStickyNotes,
                        shapeIds        = erasedShapeIds.toList(),
                        shapes          = erasedShapes,
                    ))
                    updateUndoRedoButtons()

                    val templateBmp = currentTemplateBitmap
                    val bitmap = withContext(Dispatchers.IO) {
                        drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
                    }
                    if (bitmap != null) {
                        drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                    } else {
                        drawingView.loadStrokes(updatedStrokes)
                    }
                }
            }
        }

        // Smart-lasso gesture complete — the view already discarded the gesture stroke.
        // Enter lasso mode, show the selection, rebuild the bitmap to remove the gesture circle.
        drawingView.onSmartLassoComplete = { hitIds, unionBounds ->
            if (!isLassoMode) {
                enterLassoMode()
                isSmartLassoSession = true
            }
            selectedObjectIds.clear()
            selectedObjectIds.addAll(hitIds)
            drawingView.lassoSelectedIds = selectedObjectIds.toSet()
            val pad          = 8f * resources.displayMetrics.density
            val paddedBounds = RectF(unionBounds).also { it.inset(-pad, -pad) }
            // Rebuild bitmap off-thread to drop the gesture circle (already removed from strokes).
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            val templateBmp     = currentTemplateBitmap
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, currentLines)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
                }
                drawingView.setLassoOverlay(null, paddedBounds)
                updateFloatingSelectionToolbar(paddedBounds)
            }
        }

        // Smart-shape dwell recognized — convert the stroke to a shape object.
        // The view already removed the gesture stroke from in-memory; convertStrokeToShape
        // handles the DB insert, undo action, and bitmap rebuild.
        drawingView.onShapeRecognized = { stroke, result ->
            lifecycleScope.launch(Dispatchers.IO) { convertStrokeToShape(stroke, result) }
        }

        // Shape transform committed — persist geometry change and push undo action.
        drawingView.onShapeTransformed = { before, after ->
            lifecycleScope.launch(Dispatchers.IO) { persistShapeTransform(before, after) }
        }

        // Tap outside the shape transform overlay — commit and clear selection.
        drawingView.onShapeTransformTapOutside = {
            exitShapeTransformMode(clearSelection = true)
        }

        // Shape drag/resize started — hide the transform toolbar (mirrors regular lasso drag).
        drawingView.onShapeTransformDragStarted = {
            binding.shapeTransformToolbar.visibility = View.GONE
        }

        // Shape moved/resized — update the lasso overlay to the new position and restore toolbar.
        drawingView.onShapeTransformMoved = { newBbox ->
            val pad = 8f * resources.displayMetrics.density
            val selBox = RectF(newBbox).apply { inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            binding.shapeTransformToolbar.visibility = View.VISIBLE
            binding.shapeTransformToolbar.post {
                positionPopover(binding.shapeTransformToolbar, newBbox)
            }
        }

        // Lasso move gesture complete — persist translated coordinates and push undo action.
        drawingView.onStrokesMoved = { originalStrokes, movedStrokes, originalHeadings, movedHeadings, originalTextObjects, movedTextObjects, originalLineObjects, movedLineObjects, originalLinks, movedLinks, originalStickyNotes, movedStickyNotes, originalShapes, movedShapes ->
            val pageId = currentPageId.takeIf { it.isNotEmpty() }
            val db = soilDatabase
            if (pageId != null && db != null) {
                lifecycleScope.launch {
                    val now = System.currentTimeMillis()
                    val density = resources.displayMetrics.density
                    // A failed write must not take the process with it. This coroutine had no catch
                    // at all, which is why a lock collision here killed the app rather than being
                    // logged. That collision no longer happens, but an unguarded DB write on a
                    // lifecycleScope coroutine is a process-killer regardless of the cause.
                    runCatching {
                    withContext(Dispatchers.IO) {
                        db.withTransaction {
                            for (moved in movedStrokes) {
                                db.notebookDao().updateStrokeBlob(moved.id, moved.strokeBlob(), moved.color, moved.strokeWidth, now)
                            }
                            for (heading in movedHeadings) {
                                db.notebookDao().replaceHeadingSubtree(heading, now)
                            }
                            for (textObj in movedTextObjects) {
                                db.notebookDao().replaceTextSubtree(textObj, now)
                            }
                            for (lineObj in movedLineObjects) {
                                db.notebookDao().updateColumns(lineObj.toRow("", 0, now, now, density))
                            }
                            for (link in movedLinks) {
                                db.notebookDao().replaceLinkSubtree(link, now, density)
                            }
                            for (note in movedStickyNotes) {
                                db.notebookDao().replaceStickyNoteSubtree(note, now, density)
                            }
                            for (shape in movedShapes) {
                                db.notebookDao().updateColumns(shape.toRow("", 0, now, now, density))
                            }
                        }
                        if (movedLinks.isNotEmpty() || movedStickyNotes.isNotEmpty() || movedShapes.isNotEmpty()) noteContentEdit(db, pageId)
                    }
                    }.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Move persist failed — the move is not saved", e)
                    }
                    undoRedoManager.push(
                        UndoRedoAction.StrokesMoved(
                            pageId,
                            originalStrokes.toList(),
                            movedStrokes.toList(),
                            originalHeadings = originalHeadings.toList(),
                            movedHeadings = movedHeadings.toList(),
                            originalTextObjects = originalTextObjects.toList(),
                            movedTextObjects = movedTextObjects.toList(),
                            originalLineObjects = originalLineObjects.toList(),
                            movedLineObjects = movedLineObjects.toList(),
                            originalLinks = originalLinks.toList(),
                            movedLinks = movedLinks.toList(),
                            originalStickyNotes = originalStickyNotes.toList(),
                            movedStickyNotes = movedStickyNotes.toList(),
                            originalShapes = originalShapes.toList(),
                            movedShapes = movedShapes.toList(),
                        )
                    )
                    updateUndoRedoButtons()
                    // Reshow floating toolbar at new selection box position after the move.
                    val newBox = computeUnionBoundingBox(movedStrokes, movedHeadings)
                    movedTextObjects.forEach { newBox.union(it.boundingBox) }
                    movedLineObjects.forEach { newBox.union(it.boundingBox) }
                    movedLinks.forEach { newBox.union(it.boundingBox) }
                    movedStickyNotes.forEach { newBox.union(it.boundingBox) }
                    movedShapes.forEach { newBox.union(it.boundingBox) }
                    val pad = 8f * resources.displayMetrics.density
                    newBox.inset(-pad, -pad)
                    updateFloatingSelectionToolbar(newBox)
                }
            }
        }

        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Wire the floating selection toolbar Copy and Cut buttons.
        binding.btnLassoCopy.setOnClickListener {
            performLassoCopy()
        }

        binding.btnLassoCut.setOnClickListener {
            performLassoCut()
        }

        binding.btnLassoDelete.setOnClickListener {
            performLassoDelete()
        }

        binding.btnMakeHeading.setOnClickListener {
            // Toggle the H1/H2/H3 submenu rather than converting directly. The actual conversion
            // happens when the user picks a level (btnHeadingH1/H2/H3 below).
            if (binding.headingTypeSubmenu.visibility == View.VISIBLE) {
                hideHeadingTypeSubmenu()
                return@setOnClickListener
            }
            val ids = drawingView.lassoSelectedIds
            val strokes = drawingView.getStrokes().filter { it.id in ids }
            if (strokes.isEmpty()) return@setOnClickListener
            val box = RectF()
            strokes.forEach { box.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            box.inset(-pad, -pad)
            // Capture the selection for the deferred convert; the level is chosen in the submenu.
            pendingHeadingStrokes = strokes
            pendingHeadingBox = box
            headingSubmenuMode = HeadingSubmenuMode.CONVERT
            applyHeadingLevelHighlight(0)                        // no level pre-selected in convert mode
            binding.headingTypeSubmenu.visibility = View.VISIBLE
            binding.headingTypeSubmenu.post {
                // Anchor to the floating toolbar (which hosts the heading button), not the selection
                // box — otherwise both popovers land in the same place and overlap.
                val tb = binding.floatingSelectionToolbar
                val anchor = RectF(tb.x, tb.y, tb.x + tb.width, tb.y + tb.height)
                positionPopover(binding.headingTypeSubmenu, anchor)
            }
        }

        // S4: btnHeadingMenu — opens the submenu in CHANGE mode for a single-heading selection.
        binding.btnHeadingMenu.setOnClickListener {
            // Toggle-close if submenu is already showing.
            if (binding.headingTypeSubmenu.visibility == View.VISIBLE) {
                hideHeadingTypeSubmenu()
                return@setOnClickListener
            }
            val heading = selectedHeadings.firstOrNull() ?: return@setOnClickListener
            pendingChangeHeading = heading
            headingSubmenuMode = HeadingSubmenuMode.CHANGE
            applyHeadingLevelHighlight(heading.level)               // highlight current level
            binding.headingTypeSubmenu.visibility = View.VISIBLE
            binding.headingTypeSubmenu.post {
                val tb = binding.floatingSelectionToolbar
                val anchor = RectF(tb.x, tb.y, tb.x + tb.width, tb.y + tb.height)
                positionPopover(binding.headingTypeSubmenu, anchor)
            }
        }

        // Shared H1/H2/H3 submenu buttons. Branch on headingSubmenuMode:
        //   CONVERT (S3) → convert selected strokes into a heading of the chosen level.
        //   CHANGE  (S4) → change an existing heading's level.
        val onHeadingLevelPicked: (Int) -> Unit = { level ->
            when (headingSubmenuMode) {
                HeadingSubmenuMode.CONVERT -> {
                    val strokes = pendingHeadingStrokes
                    val box = pendingHeadingBox
                    hideHeadingTypeSubmenu()
                    if (strokes.isNotEmpty() && box != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            createHeadingFromStrokes(strokes, box, level = level)
                        }
                    }
                }
                HeadingSubmenuMode.CHANGE -> {
                    val h = pendingChangeHeading
                    hideHeadingTypeSubmenu()
                    if (h != null && level != h.level) {
                        changeHeadingLevel(h, level)
                    }
                }
            }
        }
        binding.btnHeadingH1.setOnClickListener { onHeadingLevelPicked(1) }
        binding.btnHeadingH2.setOnClickListener { onHeadingLevelPicked(2) }
        binding.btnHeadingH3.setOnClickListener { onHeadingLevelPicked(3) }

        binding.btnLink.setOnClickListener {
            val ids = drawingView.lassoSelectedIds
            val strokes  = drawingView.getStrokes().filter { it.id in ids }
            val headings = drawingView.getHeadings().filter { it.id in ids }
            val texts    = drawingView.getTextObjects().filter { it.id in ids }
            val lines    = drawingView.getLineObjects().filter { it.id in ids }
            val shapes   = drawingView.getShapeObjects().filter { it.id in ids }
            if (strokes.isEmpty() && headings.isEmpty() && texts.isEmpty() && lines.isEmpty() && shapes.isEmpty()) return@setOnClickListener
            // Stash the selection and open the target picker; the result handler creates the link.
            pendingLinkEditId = null
            pendingLinkStrokes = strokes; pendingLinkHeadings = headings
            pendingLinkTexts = texts;     pendingLinkLines = lines
            pendingLinkShapes = shapes
            pendingLinkStrokesOnly = strokes.isNotEmpty() && headings.isEmpty() && texts.isEmpty() && lines.isEmpty() && shapes.isEmpty()
            launchLinkPicker(initialChrome = null, initialTarget = null, strokesOnly = pendingLinkStrokesOnly)
        }

        binding.btnLinkEdit.setOnClickListener {
            val link = drawingView.getLinks().firstOrNull { it.id in drawingView.lassoSelectedIds } ?: return@setOnClickListener
            pendingLinkEditId = link.id
            pendingLinkStrokes = emptyList(); pendingLinkHeadings = emptyList()
            pendingLinkTexts = emptyList();   pendingLinkLines = emptyList()
            pendingLinkShapes = emptyList()
            val singleText = link.textObjects.singleOrNull()?.text?.takeIf {
                link.strokes.isEmpty() && link.headings.isEmpty() && link.lines.isEmpty()
            }
            launchLinkPicker(initialChrome = link.chrome, initialTarget = link.target, initialText = singleText)
        }

        binding.btnUnlink.setOnClickListener {
            val link = drawingView.getLinks().firstOrNull { it.id in drawingView.lassoSelectedIds } ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                removeLink(link)
            }
        }

        binding.btnConvertText.setOnClickListener {
            val ids = drawingView.lassoSelectedIds
            val selectedStrokes = drawingView.getStrokes().filter { it.id in ids }
            if (selectedStrokes.isEmpty()) return@setOnClickListener
            val box = RectF()
            selectedStrokes.forEach { box.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            box.inset(-pad, -pad)
            lifecycleScope.launch(Dispatchers.IO) {
                convertLassoToText(selectedStrokes, box)
            }
        }

        binding.btnConvertShape.setOnClickListener {
            val stroke = shapeConvertStroke ?: return@setOnClickListener
            val result = shapeConvertResult ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) { convertStrokeToShape(stroke, result) }
        }

        binding.btnAlignLeft.setOnClickListener {
            lifecycleScope.launch { performAlign(alignVertical = true) }
        }

        binding.btnAlignTop.setOnClickListener {
            lifecycleScope.launch { performAlign(alignVertical = false) }
        }

        binding.btnSnapToggle.setOnClickListener {
            isSnapEnabled = !isSnapEnabled
            drawingView.isSnapEnabled = isSnapEnabled
            SnapPreferences.save(this, isSnapEnabled)
            updateSnapToggleIcon()
        }
        updateSnapToggleIcon()

        binding.btnLassoSendToScratchpad.setOnClickListener {
            lifecycleScope.launch { performSendToScratchpad() }
        }

        // Wire the lasso popup Clear Clipboard button.
        binding.btnLassoClearClipboard.setOnClickListener {
            clearClipboard()
            updateLassoButtonIcon()
            hideLassoPopupToolbar()
        }

        // Shape transform toolbar buttons.
        binding.btnShapeAspectLock.setOnClickListener {
            val updated = drawingView.toggleShapeAspectLock() ?: return@setOnClickListener
            updateShapeTransformToggle(updated)
        }
        binding.btnShapeTransformDone.setOnClickListener {
            exitShapeTransformMode(clearSelection = false)
        }

        // Compute the lasso popup anchor point once the toolbar has been laid out.
        binding.drawingToolbar.doOnLayout {
            computeLassoToolbarAnchor()
        }

        // Push the toolbar's bounds to the drawing view after layout so BOOX excludes the bar
        // region from pen input. Placement-aware (top/bottom) via computeToolbarExclusionRect().
        binding.drawingToolbar.doOnLayout { pushToolbarExclusion() }

        // ── Toolbar layout (order / visibility / dividers per ToolbarConfig) ───
        // Arrange the existing button views per the persisted global config before overflow
        // runs. Default config reproduces today's bar (full set, top) except the page controls
        // now pack left with the rest; the overflow button stays pinned right via an internal
        // weighted spacer. Runs synchronously so the first layout already reflects it.
        toolbarLayoutManager = ToolbarLayoutManager(
            toolbar         = binding.drawingToolbar,
            dividerOverflow = binding.dividerOverflow,
            btnOverflow     = binding.btnOverflow,
        )
        toolbarConfig = ToolbarPreferencesManager.load(this)
        toolbarLayoutManager.apply(toolbarConfig)

        // ── Pen colour panel ──────────────────────────────────────────────────
        // Built after the toolbar manager because it asks it for the placement-derived side and the
        // top guard on every open, so the bar can move (or float) and the panel follows.
        penColorPanel = PenColorPanelController(
            root          = binding.root,
            panel         = binding.penColorPanel.root,
            anchor        = binding.btnPen,
            paletteGreyButton  = binding.penColorPanel.btnPaletteGrey,
            paletteColorButton = binding.penColorPanel.btnPaletteColor,
            swatchRow1    = binding.penColorPanel.penSwatchRow1,
            swatchRow2    = binding.penColorPanel.penSwatchRow2,
            customDivider = binding.penColorPanel.penCustomDivider,
            customRow     = binding.penColorPanel.penCustomRow,
            sideProvider  = {
                when (toolbarConfig.placement) {
                    ToolbarPlacement.LEFT   -> PenColorPanelController.Side.RIGHT_OF
                    ToolbarPlacement.RIGHT  -> PenColorPanelController.Side.LEFT_OF
                    ToolbarPlacement.BOTTOM -> PenColorPanelController.Side.ABOVE
                    else                    -> PenColorPanelController.Side.BELOW
                }
            },
            boundsProvider   = { Rect(0, 0, binding.root.width, binding.root.height) },
            topGuardProvider = { toolbarLayoutManager.topGuard() },
            onColorChosen    = { hex -> applyPenColor(hex) },
            onSlotEditRequested = { index, initial -> showCustomColorDialog(index, initial) },
            onPaletteChanged  = { PenColorPreferences.savePalette(this, it) },
            onVisibilityChanged = { pushToolbarExclusion() },
        )
        applyPenTintToButton()
        PenColorPreferences.addListener(penColorListener)

        // ── Toolbar overflow ──────────────────────────────────────────────────
        // Constructed before positionOverflowMenu()/positionPageIndicator(): those read the overflow
        // manager (FLOAT's menu anchoring asks it for the menu extent), so it must exist first.
        overflowManager = ToolbarOverflowManager(
            toolbar        = binding.drawingToolbar,
            overflowMenu   = binding.overflowMenu,
            dividerOverflow = binding.dividerOverflow,
            btnOverflow    = binding.btnOverflow,
        )
        // FLOAT mode: pin the drag handle in the overflow manager + wire its long-drag behaviour.
        overflowManager.setLeadingPinned(toolbarLayoutManager.dragHandle)
        wireFloatDragHandle()

        positionOverflowMenu()
        positionPageIndicator()

        binding.drawingToolbar.doOnLayout {
            overflowManager.initialize()
            overflowManager.recalc()
        }
        binding.drawingToolbar.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Recalc on either dimension change: a horizontal bar overflows by width, a vertical bar
            // by height (e.g. rotation). Either edge moving can change the available main-axis extent.
            val sizeChanged = (right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged) {
                closeOverflowMenu()     // restore exclusion zone if menu was open
                hideShapeInsertToolbar() // position would be stale after layout change
                // Defer the reparenting recalc out of this layout traversal — see recalcOverflowAfterLayout.
                recalcOverflowAfterLayout()
            }
        }
        binding.btnOverflow.setOnClickListener {
            drawingView.releaseRender()
            if (overflowManager.isOverflowMenuOpen()) closeOverflowMenu()
            else openOverflowMenu()
        }

        // ── Customize toolbar (gear) ──────────────────────────────────────────
        binding.btnToolbarSettings.setOnClickListener {
            drawingView.releaseRender()
            CustomizeToolbarDialog(
                context = this,
                current = toolbarConfig,
                onApply = { updated -> applyToolbarConfig(updated) },
            ).show()
        }
        // Long-press the gear: a fast Full↔Mini switch without opening the dialog. Mini is float-only,
        // so the shortcut is a no-op for edge-anchored placements (let the touch fall through).
        binding.btnToolbarSettings.setOnLongClickListener {
            if (toolbarConfig.placement != ToolbarPlacement.FLOAT) return@setOnLongClickListener false
            drawingView.releaseRender()
            applyToolbarConfig(toolbarConfig.copy(miniEnabled = !toolbarConfig.miniEnabled))
            true
        }

        // ── Toolbar hide state (Session 7) ────────────────────────────────────
        // Restore a persisted collapsed state. Registered after the overflow init doOnLayout so that
        // pass runs with the bar still visible (correct fit) before we hide it here. A one-finger
        // double-tap on the canvas (handleToolbarToggleGesture) brings it back.
        if (toolbarConfig.collapsed) {
            binding.root.doOnLayout { applyCollapsedState() }
        }

        // ── Page swipe gesture (one-finger, deliberate) ──────────────────────
        // Page navigation requires a deliberate full-width horizontal finger swipe.
        // Three guards gate the gesture: minimum distance (screen-width relative),
        // minimum velocity (1.5× system fling threshold), and horizontal dominance.
        // A second finger cancels the gesture so palm+finger combos never fire.
        //
        // State machine:
        //   ACTION_DOWN          → arm tracker; record start position
        //   ACTION_POINTER_DOWN  → second finger → cancel gesture
        //   ACTION_MOVE          → feed tracker while active
        //   ACTION_UP            → compute velocity + displacement; evaluate
        //   ACTION_CANCEL        → reset
        // No-op on init — handled entirely in dispatchTouchEvent / helpers below.

        // ── Resolve notebook identity ─────────────────────────────────────────
        notebookId          = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: ""
        notebookDisplayName = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: ""
        com.notesprout.android.core.OpenNotebooks.opened(notebookId)

        // Record this notebook on the surface stack, so a cold launch reopens it (see SurfaceStack).
        surfaceToken = savedInstanceState?.getString(SurfaceStack.KEY_TOKEN) ?: UUID.randomUUID().toString()
        // Restore an in-flight sticky-editor session (see onSaveInstanceState).
        savedInstanceState?.getString(StickyNoteEditorTransfer.STATE_PENDING_NOTE)?.let {
            pendingStickyNote = StickyNoteEditorTransfer.decodeNote(it)
            pendingStickyInitialCreate = savedInstanceState.getBoolean(StickyNoteEditorTransfer.STATE_PENDING_CREATE, false)
        }
        // Restore an in-flight document-editor session, and offer the editor a host again: it may
        // still be in front of us, holding text that only this activity can store.
        savedInstanceState?.getString(STATE_DOCUMENT_PAGE_ID)?.takeIf { it.isNotEmpty() }?.let {
            documentPageId = it
            documentPageIndex = savedInstanceState.getInt(STATE_DOCUMENT_PAGE_INDEX, -1)
            documentSrcUpdatedAt =
                if (savedInstanceState.containsKey(STATE_DOCUMENT_SRC)) savedInstanceState.getLong(STATE_DOCUMENT_SRC) else null
            DocumentTransfer.host = documentHost
        }
        SurfaceStack.attach(this, surfaceEntry())
        // A "fresh" open (from MainActivity/Recents) resets the link back-stack; a via-link open
        // (following a link or a back-swipe) preserves the trail. See [LinkBackStack].
        if (!intent.getBooleanExtra(EXTRA_VIA_LINK, false)) LinkBackStack.clear(this)
        if (notebookId.isNotEmpty()) {
            notebookSoilPath = soilFile(this, notebookId).absolutePath
            title = notebookDisplayName
            // Record this open in the device-local recents store (see RecentsManager).
            RecentsManager.recordOpen(this, notebookId)
            // Log an OPENED activity event for the Day-Detail history views (fire-and-forget).
            NotesproutApplication.appScope.launch { dayHistoryRepo.logOpened(notebookId) }
            val nbId = notebookId
            lifecycleScope.launch {
                notebookPinned = withContext(Dispatchers.IO) { indexRepo.isNotebookPinned(nbId) }
            }
        }

        // ── Open the Room DB ──────────────────────────────────────────────────
        // Key resolution may show a passphrase dialog, so the open is async.
        val notebookPath = notebookSoilPath
        if (notebookPath != null) {
            lifecycleScope.launch {
                val nbId = notebookId
                // Existence gate: a stale recents/history tap or replayed intent can carry an id
                // whose index row (or .soil) is gone. Without this, getEncryptionInfo defaults to
                // NONE and Room CREATES a ghost empty .soil at the path — an orphan file shown to
                // the user as their notebook, mysteriously blank.
                val exists = withContext(Dispatchers.IO) {
                    indexRepo.getNotebook(nbId)?.deletedAt == null && soilFile(this@NotebookActivity, nbId).exists()
                }
                if (!exists) {
                    android.widget.Toast.makeText(
                        this@NotebookActivity, "This notebook no longer exists.", android.widget.Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(nbId) }
                encryptionInfo = info
                updateLockButtonVisibility(info)
                val key = KeyResolver.resolveForOpen(this@NotebookActivity, nbId, info)
                if (info.encrypted && key == null) {
                    android.widget.Toast.makeText(
                        this@NotebookActivity, "Notebook locked", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    finish()
                    return@launch
                }
                soilKey = key
                if (key != null) KeySession.set(nbId, key) else KeySession.clear()
                // Encrypted notebooks never write a plaintext sidecar; clear any stale one.
                if (info.encrypted) {
                    undoRedoPersistenceFile(notebookPath).takeIf { it.exists() }?.delete()
                    undoRedoManager = UndoRedoManager()
                    updateUndoRedoButtons()
                    // Only NOTEBOOK-scope runs the passphrase KDF on open (a real wait worth an
                    // overlay). GLOBAL-scope opens from a cached raw key almost instantly, so the
                    // "Opening…" overlay would just flash — skip it (matches plaintext behaviour).
                    if (info.keyScope == KeyScope.NOTEBOOK) {
                        binding.openingOverlay.visibility = View.VISIBLE
                    }
                }
                sessionStartTime = System.currentTimeMillis()
                // Broad on purpose. A .soil that this build cannot open (schema drift from before the
                // columnar migration, an unreadable/mis-keyed file) used to throw straight out of this
                // coroutine and kill the process — and since cold launch rebuilds the surface stack,
                // the next launch reopened the same notebook and died again. Fail back to the library
                // with an explanation instead; the file itself is never touched by this path.
                try {
                    val builder = SoilDatabase.builder(this@NotebookActivity, notebookPath)
                    if (key != null) builder.openHelperFactory(
                        com.notesprout.android.crypto.KeyOpener.roomFactoryFor(
                            this@NotebookActivity, nbId, java.io.File(notebookPath), info.keyScope, key
                        )
                    )
                    soilDatabase = builder.build()
                    val db = soilDatabase!!
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            NotebookMetaStore.refresh(db, indexRepo, notebookId)
                        }.onFailure { Slog.d(TAG) { "meta refresh on open failed: ${it.message}" } }
                    }
                    loadStrokes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failOpen(e)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Restore undo/redo state if the app was backgrounded with a non-empty history.
        val path = notebookSoilPath ?: return
        val file = undoRedoPersistenceFile(path)
        if (file.exists()) {
            try {
                undoRedoManager = UndoRedoManager.fromJson(file.readText())
                updateUndoRedoButtons()
            } catch (e: Exception) {
                Log.e(TAG, "onStart: failed to restore undo/redo state", e)
            } finally {
                file.delete()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop pending RTR jobs from firing against a DB that may be torn down while backgrounded.
        rtrScheduler?.cancelAll()
        if (encryptionInfo.encrypted) {
            // Persist undo/redo state inside the encrypted .soil so process death while
            // backgrounded doesn't lose history. Write even when empty to clear stale state.
            val db = soilDatabase ?: return
            try {
                db.openHelper.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO undo_redo_state (id, json) VALUES (0, ?)",
                    arrayOf(undoRedoManager.toJson())
                )
            } catch (e: Exception) {
                Log.e(TAG, "onStop: failed to persist undo/redo state", e)
            }
            return
        }
        // Plaintext: persist sidecar so history survives process death while backgrounded.
        if (!undoRedoManager.isEmpty()) {
            val path = notebookSoilPath ?: return
            try {
                undoRedoPersistenceFile(path).writeText(undoRedoManager.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "onStop: failed to persist undo/redo state", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SurfaceStack.markTop(this, surfaceEntry())
        drawingView.resumeDrawing()
        updateLassoButtonIcon()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SurfaceStack.KEY_TOKEN, surfaceToken)
        // Sticky-editor session marker: the transfer singleton dies with the process, so the
        // pending note must survive here or a low-memory kill behind the open editor makes the
        // launcher callback drop the whole editing session on return.
        pendingStickyNote?.let {
            outState.putString(StickyNoteEditorTransfer.STATE_PENDING_NOTE, StickyNoteEditorTransfer.encodeNote(it))
            outState.putBoolean(StickyNoteEditorTransfer.STATE_PENDING_CREATE, pendingStickyInitialCreate)
        }
        // Same reasoning for the open document: DocumentTransfer dies with the process, so which page
        // the editor is writing to has to survive here or the text it kept alive lands nowhere.
        if (documentPageId.isNotEmpty()) {
            outState.putString(STATE_DOCUMENT_PAGE_ID, documentPageId)
            outState.putInt(STATE_DOCUMENT_PAGE_INDEX, documentPageIndex)
            documentSrcUpdatedAt?.let { outState.putLong(STATE_DOCUMENT_SRC, it) }
        }
    }

    private fun surfaceEntry() = SurfaceEntry(surfaceToken, AppSurface.NOTEBOOK, notebookId = notebookId)

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        PenColorPreferences.removeListener(penColorListener)
        // Belt-and-braces against leaking this Activity through the process-global transfer: the
        // launcher callback clears it on the normal path, but that never runs if we are destroyed
        // while the editor is still up.
        StickyNoteEditorTransfer.persistToHost = null
        super.onDestroy()
        // Stop offering ourselves to the document editor — our connection is about to close. The
        // editor keeps its text in DocumentTransfer.live, which the seal below (and, if this activity
        // is recreated behind the editor, documentLauncher) flushes.
        if (DocumentTransfer.host === documentHost) DocumentTransfer.host = null
        com.notesprout.android.core.OpenNotebooks.closed(notebookId)
        drawingView.releaseResources()
        // Safety net: if the activity is destroyed without the user tapping Close
        // (e.g. system kill), ensure the DB is still closed cleanly. Seal synchronously
        // (blocking) here — there's no surviving UI to defer to and the process may be
        // dying. In the normal close path soilDatabase is already null, so this no-ops.
        closeNotebook(blocking = true)
    }

    /**
     * Feed every touch event to the page swipe detector before normal dispatch.
     *
     * This is required because on BOOX the Onyx TouchHelper consumes stylus events
     * at the view level and never bubbles them back to a parent touch listener.
     * Running the detector here means we always see DOWN / POINTER_DOWN events
     * regardless of which child consumed intermediate move events.  We do not
     * consume events ourselves (return super), so the drawing view receives everything
     * it expects.
     *
     * Only finger events are forwarded — stylus and eraser events belong to the
     * drawing engine exclusively.  A pen swipe must never turn the page.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Pen-activity gate: while the stylus owns the surface (and briefly after it lifts), a
        // finger contact is a palm, not a gesture. Feeding it to the detectors produces false
        // double-taps/swipes whose handlers reconfigure the live pen session (releaseRender,
        // setLimitRect) and drop the stroke being written. Cancel any in-flight gesture state
        // once, then stay out of the way until the pen is gone.
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            if (drawingView.isPenActive) {
                if (!fingerGesturesSuppressed) {
                    fingerGesturesSuppressed = true
                    cancelFingerGestures()
                }
            } else {
                fingerGesturesSuppressed = false
                handlePageSwipe(event)
                handleLinkFollowGesture(event)
                handleToolbarToggleGesture(event)
                handleMultiFingerDoubleTap(event)
                handlePageMenuLongPress(event)
            }
        }

        // ── Overflow: deferred close after button UP (fixes finger click + stylus click) ──
        // overflowCloseOnUp is set when a DOWN lands inside an open overflow menu.
        // We defer the close until ACTION_UP so the button receives the UP and fires its
        // click listener before the menu is hidden.
        if (overflowCloseOnUp
            && (event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            overflowCloseOnUp = false
            // Capture the flag before dispatching — the click is posted asynchronously
            // (View.post(mPerformClick)), so checking visibility *after* super returns would
            // always see the old state and incorrectly close the overflow.
            val keepOpen = overflowKeepOpenForShapePicker
            overflowKeepOpenForShapePicker = false
            val result = super.dispatchTouchEvent(event) // button gets UP → click fires (async)
            if (!keepOpen) {
                closeOverflowMenu()                      // hide the menu + restore EPD zone
            }
            return result
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val isFinger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            val inToolbar = isTouchInToolbar(event)
            val inOverflowMenu = overflowManager.isOverflowMenuOpen() && run {
                val m = binding.overflowMenu
                event.x >= m.left && event.x < m.right && event.y >= m.top && event.y < m.bottom
            }
            val inShapeInsertToolbar = binding.shapeInsertToolbar.visibility == View.VISIBLE
                && isTouchInView(event, binding.shapeInsertToolbar)

            // Release the EPD writing overlay on any touch within the toolbar, overflow menu,
            // or shape insert toolbar so button state changes are visible immediately on e-ink.
            // Shape insert toolbar releases for both finger and stylus — it's a UI surface, not
            // a drawing surface, so we always want to flush the current EPD frame on entry.
            // Pen-active guard on the finger path: a palm landing over the bar mid-word is not a
            // button press, and releasing the render here would kill the stroke in flight. The
            // stylus path is unguarded — a pen touch on chrome is always deliberate.
            if ((isFinger && (inToolbar || inOverflowMenu) && !drawingView.isPenActive)
                || inShapeInsertToolbar) {
                drawingView.releaseRender()
            }

            // Overflow menu dismiss logic (handled for both finger and stylus).
            if (overflowManager.isOverflowMenuOpen()) {
                when {
                    isTouchInView(event, binding.btnOverflow) -> {
                        // btnOverflow's own click listener handles the toggle; don't intervene.
                    }
                    inOverflowMenu -> {
                        // Tapped an overflow button — defer close until ACTION_UP so the
                        // click listener fires before the menu is hidden.
                        overflowCloseOnUp = true
                        // If the tapped button is the shape picker, flag that the overflow
                        // must stay open so all three toolbars remain on screen together.
                        if (isTouchInView(event, binding.btnInsertShape)) {
                            overflowKeepOpenForShapePicker = true
                        }
                    }
                    inToolbar -> {
                        // Tapped a regular toolbar button — close immediately (no click timing issue).
                        closeOverflowMenu()
                    }
                    inShapeInsertToolbar -> {
                        // Tapped inside the shape insert toolbar while overflow is open — keep
                        // the overflow visible so all three toolbars stay on screen together.
                        // The shape insert toolbar's own dismiss logic handles its lifecycle.
                    }
                    else -> {
                        // Outside toolbar and overflow — dismiss-only.
                        closeOverflowMenu()
                        // Shape insert toolbar closes too when the overflow is dismissed from outside.
                        hideShapeInsertToolbar()
                        // Consume finger taps to avoid accidental canvas interaction; let
                        // stylus events through so a drawing stroke can start immediately.
                        if (isFinger) return true
                    }
                }
            }
        }

        // Dismiss the lasso popup toolbar on any touch outside its bounds.
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && binding.lassoPopupToolbar.visibility == View.VISIBLE) {
            val loc = IntArray(2)
            binding.lassoPopupToolbar.getLocationOnScreen(loc)
            val popupRect = Rect(
                loc[0], loc[1],
                loc[0] + binding.lassoPopupToolbar.width,
                loc[1] + binding.lassoPopupToolbar.height,
            )
            if (!popupRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                hideLassoPopupToolbar()
            }
        }

        // Dismiss the shape insert toolbar when touching outside it, unless the touch is on
        // btnInsertShape itself (its click listener handles the toggle).  Any other toolbar
        // button, overflow button, or canvas tap should close the picker.
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && binding.shapeInsertToolbar.visibility == View.VISIBLE) {
            val loc = IntArray(2)
            binding.shapeInsertToolbar.getLocationOnScreen(loc)
            val shapeToolbarRect = Rect(
                loc[0], loc[1],
                loc[0] + binding.shapeInsertToolbar.width,
                loc[1] + binding.shapeInsertToolbar.height,
            )
            val inShapeToolbar = shapeToolbarRect.contains(event.rawX.toInt(), event.rawY.toInt())
            val inBtnInsertShape = isTouchInView(event, binding.btnInsertShape)
            if (!inShapeToolbar && !inBtnInsertShape) {
                hideShapeInsertToolbar()
            }
        }

        // Dismiss the pen colour panel on any touch outside it — except on btnPen itself, whose own
        // click listener owns the toggle (handling it here too would close then immediately reopen).
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && ::penColorPanel.isInitialized && penColorPanel.isVisible) {
            val inPanel = penColorPanel.containsScreenPoint(event.rawX.toInt(), event.rawY.toInt())
            if (!inPanel && !isTouchInView(event, binding.btnPen)) {
                hidePenColorPanel()
            }
        }

        return super.dispatchTouchEvent(event)
    }

    /**
     * Returns true if the touch lands within the toolbar's bounds. Placement-aware: a rect contains
     * check (not `event.y < bottom`) so it works whether the bar is anchored at the top or bottom.
     * `event.x`/`event.y` are in the root layout's coordinate space, matching the toolbar's bounds.
     */
    private fun isTouchInToolbar(event: MotionEvent): Boolean {
        val tb = binding.drawingToolbar
        return event.x >= tb.left && event.x < tb.right && event.y >= tb.top && event.y < tb.bottom
    }

    /** Returns true if the touch event's screen-absolute coordinates land within [view]'s bounds. */
    private fun isTouchInView(event: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return event.rawX.toInt() in loc[0] until loc[0] + view.width
            && event.rawY.toInt() in loc[1] until loc[1] + view.height
    }

    /**
     * Opens the overflow menu and extends the BOOX stylus exclusion zone to cover it,
     * preventing the drawing engine from capturing stylus events in the menu area.
     */
    private fun openOverflowMenu() {
        overflowManager.openOverflowMenu()
        // computeToolbarExclusionRect() reads the open-menu state and extends the rect away from the
        // anchored edge synchronously (row count × button height) — no layout pass needed. Using a
        // measured height would be 0 until wrap_content runs, too late for an early stylus tap.
        pushToolbarExclusion()
    }

    /**
     * Closes the overflow menu and restores the BOOX stylus exclusion zone to the toolbar only.
     */
    private fun closeOverflowMenu() {
        overflowManager.closeOverflowMenu()
        pushToolbarExclusion()
    }

    /**
     * Compute the toolbar's exclusion rect in drawing-view coordinates for the current placement and
     * overflow state. The toolbar and drawing view share the root FrameLayout's origin, so the
     * toolbar's bounds *are* the rect. An open overflow menu extends the rect away from the anchored
     * edge (down for a top bar, up for a bottom bar, right for a left bar, left for a right bar).
     * When the shape insert toolbar is visible its bounds are also unioned in so the BOOX pen driver
     * does not capture stylus taps on its buttons as drawing strokes.
     */
    private fun computeToolbarExclusionRect(): Rect {
        // Collapsed: the bar is hidden, so nothing is excluded — the whole canvas is writable.
        if (toolbarConfig.collapsed) return Rect()
        val tb = binding.drawingToolbar
        val ext =
            if (overflowManager.isOverflowMenuOpen()) overflowManager.expectedOverflowMenuExtent() else 0
        val base = when (toolbarConfig.placement) {
            ToolbarPlacement.BOTTOM -> Rect(tb.left, tb.top - ext, tb.right, tb.bottom)
            ToolbarPlacement.LEFT   -> Rect(tb.left, tb.top, tb.right + ext, tb.bottom)
            ToolbarPlacement.RIGHT  -> Rect(tb.left - ext, tb.top, tb.right, tb.bottom)
            ToolbarPlacement.FLOAT  -> {
                // The floating bar sits at its margins inside the root; its laid-out bounds ARE the
                // rect. Overflow grows below a horizontal bar / right of a vertical bar — or flips to
                // the leading side near the far screen edge (matching positionOverflowMenu).
                val before = floatOverflowOpensBefore()
                if (toolbarConfig.floatAxis == ToolbarAxis.HORIZONTAL)
                    if (before) Rect(tb.left, tb.top - ext, tb.right, tb.bottom)
                    else Rect(tb.left, tb.top, tb.right, tb.bottom + ext)
                else
                    if (before) Rect(tb.left - ext, tb.top, tb.right, tb.bottom)
                    else Rect(tb.left, tb.top, tb.right + ext, tb.bottom)
            }
            else                    -> Rect(tb.left, tb.top, tb.right, tb.bottom + ext) // TOP
        }
        // Extend to cover the shape insert toolbar when it is visible and fully laid out.
        val sit = binding.shapeInsertToolbar
        if (sit.visibility == View.VISIBLE && sit.width > 0 && sit.height > 0) {
            base.union(
                sit.x.toInt(),
                sit.y.toInt(),
                (sit.x + sit.width).toInt(),
                (sit.y + sit.height).toInt(),
            )
        }
        // Same for the pen colour panel — it overhangs the bar, and the pen must not write under it.
        // Asked for in the drawing view's own coordinates, which is what the exclusion rect is in.
        if (::penColorPanel.isInitialized) {
            penColorPanel.panelRectIn(drawingView.asView())?.let(base::union)
        }
        return base
    }

    /** Push the current toolbar exclusion rect to the drawing view (BOOX pen layer). */
    private fun pushToolbarExclusion() {
        drawingView.setToolbarExclusion(computeToolbarExclusionRect())
    }

    /**
     * For FLOAT only: should the overflow menu open on the bar's *leading* side — above a horizontal
     * bar / left of a vertical bar — instead of the default trailing side? True only when the trailing
     * side can't fit the menu AND the leading side has more room, so a bar dragged near the far screen
     * edge keeps its overflow on-screen. Both [positionOverflowMenu] and [computeToolbarExclusionRect]
     * consult this so the menu and the pen-exclusion rect always agree.
     */
    private fun floatOverflowOpensBefore(): Boolean {
        if (toolbarConfig.placement != ToolbarPlacement.FLOAT) return false
        // positionOverflowMenu() runs once in onCreate before overflowManager is constructed; there's
        // no overflow content yet, so the menu can't open on the leading side.
        if (!::overflowManager.isInitialized) return false
        val tbLp = binding.drawingToolbar.layoutParams as FrameLayout.LayoutParams
        val ext = overflowManager.expectedOverflowMenuExtent()
        if (ext <= 0) return false
        return if (toolbarConfig.floatAxis == ToolbarAxis.HORIZONTAL) {
            val roomAfter = binding.root.height - (tbLp.topMargin + tbLp.height)
            roomAfter < ext && tbLp.topMargin > roomAfter
        } else {
            val roomAfter = binding.root.width - (tbLp.leftMargin + tbLp.width)
            roomAfter < ext && tbLp.leftMargin > roomAfter
        }
    }

    /**
     * Anchor + orient the overflow menu so it opens away from the bar and mirrors its axis:
     * below a top bar / above a bottom bar (vertical stack of rows), or beside a left/right bar
     * (horizontal run of columns). Margins match the bar thickness so it sits flush against the bar.
     */
    private fun positionOverflowMenu() {
        val menu = binding.overflowMenu
        val lp = menu.layoutParams as FrameLayout.LayoutParams
        val thick = toolbarLayoutManager.barThickness()
        // The bar itself starts below the guard on every top-touching placement, so the menu that
        // hangs off it has to clear the guard as well.
        val guard = toolbarLayoutManager.topGuard()
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        lp.topMargin = 0; lp.bottomMargin = 0; lp.leftMargin = 0; lp.rightMargin = 0
        when (toolbarConfig.placement) {
            ToolbarPlacement.LEFT -> {
                menu.orientation = LinearLayout.HORIZONTAL
                lp.gravity = Gravity.START
                lp.width = wrap; lp.height = match
                lp.leftMargin = thick
                lp.topMargin = guard
            }
            ToolbarPlacement.RIGHT -> {
                menu.orientation = LinearLayout.HORIZONTAL
                lp.gravity = Gravity.END
                lp.width = wrap; lp.height = match
                lp.rightMargin = thick
                lp.topMargin = guard
            }
            ToolbarPlacement.BOTTOM -> {
                menu.orientation = LinearLayout.VERTICAL
                lp.gravity = Gravity.BOTTOM
                lp.width = match; lp.height = wrap
                lp.bottomMargin = thick
            }
            ToolbarPlacement.FLOAT -> {
                // The menu tracks the floating bar's position (top-left margins), not a screen edge,
                // and matches the bar's main-axis extent so its packing lines line up with the bar.
                // Near the far screen edge it flips to the leading side (above / left of the bar).
                val tbLp = binding.drawingToolbar.layoutParams as FrameLayout.LayoutParams
                val before = floatOverflowOpensBefore()
                val ext = overflowManager.expectedOverflowMenuExtent()
                lp.gravity = Gravity.TOP or Gravity.START
                if (toolbarConfig.floatAxis == ToolbarAxis.HORIZONTAL) {
                    menu.orientation = LinearLayout.VERTICAL
                    lp.width = tbLp.width; lp.height = wrap
                    lp.leftMargin = tbLp.leftMargin
                    lp.topMargin = if (before) tbLp.topMargin - ext else tbLp.topMargin + thick
                } else {
                    menu.orientation = LinearLayout.HORIZONTAL
                    lp.width = wrap; lp.height = tbLp.height
                    lp.leftMargin = if (before) tbLp.leftMargin - ext else tbLp.leftMargin + thick
                    lp.topMargin = tbLp.topMargin
                }
            }
            else -> {
                menu.orientation = LinearLayout.VERTICAL
                lp.gravity = Gravity.TOP
                lp.width = match; lp.height = wrap
                lp.topMargin = guard + thick
            }
        }
        menu.layoutParams = lp
    }

    /** Keep the page indicator clear of the toolbar so they never collide: opposite a top/bottom bar,
     *  and on the leading side away from a right bar. When the toolbar is hidden, it always returns to
     *  the lower-right corner. */
    private fun positionPageIndicator() {
        val lp = binding.tvPageIndicator.layoutParams as FrameLayout.LayoutParams
        val gap = (8f * resources.displayMetrics.density).toInt()
        lp.topMargin = 0; lp.bottomMargin = 0; lp.marginStart = 0; lp.marginEnd = 0
        // Toolbar hidden → the indicator always returns to the lower-right corner, regardless of where
        // the bar would otherwise anchor it.
        if (toolbarConfig.collapsed) {
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.bottomMargin = gap
            lp.marginEnd = gap
            binding.tvPageIndicator.layoutParams = lp
            return
        }
        when (toolbarConfig.placement) {
            ToolbarPlacement.BOTTOM -> {
                // Bar sits at the bottom; the top edge is free, so pin flush to the top-right —
                // below the guard, so it lines up with where a top bar would start.
                lp.gravity = Gravity.TOP or Gravity.END
                lp.topMargin = toolbarLayoutManager.topGuard() + gap
                lp.marginEnd = gap
            }
            ToolbarPlacement.RIGHT -> {
                // Bottom-right would sit under the right bar; pin to bottom-left instead.
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.bottomMargin = gap
                lp.marginStart = gap
            }
            else -> {
                // TOP and LEFT: bottom-right is clear of both.
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.bottomMargin = gap
                lp.marginEnd = gap
            }
        }
        binding.tvPageIndicator.layoutParams = lp
    }

    /**
     * Tap-to-follow link gesture (Session 4).
     *
     * A finger single-tap inside a link's bounding box follows the link — in **pen mode only**
     * ([isLinkFollowEnabled]; not lasso, lasso-eraser, or text-placement, where a finger tap means
     * something else). Stylus events never reach this detector, so writing/erasing over a link with
     * the pen is untouched. Tap-vs-drag gated (short, near-stationary, single pointer) so it never
     * collides with the page-swipe/back-swipe gestures.
     *
     * The candidate link is captured at DOWN; the follow fires at UP only if the touch never strayed
     * past slop and lifts back inside the same box.
     */
    private fun handleLinkFollowGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                linkTapDownX = event.x
                linkTapDownY = event.y
                linkTapDownTime = event.eventTime
                linkTapMoved = false
                if (isLinkFollowEnabled()) {
                    // Sticky notes take priority over links for tap-to-open.
                    stickyNoteTapCandidate = stickyNoteAt(event.x, event.y)
                    linkTapCandidate = if (stickyNoteTapCandidate == null) linkAt(event.x, event.y) else null
                } else {
                    stickyNoteTapCandidate = null
                    linkTapCandidate = null
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger → this is a multi-touch gesture, never a follow tap.
                linkTapMoved = true
                stickyNoteTapCandidate = null
                linkTapCandidate = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (!linkTapMoved && (linkTapCandidate != null || stickyNoteTapCandidate != null)) {
                    val dx = event.x - linkTapDownX
                    val dy = event.y - linkTapDownY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > toggleTouchSlopPx) linkTapMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val stickyCandidate = stickyNoteTapCandidate
                val candidate       = linkTapCandidate
                stickyNoteTapCandidate = null
                linkTapCandidate       = null
                val isTap = (stickyCandidate != null || candidate != null)
                    && !linkTapMoved
                    && event.pointerCount == 1
                    && event.eventTime - linkTapDownTime <= ViewConfiguration.getLongPressTimeout()
                if (isTap) {
                    if (stickyCandidate != null && stickyCandidate.boundingBox.contains(event.x, event.y)) {
                        openStickyNote(stickyCandidate, initialCreate = false)
                    } else if (candidate != null && candidate.boundingBox.contains(event.x, event.y)) {
                        followLink(candidate)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                linkTapMoved = true
                stickyNoteTapCandidate = null
                linkTapCandidate = null
            }
        }
    }

    /**
     * Tap-to-follow ([handleLinkFollowGesture]) runs for a clean one-finger tap in every mode.
     * [handleLinkFollowGesture] is finger-only (see [dispatchTouchEvent]), so this never conflicts
     * with the stylus-driven lasso selection, lasso erase, or stylus tap-to-paste — those are
     * separate input and keep their behavior. (Kept as a named predicate so the tap-follow guards
     * stay readable and re-gating link-follow later is a one-line change.)
     */
    private fun isLinkFollowEnabled(): Boolean = true

    /** Topmost link whose bounding box contains the view-space point ([x],[y]), or null. */
    private fun linkAt(x: Float, y: Float): LinkRender? =
        drawingView.getLinks().lastOrNull { it.boundingBox.contains(x, y) }

    /**
     * One-finger double-tap on the canvas toggles the toolbar collapsed/expanded. Always active —
     * the double-tap is the only way to hide the bar and the only way to bring it back, so it never
     * strands the user. Finger-only. A "tap" is a short, near-stationary, single-pointer touch that
     * doesn't land on any toolbar chrome; two such taps within the platform double-tap window (and
     * slop) fire the toggle. The movement + single-pointer guards keep it clear of the page-swipe
     * and two-finger page-insert gestures (large / multi-touch).
     */
    private fun handleToolbarToggleGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toggleTapDownTime = event.eventTime
                toggleTapDownX = event.rawX
                toggleTapDownY = event.rawY
                toggleTapMoved = false
                // A tap that follows a link or opens a sticky note must not also toggle the toolbar.
                toggleDownOnLink = isLinkFollowEnabled() &&
                    (linkAt(event.x, event.y) != null || stickyNoteAt(event.x, event.y) != null)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger → this is a multi-touch gesture, never a tap.
                toggleTapMoved = true
                toggleFirstTapTime = 0L
            }
            MotionEvent.ACTION_MOVE -> {
                if (!toggleTapMoved) {
                    val dx = event.rawX - toggleTapDownX
                    val dy = event.rawY - toggleTapDownY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > toggleTouchSlopPx) toggleTapMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - toggleTapDownTime
                val isTap = !toggleTapMoved
                    && event.pointerCount == 1
                    && duration <= ViewConfiguration.getLongPressTimeout()
                    && !isPointInToolbarChrome(event)
                    && !toggleDownOnLink
                if (!isTap) {
                    toggleFirstTapTime = 0L
                    return
                }
                val now = event.eventTime
                val withinTime = toggleFirstTapTime != 0L
                    && now - toggleFirstTapTime <= ViewConfiguration.getDoubleTapTimeout()
                val withinSlop = Math.hypot(
                    (event.rawX - toggleFirstTapX).toDouble(),
                    (event.rawY - toggleFirstTapY).toDouble(),
                ) <= toggleDoubleTapSlopPx
                if (withinTime && withinSlop) {
                    toggleFirstTapTime = 0L
                    toggleToolbarCollapsed()
                } else {
                    toggleFirstTapTime = now
                    toggleFirstTapX = event.rawX
                    toggleFirstTapY = event.rawY
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                toggleTapMoved = true
                toggleFirstTapTime = 0L
            }
        }
    }

    /**
     * Multi-finger stationary double-tap: 2 fingers = undo, 3 fingers = redo.
     *
     * Arms on the first [ACTION_POINTER_DOWN] (second finger lands). Evaluates at [ACTION_UP]
     * (all fingers lifted) only when the centroid stayed within tap slop and the total gesture
     * duration was short. Peak pointer count distinguishes the two cases — a 3-finger gesture
     * never triggers the 2-finger undo branch. The movement guard keeps this completely clear
     * of the 2-finger swipe (page insert), which requires significant centroid travel.
     *
     * 4+ fingers cancel silently. [ACTION_CANCEL] resets first-tap memory so an interrupted
     * double-tap doesn't linger and fire on the next independent gesture.
     */
    private fun handleMultiFingerDoubleTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mfTapPeakCount = 1
                mfTapArmed = false
                mfTapMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val count = event.pointerCount
                if (count > mfTapPeakCount) mfTapPeakCount = count
                if (count >= 4) {
                    mfTapArmed = false
                    mfTapMoved = true
                    return
                }
                if (!mfTapArmed) {
                    mfTapArmed = true
                    mfTapDownTime = event.eventTime
                }
                // Always update the centroid baseline when finger count changes so that MOVE
                // events with N fingers are compared against an N-finger baseline — not the
                // 2-finger centroid recorded when the second finger landed.  Without this, a
                // third finger landing at a different position shifts the centroid past slop
                // and incorrectly marks the gesture as moved.
                mfTapCentroidStartX = (0 until count).map { event.getX(it) }.average().toFloat()
                mfTapCentroidStartY = (0 until count).map { event.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mfTapArmed && !mfTapMoved && event.pointerCount >= 2) {
                    val count = event.pointerCount
                    val cx = (0 until count).map { event.getX(it) }.average().toFloat()
                    val cy = (0 until count).map { event.getY(it) }.average().toFloat()
                    val dx = cx - mfTapCentroidStartX
                    val dy = cy - mfTapCentroidStartY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > toggleTouchSlopPx) mfTapMoved = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mfTapArmed && !mfTapMoved) {
                    val duration = event.eventTime - mfTapDownTime
                    if (duration <= ViewConfiguration.getLongPressTimeout()) {
                        val now = event.eventTime
                        when (mfTapPeakCount) {
                            2 -> {
                                val withinTime = twoFingerTapFirstTime != 0L
                                    && now - twoFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                                val withinSlop = twoFingerTapFirstTime != 0L
                                    && Math.hypot(
                                        (mfTapCentroidStartX - twoFingerTapFirstX).toDouble(),
                                        (mfTapCentroidStartY - twoFingerTapFirstY).toDouble(),
                                    ) <= toggleDoubleTapSlopPx
                                if (withinTime && withinSlop) {
                                    twoFingerTapFirstTime = 0L
                                    drawingView.releaseRender()
                                    performUndo()
                                } else {
                                    twoFingerTapFirstTime = now
                                    twoFingerTapFirstX = mfTapCentroidStartX
                                    twoFingerTapFirstY = mfTapCentroidStartY
                                }
                            }
                            3 -> {
                                val withinTime = threeFingerTapFirstTime != 0L
                                    && now - threeFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                                val withinSlop = threeFingerTapFirstTime != 0L
                                    && Math.hypot(
                                        (mfTapCentroidStartX - threeFingerTapFirstX).toDouble(),
                                        (mfTapCentroidStartY - threeFingerTapFirstY).toDouble(),
                                    ) <= toggleDoubleTapSlopPx
                                if (withinTime && withinSlop) {
                                    threeFingerTapFirstTime = 0L
                                    drawingView.releaseRender()
                                    performRedo()
                                } else {
                                    threeFingerTapFirstTime = now
                                    threeFingerTapFirstX = mfTapCentroidStartX
                                    threeFingerTapFirstY = mfTapCentroidStartY
                                }
                            }
                        }
                    }
                }
                mfTapArmed = false
                mfTapMoved = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // On BOOX the Onyx SDK intercepts 3-finger touches and immediately cancels the
                // gesture — ACTION_UP never fires. Treat a cancel on an armed, stationary
                // 3-finger gesture as a valid tap completion so double-tap redo can work.
                if (mfTapArmed && !mfTapMoved && mfTapPeakCount == 3) {
                    val now = SystemClock.uptimeMillis()
                    val withinTime = threeFingerTapFirstTime != 0L
                        && now - threeFingerTapFirstTime <= ViewConfiguration.getDoubleTapTimeout()
                    val withinSlop = threeFingerTapFirstTime != 0L
                        && Math.hypot(
                            (mfTapCentroidStartX - threeFingerTapFirstX).toDouble(),
                            (mfTapCentroidStartY - threeFingerTapFirstY).toDouble(),
                        ) <= toggleDoubleTapSlopPx
                    if (withinTime && withinSlop) {
                        threeFingerTapFirstTime = 0L
                        drawingView.releaseRender()
                        performRedo()
                    } else {
                        threeFingerTapFirstTime = now
                        threeFingerTapFirstX = mfTapCentroidStartX
                        threeFingerTapFirstY = mfTapCentroidStartY
                    }
                } else {
                    twoFingerTapFirstTime = 0L
                    threeFingerTapFirstTime = 0L
                }
                mfTapArmed = false
                mfTapMoved = false
            }
        }
    }

    /**
     * One-finger stationary long-press on the canvas → open the page [showPageMenu]. Arms a posted
     * runnable on ACTION_DOWN and lets it fire while the finger is still held (standard long-press
     * feel), cancelling on movement, a second finger, UP/CANCEL, or the pen-activity gate.
     *
     * Guarded against every context where a long-press would be ambiguous: over toolbar chrome or
     * the shape-insert picker, over a followable link / sticky note (which own the finger tap), and
     * while a lasso / lasso-eraser / text-placement / shape-transform mode is active. The runnable
     * re-checks pen-active + finishing state at fire time, since ~500ms elapse before it runs.
     */
    private fun handlePageMenuLongPress(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Finger-only gesture — orthogonal to the active *stylus* tool. Deliberately NOT
                // guarded on lasso / text-placement / shape-transform modes: those are stylus
                // interactions, and the other finger gestures (swipe, double-tap toggle) run in
                // them freely. Only genuine finger-tap conflicts are excluded: toolbar chrome, the
                // shape-insert picker, an open overflow, and a followable link / sticky note.
                val eligible = event.pointerCount == 1
                    && !drawingView.isPenActive
                    && !isPointInToolbarChrome(event)
                    && binding.shapeInsertToolbar.visibility != View.VISIBLE
                    && !overflowManager.isOverflowMenuOpen()
                    && !(isLinkFollowEnabled()
                        && (linkAt(event.x, event.y) != null || stickyNoteAt(event.x, event.y) != null))
                if (!eligible) {
                    cancelPageMenuLongPress()
                    return
                }
                pageMenuDownX = event.rawX
                pageMenuDownY = event.rawY
                cancelPageMenuLongPress()
                val r = Runnable {
                    pageMenuLongPressRunnable = null
                    if (isFinishing || isDestroyed) return@Runnable
                    if (drawingView.isPenActive) return@Runnable
                    showPageMenu()
                }
                pageMenuLongPressRunnable = r
                pageMenuLongPressHandler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelPageMenuLongPress()
            MotionEvent.ACTION_MOVE -> {
                if (pageMenuLongPressRunnable != null) {
                    val dx = event.rawX - pageMenuDownX
                    val dy = event.rawY - pageMenuDownY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > toggleTouchSlopPx) cancelPageMenuLongPress()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelPageMenuLongPress()
        }
    }

    /** Cancel any pending page-menu long-press. Safe to call when nothing is armed. */
    private fun cancelPageMenuLongPress() {
        pageMenuLongPressRunnable?.let { pageMenuLongPressHandler.removeCallbacks(it) }
        pageMenuLongPressRunnable = null
    }

    /**
     * Abandon all in-flight finger-gesture state, silently.
     *
     * Called once when the pen-activity gate closes ([fingerGesturesSuppressed]). This resets the
     * same fields the detectors' own `ACTION_CANCEL` branches do, but deliberately does **not**
     * route through them: [handleMultiFingerDoubleTap]'s cancel branch treats a cancel on an armed
     * 3-finger gesture as a *completed* tap (the Onyx 3-finger interception workaround), which is
     * exactly the false positive this gate exists to prevent.
     *
     * Clearing the first-tap timestamps matters as much as the in-flight flags — a stale armed
     * first tap would otherwise pair with a real tap after the pen lifts and fire a phantom
     * undo / redo / toolbar toggle.
     */
    private fun cancelFingerGestures() {
        // One-finger + two-finger page swipe.
        pageSwipeActive = false
        pageSwipeVelocityTracker?.recycle()
        pageSwipeVelocityTracker = null
        twoFingerSwipeActive = false
        twoFingerSwipeVelocityTracker?.recycle()
        twoFingerSwipeVelocityTracker = null

        // Link / sticky-note tap-to-follow.
        linkTapMoved = true
        linkTapCandidate = null
        stickyNoteTapCandidate = null

        // One-finger double-tap toolbar toggle.
        toggleTapMoved = true
        toggleFirstTapTime = 0L

        // Multi-finger double-tap undo / redo.
        mfTapArmed = false
        mfTapMoved = true
        mfTapPeakCount = 0
        twoFingerTapFirstTime = 0L
        threeFingerTapFirstTime = 0L

        // Page long-press menu.
        cancelPageMenuLongPress()
    }

    /**
     * True if the touch lands on any visible toolbar chrome (the bar itself, an open overflow menu,
     * or the floating selection / lasso popup toolbars) — places where a double-tap must NOT
     * collapse the toolbar.
     */
    private fun isPointInToolbarChrome(event: MotionEvent): Boolean {
        if (binding.drawingToolbar.visibility == View.VISIBLE && isTouchInToolbar(event)) return true
        if (overflowManager.isOverflowMenuOpen()) {
            val m = binding.overflowMenu
            if (event.x >= m.left && event.x < m.right && event.y >= m.top && event.y < m.bottom) return true
        }
        for (v in arrayOf(binding.floatingSelectionToolbar, binding.lassoPopupToolbar)) {
            if (v.visibility == View.VISIBLE && isTouchInView(event, v)) return true
        }
        return false
    }

    /** Flip the toolbar between collapsed (hidden, peek tab showing) and expanded, persisting the
     *  new state so it survives a notebook reopen. */
    private fun toggleToolbarCollapsed() {
        toolbarConfig = toolbarConfig.copy(collapsed = !toolbarConfig.collapsed)
        ToolbarPreferencesManager.save(this, toolbarConfig)
        applyCollapsedState()
    }

    /**
     * Apply [ToolbarConfig.collapsed] to the live views. Collapsed: hide the bar (closing any open
     * overflow menu first) and clear the pen-exclusion rect so the whole canvas is writable.
     * Expanded: show the bar and restore the exclusion rect once it relays out. Releases the EPD
     * overlay either way so the change paints immediately on e-ink.
     */
    private fun applyCollapsedState() {
        // Reposition the page indicator: hidden bar → lower-right, visible bar → placement corner.
        // It hides with the bar, so a one-finger double-tap clears all chrome off the page.
        positionPageIndicator()
        binding.tvPageIndicator.visibility =
            if (toolbarConfig.collapsed) View.GONE else View.VISIBLE
        if (toolbarConfig.collapsed) {
            if (overflowManager.isOverflowMenuOpen()) overflowManager.closeOverflowMenu()
            binding.drawingToolbar.visibility = View.GONE
            pushToolbarExclusion()
            // Defer to the next looper turn so reapplyDrawingBounds() runs AFTER the touch event
            // that triggered the collapse has fully exited dispatchTouchEvent. Calling SDK restart
            // methods mid-touch-event appears to silently fail on the Onyx SDK.
            binding.root.post { drawingView.reapplyDrawingBounds() }
        } else {
            binding.drawingToolbar.visibility = View.VISIBLE
            // The bar's bounds are stale (0) until it relays out from GONE → VISIBLE; push the real
            // exclusion rect and re-arm the Onyx SDK once that pass completes.
            binding.drawingToolbar.doOnLayout {
                pushToolbarExclusion()
                drawingView.reapplyDrawingBounds()
            }
        }
        drawingView.releaseRender()
    }

    /**
     * Persists [config] and re-applies the live toolbar in place: closes any open overflow menu,
     * rearranges the existing button views via [toolbarLayoutManager], resets the overflow manager so
     * it re-derives its moveable set + dividers, then recalculates fit. Move-not-clone throughout, so
     * `isSelected` state and click listeners survive.
     */
    private fun applyToolbarConfig(config: ToolbarConfig) {
        toolbarConfig = config
        ToolbarPreferencesManager.save(this, config)
        closeOverflowMenu()
        toolbarLayoutManager.apply(config)
        overflowManager.setLeadingPinned(toolbarLayoutManager.dragHandle)
        overflowManager.reset()
        wireFloatDragHandle()
        positionOverflowMenu()
        positionPageIndicator()
        // Placement may have moved the bar; the cached lasso anchor must be recomputed against the
        // new position.
        lassoToolbarAnchor = null
        // A placement change can flip the bar's orientation + size (top/bottom ↔ left/right). recalc()
        // and the exclusion rect both read the toolbar's *measured* extent, which stays stale until
        // the relayout triggered by the new orientation/layoutParams runs. Wait for that pass (so the
        // fit is computed against real dimensions), then post the recalc so its view reparenting +
        // requestLayout land *outside* the layout traversal — see [recalcOverflowAfterLayout].
        binding.drawingToolbar.requestLayout()
        binding.drawingToolbar.doOnLayout {
            computeLassoToolbarAnchor()
            recalcOverflowAfterLayout()
        }
    }

    /**
     * Recompute overflow + push the exclusion rect, deferred to *after* the current layout traversal.
     *
     * [ToolbarOverflowManager.recalc] reparents button views and calls `requestLayout()`. Running it
     * synchronously from inside a layout callback (`doOnLayout` / `addOnLayoutChangeListener`, both
     * dispatched during `View.layout()`) hits the "requestLayout during layout" trap: the re-shown
     * overflow button isn't laid out until some later unrelated pass, so it appears to vanish on a
     * same-size placement switch (e.g. top↔bottom). Posting runs the mutation cleanly after the
     * traversal, giving it its own layout pass. Call only from contexts where the toolbar is already
     * laid out at its current size, so recalc reads fresh dimensions.
     */
    private fun recalcOverflowAfterLayout() {
        binding.drawingToolbar.post {
            overflowManager.recalc()
            // recalc may have changed the overflow line count; re-anchor the menu (FLOAT's open-side
            // decision reads that count) before pushing the matching exclusion rect.
            positionOverflowMenu()
            pushToolbarExclusion()
        }
    }

    /**
     * Wire (or no-op) the long-drag behaviour on the FLOAT bar's grip handle. A finger drag on the
     * handle repositions the floating bar within the screen; on release the new `{floatX, floatY}`
     * are persisted and the pen exclusion rect + overflow menu anchor are refreshed for the new spot.
     *
     * Cancels any armed page-swipe on touch-down so dragging the bar across the screen never turns a
     * page. No-op for edge-anchored placements (the handle only exists in FLOAT). Re-called after each
     * [toolbarLayoutManager] apply, since FLOAT re-entry creates a fresh handle.
     */
    private fun wireFloatDragHandle() {
        val handle = toolbarLayoutManager.dragHandle ?: return
        var startRawX = 0f
        var startRawY = 0f
        var startMarginL = 0
        var startMarginT = 0
        handle.setOnTouchListener { _, event ->
            val lp = binding.drawingToolbar.layoutParams as FrameLayout.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // dispatchTouchEvent armed the page-swipe on this same finger DOWN; disarm it so a
                    // long bar drag can't satisfy the swipe distance and turn a page.
                    pageSwipeActive = false
                    drawingView.releaseRender()
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startMarginL = lp.leftMargin
                    startMarginT = lp.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (binding.root.width - binding.drawingToolbar.width).coerceAtLeast(0)
                    val maxY = (binding.root.height - binding.drawingToolbar.height).coerceAtLeast(0)
                    // Floor the drag at the top guard so the bar can't be parked in the status bar's
                    // reveal zone (see ToolbarLayoutManager.topGuard).
                    val minY = toolbarLayoutManager.topGuard()
                    lp.leftMargin = (startMarginL + (event.rawX - startRawX).toInt()).coerceIn(0, maxX)
                    lp.topMargin  = (startMarginT + (event.rawY - startRawY).toInt())
                        .coerceIn(minY, maxY.coerceAtLeast(minY))
                    binding.drawingToolbar.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    toolbarConfig = toolbarConfig.copy(
                        floatX = lp.leftMargin.toFloat(),
                        floatY = lp.topMargin.toFloat(),
                    )
                    ToolbarPreferencesManager.save(this, toolbarConfig)
                    // Re-anchor pen exclusion + overflow menu to the bar's new resting position.
                    binding.drawingToolbar.doOnLayout {
                        pushToolbarExclusion()
                        positionOverflowMenu()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * One-finger deliberate page-turn detector.
     *
     * Arms on the first finger down; fires at ACTION_UP if the gesture passes
     * all three guards in [evaluatePageFling].  A second finger immediately
     * cancels so palm+finger combos and pinch-style gestures never turn pages.
     */
    private fun handlePageSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Single finger down — arm and record the start position.
                pageSwipeActive = true
                pageSwipeStartX = event.x
                pageSwipeStartY = event.y
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // If the one-finger swipe already covers qualifying distance, commit it now
                // before the second finger lands — a palm mid-swipe must not kill a valid turn.
                if (pageSwipeActive) {
                    val tracker = pageSwipeVelocityTracker
                    val dx = event.getX(0) - pageSwipeStartX
                    val dy = event.getY(0) - pageSwipeStartY
                    if (tracker != null && pageSwipeQualifies(dx, dy)) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val vx = tracker.getXVelocity(0)
                        val vy = tracker.getYVelocity(0)
                        evaluatePageFling(vx, vy, dx, dy)
                        tracker.recycle()
                        pageSwipeVelocityTracker = null
                        pageSwipeActive = false
                        // Do NOT arm the two-finger insert — one-finger turn was committed.
                        return
                    }
                }
                // Cancel the 1-finger gesture.
                pageSwipeActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
                // Arm 2-finger insert gesture when exactly two fingers are down.
                if (event.pointerCount == 2) {
                    twoFingerSwipeActive = true
                    twoFingerSwipeStartX = (event.getX(0) + event.getX(1)) / 2f
                    twoFingerSwipeStartY = (event.getY(0) + event.getY(1)) / 2f
                    twoFingerSwipeVelocityTracker?.recycle()
                    twoFingerSwipeVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                } else {
                    // 3+ fingers. If the two-finger swipe already qualifies, commit the insert now
                    // before the extra finger (e.g. a palm) cancels it.
                    if (twoFingerSwipeActive) {
                        val tracker = twoFingerSwipeVelocityTracker
                        val endX = (event.getX(0) + event.getX(1)) / 2f
                        val endY = (event.getY(0) + event.getY(1)) / 2f
                        val dx = endX - twoFingerSwipeStartX
                        val dy = endY - twoFingerSwipeStartY
                        if (tracker != null && pageSwipeQualifies(dx, dy)) {
                            tracker.addMovement(event)
                            tracker.computeCurrentVelocity(1000)
                            val vx = tracker.getXVelocity(0)
                            val vy = tracker.getYVelocity(0)
                            evaluateTwoFingerInsertFling(vx, vy, dx, dy)
                        }
                    }
                    twoFingerSwipeActive = false
                    twoFingerSwipeVelocityTracker?.recycle()
                    twoFingerSwipeVelocityTracker = null
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifted from a 2-finger gesture — evaluate and clean up.
                // Both pointers are still present at POINTER_UP, so use their centroid
                // as the end point (consistent with how the start centroid was recorded).
                if (twoFingerSwipeActive && event.pointerCount == 2) {
                    val tracker = twoFingerSwipeVelocityTracker
                    if (tracker != null) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val endX = (event.getX(0) + event.getX(1)) / 2f
                        val endY = (event.getY(0) + event.getY(1)) / 2f
                        val vx = tracker.getXVelocity(0)
                        val vy = tracker.getYVelocity(0)
                        val dx = endX - twoFingerSwipeStartX
                        val dy = endY - twoFingerSwipeStartY
                        evaluateTwoFingerInsertFling(vx, vy, dx, dy)
                        tracker.recycle()
                        twoFingerSwipeVelocityTracker = null
                    }
                    twoFingerSwipeActive = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pageSwipeActive) {
                    pageSwipeVelocityTracker?.addMovement(event)
                }
                if (twoFingerSwipeActive && event.pointerCount >= 2) {
                    twoFingerSwipeVelocityTracker?.addMovement(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pageSwipeActive) {
                    val tracker = pageSwipeVelocityTracker
                    if (tracker != null) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val vx = tracker.getXVelocity(0)
                        val vy = tracker.getYVelocity(0)
                        val dx = event.x - pageSwipeStartX
                        val dy = event.y - pageSwipeStartY
                        // A deliberate upward swipe walks the link back-stack; a downward swipe
                        // opens the TOC; otherwise fall through to the horizontal page-turn evaluator.
                        if (!evaluateSwipeUpBack(vy, dx, dy) && !evaluateSwipeDownToc(vy, dx, dy)) {
                            evaluatePageFling(vx, vy, dx, dy)
                        }
                        tracker.recycle()
                        pageSwipeVelocityTracker = null
                    }
                }
                pageSwipeActive = false
                twoFingerSwipeActive = false
                twoFingerSwipeVelocityTracker?.recycle()
                twoFingerSwipeVelocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                pageSwipeActive = false
                pageSwipeVelocityTracker?.recycle()
                pageSwipeVelocityTracker = null
                twoFingerSwipeActive = false
                twoFingerSwipeVelocityTracker?.recycle()
                twoFingerSwipeVelocityTracker = null
            }
        }
    }

    /**
     * Returns true if the displacement is horizontal-dominant and covers the minimum
     * qualifying distance. Used to commit a page turn early when a second finger lands.
     */
    private fun pageSwipeQualifies(dx: Float, dy: Float): Boolean {
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx <= absDy) return false
        val minDist = PAGE_SWIPE_MIN_DISTANCE_FRAC * resources.displayMetrics.widthPixels.toFloat()
        return absDx >= minDist
    }

    /**
     * Navigates pages if the gesture qualifies.
     *
     * Distance is the primary gate: ≥30% of screen width is required; ≥50% qualifies
     * regardless of velocity. A quick flick covering ≥30% also qualifies. Direction
     * is derived from displacement (dx), never from velocity, to avoid sign flips when
     * the finger decelerates at lift-off.
     *
     * Swipe left (dx < 0) → next page (or insert new page on last page).
     * Swipe right (dx > 0) → previous page.
     */
    private fun evaluatePageFling(velocityX: Float, velocityY: Float, dx: Float, dy: Float) {
        val absDx = abs(dx)
        val absDy = abs(dy)
        val width  = resources.displayMetrics.widthPixels.toFloat()
        val minDist = PAGE_SWIPE_MIN_DISTANCE_FRAC  * width
        val minVel  = ViewConfiguration.get(this).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
        val fastEnough = abs(velocityX) >= minVel
        val longEnough = absDx >= PAGE_SWIPE_LONG_DISTANCE_FRAC * width

        Slog.d(TAG) {
            "evaluatePageFling dx=$dx dy=$dy vx=$velocityX vy=$velocityY " +
            "width=$width minDist=$minDist minVel=$minVel " +
            "fastEnough=$fastEnough longEnough=$longEnough"
        }

        if (absDx <= absDy) {
            Slog.d(TAG) { "evaluatePageFling rejected: not horizontal (absDx=$absDx absDy=$absDy)" }
            return
        }
        if (absDx < minDist) {
            Slog.d(TAG) { "evaluatePageFling rejected: too short (absDx=$absDx minDist=$minDist)" }
            return
        }
        if (!fastEnough && !longEnough) {
            Slog.d(TAG) { "evaluatePageFling rejected: neither fast nor long" }
            return
        }

        val goNext = dx < 0
        Slog.d(TAG) { "evaluatePageFling accepted: ${if (goNext) "next" else "prev"}" }

        if (goNext) {
            // Swipe left → advance to next page.
            val next = currentPageIndex + 1
            if (next <= pages.lastIndex) {
                navigateToPage(next)
            } else {
                // Already on the last page — insert a new page (same as the + button).
                val db = soilDatabase ?: return
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        addPage(db)
                    }
                    undoRedoManager.push(UndoRedoAction.PageAdded(currentPageId, currentPageIndex))
                    updateUndoRedoButtons()
                    selectedObjectIds.clear()
                    drawingView.setLassoOverlay(null, null)
                    hideFloatingSelectionToolbar()
                    drawingView.clearForPageLoad()
                    val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                    displayPage(result)
                    updatePageIndicator()
                    saveLastOpenedPage(currentPageId)
                    postDisplayWork(db, result)
                }
            }
        } else {
            // Swipe right → go back to previous page.
            val prev = currentPageIndex - 1
            if (prev >= 0) navigateToPage(prev)
        }
    }

    /**
     * One-finger upward swipe → walk back through the link [LinkBackStack].
     *
     * Mirrors [evaluatePageFling]'s gates but on the *vertical* axis: vertical-dominant, upward
     * ([dy] < 0), ≥30% of screen *height* to qualify at all, and either fast enough or ≥50% of
     * height. Returns true when a qualifying back-swipe was consumed (so the caller skips the
     * horizontal page-turn evaluator). An empty back-stack makes the follow a no-op [A3].
     */
    private fun evaluateSwipeUpBack(velocityY: Float, dx: Float, dy: Float): Boolean {
        val absDx = abs(dx)
        val absDy = abs(dy)
        val height = resources.displayMetrics.heightPixels.toFloat()
        val minDist = PAGE_SWIPE_MIN_DISTANCE_FRAC * height
        val minVel = ViewConfiguration.get(this).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
        val fastEnough = abs(velocityY) >= minVel
        val longEnough = absDy >= PAGE_SWIPE_LONG_DISTANCE_FRAC * height

        if (dy >= 0) return false                 // must be upward
        if (absDy <= absDx) return false          // must be vertical-dominant
        if (absDy < minDist) return false         // must cover the minimum distance
        if (!fastEnough && !longEnough) return false

        Slog.d(TAG) { "evaluateSwipeUpBack accepted: dy=$dy vy=$velocityY" }
        followBack()
        return true
    }

    /**
     * One-finger downward swipe → open the table of contents.
     *
     * Mirrors [evaluateSwipeUpBack]'s gates but downward ([dy] > 0): vertical-dominant, ≥30% of
     * screen *height* to qualify at all, and either fast enough or ≥50% of height. Returns true
     * when a qualifying swipe was consumed (so the caller skips the horizontal page-turn evaluator).
     */
    private fun evaluateSwipeDownToc(velocityY: Float, dx: Float, dy: Float): Boolean {
        val absDx = abs(dx)
        val absDy = abs(dy)
        val height = resources.displayMetrics.heightPixels.toFloat()
        val minDist = PAGE_SWIPE_MIN_DISTANCE_FRAC * height
        val minVel = ViewConfiguration.get(this).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
        val fastEnough = abs(velocityY) >= minVel
        val longEnough = absDy >= PAGE_SWIPE_LONG_DISTANCE_FRAC * height

        if (dy <= 0) return false                 // must be downward
        if (absDy <= absDx) return false          // must be vertical-dominant
        if (absDy < minDist) return false         // must cover the minimum distance
        if (!fastEnough && !longEnough) return false

        Slog.d(TAG) { "evaluateSwipeDownToc accepted: dy=$dy vy=$velocityY" }
        openToc()
        return true
    }

    /** Builds the TOC tree off-thread and shows the [TocDialog]. */
    private fun openToc() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            val tree = withContext(Dispatchers.IO) {
                TocRepository(db.notebookDao()).buildTocTree()
            }
            TocDialog(
                context = this@NotebookActivity,
                nodes = tree,
                currentPageIndex = currentPageIndex,
                onPageSelected = { pageId ->
                    val index = pages.indexOfFirst { it.id == pageId }
                    if (index >= 0 && index != currentPageIndex) navigateToPage(index)
                }
            ).show()
        }
    }

    /**
     * Inserts a new page relative to the current one if the two-finger gesture qualifies.
     *
     * Uses the same distance-primary gate as [evaluatePageFling]: horizontal dominance is
     * required, ≥30% screen width is the minimum distance, and ≥50% screen width qualifies
     * regardless of velocity. A quick flick covering ≥30% also qualifies. Direction is
     * derived from displacement (dx), never velocity, to avoid sign flips on lift-off.
     *
     * Swipe left (dx < 0) → insert AFTER the current page, navigate to it.
     * Swipe right (dx > 0) → insert BEFORE the current page, navigate to it.
     */
    private fun evaluateTwoFingerInsertFling(velocityX: Float, velocityY: Float, dx: Float, dy: Float) {
        val absDx = abs(dx)
        val absDy = abs(dy)
        val width  = resources.displayMetrics.widthPixels.toFloat()
        val minVel = ViewConfiguration.get(this).scaledMinimumFlingVelocity * PAGE_SWIPE_MIN_VELOCITY_MULT
        val fastEnough = abs(velocityX) >= minVel
        val longEnough = absDx >= PAGE_SWIPE_LONG_DISTANCE_FRAC * width

        Slog.d(TAG) {
            "evaluateTwoFingerInsertFling dx=$dx dy=$dy vx=$velocityX vy=$velocityY " +
            "width=$width fastEnough=$fastEnough longEnough=$longEnough"
        }

        if (absDx <= absDy) {
            Slog.d(TAG) { "evaluateTwoFingerInsertFling rejected: not horizontal (absDx=$absDx absDy=$absDy)" }
            return
        }
        if (absDx < PAGE_SWIPE_MIN_DISTANCE_FRAC * width) {
            Slog.d(TAG) { "evaluateTwoFingerInsertFling rejected: too short (absDx=$absDx minDist=${PAGE_SWIPE_MIN_DISTANCE_FRAC * width})" }
            return
        }
        if (!fastEnough && !longEnough) {
            Slog.d(TAG) { "evaluateTwoFingerInsertFling rejected: neither fast nor long" }
            return
        }

        val insertAfter = dx < 0
        Slog.d(TAG) { "evaluateTwoFingerInsertFling accepted: ${if (insertAfter) "insert after" else "insert before"}" }

        if (insertAfter) {
            insertPageAfterCurrentAndNavigate()
        } else {
            insertPageBeforeCurrentAndNavigate()
        }
    }

    private fun insertPageAfterCurrentAndNavigate() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                addPage(db)
            }
            undoRedoManager.push(UndoRedoAction.PageAdded(currentPageId, currentPageIndex))
            updateUndoRedoButtons()
            drawingView.clearForPageLoad()
            val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
            displayPage(result)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            postDisplayWork(db, result)
        }
    }

    private fun insertPageBeforeCurrentAndNavigate() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                addPageBefore(db)
            }
            undoRedoManager.push(UndoRedoAction.PageAdded(currentPageId, currentPageIndex, insertedBefore = true))
            updateUndoRedoButtons()
            drawingView.clearForPageLoad()
            val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
            displayPage(result)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            postDisplayWork(db, result)
        }
    }

    // ── Notebook DB lifecycle ─────────────────────────────────────────────────

    /**
     * Tears down the open notebook: captures the current page as the library-grid cover, then seals
     * the file (save strokes, housekeeping PRAGMAs, close the Room DB, delete the stray -journal).
     *
     * The cover snapshot is captured here on the main thread (before [soilDatabase] is cleared, and
     * View work must run on the main thread) so the close button and back-press paths always update
     * the grid cover. [onWindowFocusChanged] fires AFTER [finish], when [soilDatabase] is already
     * null — capturing here is the only reliable close-path hook.
     *
     * The heavy seal ([sealNotebook]) runs on [Dispatchers.IO]:
     * - [blocking] = false (user-initiated close): launched on [NotesproutApplication.appScope]
     *   so it outlives this activity. [finish] fires immediately, off the seal — no ANR/jank
     *   on large notebooks. The null-guard below makes the later [onDestroy] call a no-op, so
     *   exactly one seal runs per notebook instance.
     * - [blocking] = true ([onDestroy] safety net on an abnormal teardown): sealed synchronously
     *   via [runBlocking] so the file is sealed before the process can die. Only reached when no
     *   user-initiated close ran first (otherwise [soilDatabase] is already null → early return).
     *
     * Idempotent — guarded by a null check.
     */
    private fun closeNotebook(blocking: Boolean = false) {
        val db = soilDatabase ?: return
        soilDatabase = null   // mark as closed before any potentially-throwing work

        // For encrypted notebooks, persist undo/redo state into the .soil before clearing
        // so history survives across close/reopen sessions (P2.S3).
        if (encryptionInfo.encrypted) {
            try {
                db.openHelper.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO undo_redo_state (id, json) VALUES (0, ?)",
                    arrayOf(undoRedoManager.toJson())
                )
            } catch (e: Exception) {
                Log.e(TAG, "closeNotebook: failed to persist undo/redo state", e)
            }
        }

        // Clear history and remove any on-disk persistence file — notebook is done.
        // Clipboard is app-global and intentionally survives notebook transitions.
        undoRedoManager.clear()
        val nbPath = notebookSoilPath
        if (nbPath != null) undoRedoPersistenceFile(nbPath).takeIf { it.exists() }?.delete()

        // Capture snapshot AND the stroke-list copy on the main thread — View operations must run
        // here, and copying the stroke list from the seal's IO context would race UI mutations.
        val snapshot = drawingView.captureSnapshot()
        val strokesAtClose = drawingView.getStrokes()
        val pageId   = currentPageId

        val nbId         = notebookId
        val sessionStart = sessionStartTime
        if (blocking) {
            runBlocking { sealNotebook(db, snapshot, strokesAtClose, pageId, nbPath, nbId, sessionStart) }
        } else {
            NotesproutApplication.appScope.launch { sealNotebook(db, snapshot, strokesAtClose, pageId, nbPath, nbId, sessionStart) }
        }
    }

    // ── Toolbar lock / lock-off ───────────────────────────────────────────────

    private fun updateLockButtonVisibility(info: EncryptionInfo) {
        // Encrypt is offered only for a (rare) plaintext notebook, e.g. a freshly-imported .soil.
        // Encrypted notebooks are managed from MainActivity (scope toggle / passphrase); there is no
        // decrypt-to-plaintext path under encrypt-everything.
        binding.btnLock.visibility = if (info.encrypted) View.GONE else View.VISIBLE
    }

    private fun showEncryptFromToolbarDialog(nbId: String) {
        ActionSheetDialog(this)
            .title("Encrypt Notebook")
            .addAction(null, "Encrypt (Global Passphrase)") {
                lifecycleScope.launch { encryptFromToolbar(nbId, KeyScope.GLOBAL) }
            }
            .addAction(null, "Encrypt (Notebook Passphrase)") {
                lifecycleScope.launch { encryptFromToolbar(nbId, KeyScope.NOTEBOOK) }
            }
            .show()
    }

    /**
     * Seals the open notebook on IO without spawning a fire-and-forget job, so the caller
     * can await completion before migrating the file. Skips the page snapshot intentionally
     * (no plaintext content written to the index when converting to encrypted).
     */
    private suspend fun sealForConversion() {
        val db = soilDatabase ?: return
        soilDatabase = null
        undoRedoManager.clear()
        val nbPath = notebookSoilPath
        if (nbPath != null) undoRedoPersistenceFile(nbPath).takeIf { it.exists() }?.delete()
        val strokesAtClose = drawingView.getStrokes() // main thread — see closeNotebook
        val pageId       = currentPageId
        val nbId         = notebookId
        val sessionStart = sessionStartTime
        withContext(Dispatchers.IO) {
            sealNotebook(db, snapshot = null, strokesAtClose, pageId, nbPath, nbId, sessionStart)
        }
    }

    private suspend fun encryptFromToolbar(nbId: String, scope: KeyScope) {
        val key = KeyResolver.resolveForConvertToEncrypted(this, scope) ?: return
        // Cache the passphrase so the immediate reopen skips the prompt (same as new-notebook flow).
        if (scope == KeyScope.NOTEBOOK) PassphraseCache.storeOnce(nbId, key)

        val tvMessage = android.widget.TextView(this).apply {
            text = "Encrypting…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setView(tvMessage)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        try {
            sealForConversion()
            val file = soilFile(this, nbId)
            withContext(Dispatchers.IO) { SoilMigrator.encryptInPlace(file, key) }
            withContext(Dispatchers.IO) { indexRepo.setEncryptionState(nbId, encrypted = true, keyScope = scope) }
            dialog.dismiss()
            // Clean pen-pipeline handoff before reopening this notebook under its new encryption state.
            drawingView.releaseForHandoff()
            startActivity(
                Intent(this, NotebookActivity::class.java).apply {
                    putExtra(EXTRA_NOTEBOOK_ID,   nbId)
                    putExtra(EXTRA_NOTEBOOK_NAME, notebookDisplayName)
                }
            )
            finish()
        } catch (e: Exception) {
            dialog.dismiss()
            android.widget.Toast.makeText(this, "Encryption failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Switch directly from this notebook to another one chosen from the recents dialog —
     * without bouncing through MainActivity.
     *
     * Order of operations:
     * 1. Resolve the selected notebook's [ObjectEntity] (name + parentId) from the index. If it
     *    no longer exists, abort — [RecentsManager.resolve] should already have pruned it.
     * 2. Persist the selected notebook's folder as the browse state so that closing *it* later
     *    lands MainActivity in that folder (return-to-folder contract — see MainActivity.onResume).
     * 3. Close the current notebook: [closeNotebook] captures the snapshot on the main thread and
     *    seals on [NotesproutApplication.appScope] (the seal fires [RecentsManager.recordClose] for
     *    the leaving notebook). [finish] this activity.
     * 4. Launch the selected notebook directly; its [onCreate] fires [RecentsManager.recordOpen].
     */
    private fun switchToRecentNotebook(selectedId: String) {
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) { indexRepo.getNotebook(selectedId) } ?: return@launch

            // Return-to-folder: closing the switched notebook should land in *its* folder.
            AppStateManager.save(this@NotebookActivity, AppViewState(entity.parentId, false))

            // Seal the current notebook (records close), then open the selected one directly.
            closeNotebook()
            // Clean pen-pipeline handoff before the destination notebook opens (the ownership guard
            // still covers the late onDestroy close as a safety net).
            drawingView.releaseForHandoff()
            startActivity(
                Intent(this@NotebookActivity, NotebookActivity::class.java).apply {
                    putExtra(EXTRA_NOTEBOOK_ID,   entity.id)
                    putExtra(EXTRA_NOTEBOOK_NAME, entity.name)
                }
            )
            finish()
        }
    }

    // ── Link following + back-stack (Session 4) ───────────────────────────────

    /**
     * Follow [link] to its target. Every successful follow pushes the *origin* `{notebookId,
     * pageId}` onto the [LinkBackStack] so a later swipe-up can walk back.
     *
     * - [LinkTarget.CurrentNotebookPage] → navigate within this notebook (no close). A self-link to
     *   the page we're already on is a no-op (no push). Missing page → toast, no nav.
     * - [LinkTarget.OtherNotebook] / [LinkTarget.OtherNotebookPage] → app-level switch via
     *   [openLinkedNotebook] (close current, open target).
     *
     * Runs on the main thread (called from the finger-gesture path).
     */
    private fun followLink(link: LinkRender) {
        val origin = BackEntry(notebookId, currentPageId)
        when (val target = link.target) {
            is LinkTarget.CurrentNotebookPage -> {
                val idx = pages.indexOfFirst { it.id == target.pageId }
                when {
                    idx < 0 -> toast("Linked page is unavailable.")
                    idx == currentPageIndex -> { /* already here — nothing to do */ }
                    else -> {
                        LinkBackStack.push(this, origin)
                        navigateToPage(idx)
                    }
                }
            }
            is LinkTarget.OtherNotebook ->
                openLinkedNotebook(target.notebookId, null, origin)
            is LinkTarget.OtherNotebookPage ->
                openLinkedNotebook(target.notebookId, target.pageId, origin)
        }
    }

    /**
     * Swipe-up handler: pop the newest [LinkBackStack] entry and navigate back to it. Empty stack →
     * no-op [A3]. Same notebook → [navigateToPage]; cross-notebook → [openLinkedNotebook] with no
     * new push (we're walking *back*, not following forward).
     */
    private fun followBack() {
        val entry = LinkBackStack.pop(this) ?: return
        if (entry.notebookId == notebookId) {
            val idx = pages.indexOfFirst { it.id == entry.pageId }
            if (idx >= 0) navigateToPage(idx) else toast("Linked page is unavailable.")
        } else {
            openLinkedNotebook(entry.notebookId, entry.pageId, origin = null)
        }
    }

    /**
     * App-level switch to another notebook for a link follow / back-swipe (mirrors
     * [switchToRecentNotebook]): resolve the target, optionally push [origin], persist the
     * return-to-folder state, seal the current notebook, then launch the target.
     *
     * The launch carries [EXTRA_VIA_LINK] so the target's [onCreate] **preserves** the back-stack
     * (a link navigation, not a fresh open), and [EXTRA_INITIAL_PAGE_ID] when a specific page is
     * requested. A missing/deleted target notebook toasts and aborts (no push, no nav).
     */
    private fun openLinkedNotebook(targetId: String, pageId: String?, origin: BackEntry?) {
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) { indexRepo.getNotebook(targetId) }
            if (entity == null || entity.deletedAt != null || entity.type != ObjectType.NOTEBOOK) {
                toast("Linked notebook is unavailable.")
                return@launch
            }
            if (origin != null) LinkBackStack.push(this@NotebookActivity, origin)

            // Return-to-folder: closing the opened notebook should land in *its* folder.
            AppStateManager.save(this@NotebookActivity, AppViewState(entity.parentId, false))

            closeNotebook()
            // Clean pen-pipeline handoff before the linked notebook opens (the ownership guard still
            // covers the late onDestroy close as a safety net).
            drawingView.releaseForHandoff()
            startActivity(
                Intent(this@NotebookActivity, NotebookActivity::class.java).apply {
                    putExtra(EXTRA_NOTEBOOK_ID,   entity.id)
                    putExtra(EXTRA_NOTEBOOK_NAME, entity.name)
                    putExtra(EXTRA_VIA_LINK, true)
                    if (pageId != null) putExtra(EXTRA_INITIAL_PAGE_ID, pageId)
                }
            )
            finish()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Heavy file-seal: cache the closing page as the notebook's library-grid cover, flush any new
     * strokes, run housekeeping PRAGMAs, compact, close the Room database, and delete the stray
     * -journal Android leaves during DB init. All IO — runs on [Dispatchers.IO] regardless of the
     * calling dispatcher.
     */
    private suspend fun sealNotebook(
        db: SoilDatabase,
        snapshot: String?,
        strokesAtClose: List<LiveStroke>,
        pageId: String,
        nbPath: String?,
        nbId: String,
        sessionStart: Long,
    ) = withContext(Dispatchers.IO) {
        // Keep the index cover current so the MainActivity grid doesn't need to open the .soil: push
        // the current page's snapshot (captured on the main thread in closeNotebook) as the cover.
        // cacheSnapshotIfAllowed suppresses only private (NOTEBOOK-scope) notebooks.
        // Every step below is individually guarded: this runs fire-and-forget on appScope after
        // the activity is gone, so a throw (disk full, index sealed underneath) has no UI to land
        // in and would take down the whole process — and skip the checkpoint/close that follow.
        if (nbId.isNotEmpty() && snapshot != null) {
            runCatching { cacheSnapshotIfAllowed(nbId, snapshot) }
                .onFailure { android.util.Log.e(TAG, "seal: cover cache failed", it) }
        }
        runCatching { saveStrokes(db, strokesAtClose) }
            .onFailure { android.util.Log.e(TAG, "seal: stroke flush failed — unsaved ink lost", it) }
        // A document editor may still be in front of this notebook, holding text that only this
        // connection can store. Write it before the connection goes away.
        flushPendingDocument(db)
        // RTR: stop pending debounce jobs and flush the sealed page's text synchronously, on this
        // IO context, so it completes before the DB is closed below (the "run once at page-seal" hook).
        rtrScheduler?.let { scheduler ->
            scheduler.cancelAll()
            val hwr = com.notesprout.android.recognition.HandwritingRecognizerProvider.instance
                ?.takeIf { it.isReady() }
            if (hwr != null && pageId.isNotEmpty()) runCatching {
                com.notesprout.android.recognition.PageTextRepository.recognizeAndCache(
                    db.notebookDao(), pageId, com.notesprout.android.recognition.PageTextRecognizer(hwr)
                )
            }.onFailure { Slog.d(TAG) { "RTR seal flush failed: ${it.message}" } }
        }
        runCatching {
            NotebookMetaStore.refresh(db, indexRepo, nbId)
        }.onFailure { Slog.d(TAG) { "meta refresh on seal failed: ${it.message}" } }
        // Log an EDITED activity event if any content row was modified this session. Must run
        // BEFORE the .soil is closed below (queries the just-sealed DB). Soft-deleted rows carry
        // updatedAt = deletedAt, so erases count as edits too.
        if (nbId.isNotEmpty()) runCatching {
            if (db.notebookDao().countContentModifiedSince(sessionStart) > 0) {
                dayHistoryRepo.logEdited(nbId)
            }
        }.onFailure { Slog.d(TAG) { "edited-activity log failed: ${it.message}" } }
        // Hard-delete rows soft-deleted before this session — they predate the undo stack
        // and can never be restored, so they are dead weight. Current-session soft-deletes
        // (deletedAt >= sessionStart) are kept for undo/redo safety on abnormal teardown.
        runCatching { db.notebookDao().hardDeleteOldSoftDeleted(before = sessionStart) }
            .onFailure { android.util.Log.e(TAG, "seal: soft-delete sweep failed", it) }
        // One-time transitional compaction: strip dead per-point ts, re-encode template images to
        // WEBP q100, strip dead heading/text strokes, strip legacy per-page snapshots, and delete
        // legacy custom-cover rows — then VACUUM to reclaim the space. Runs on every seal (including
        // encrypted notebooks, whose key is only available while open), but self-limiting: once a
        // notebook is converted the scans find nothing. touchNotebook() below flags the shrunk file
        // for backup.
        runCatching {
            val r = NotebookCompactor.compact(db, resources.displayMetrics.density)
            if (r.changed) Slog.d(TAG) {
                "compaction sealed: ${r.tsRows} ts + ${r.imageRows} images→WEBP + ${r.deadStrokeRows} dead-stroke + " +
                    "${r.snapshotRows} snapshots stripped + ${r.coverRows} cover rows deleted + " +
                    "${r.compositeRows} composites→child-rows + ${r.orphanRows} orphan sweeps + " +
                    "${r.structuralRows} structural→columnar + ${r.orphanSubtreeRows} orphan subtrees + VACUUM"
            }
        }.onFailure { Slog.d(TAG) { "compaction failed: ${it.message}" } }
        runCatching {
            db.openHelper.writableDatabase.apply {
                query("PRAGMA incremental_vacuum").use { it.moveToFirst() }
                query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
        }.onFailure { android.util.Log.e(TAG, "seal: checkpoint failed", it) }
        // Guarded clear: this seal runs async on appScope and can finish AFTER the user has
        // already opened another encrypted notebook (compaction + VACUUM take seconds on big
        // files). KeySession is a single process-global slot — an unconditional clear here wipes
        // the NEW notebook's cached key. Same idiom as MainActivity's delete path.
        if (KeySession.entry?.notebookId == nbId) KeySession.clear()
        runCatching { db.close() }
            .onFailure { android.util.Log.e(TAG, "seal: close failed", it) }
        if (nbPath != null) runCatching { File("$nbPath-journal").takeIf { it.exists() }?.delete() }
        // Update the index updatedAt so sort-by-date stays correct in MainActivity.
        if (nbId.isNotEmpty()) runCatching { indexRepo.touchNotebook(nbId) }
        // Bump this notebook's recents timestamp to close-time (newest-first ordering).
        // applicationContext — the Activity may be finishing while this seal runs on appScope.
        if (nbId.isNotEmpty()) runCatching { RecentsManager.recordClose(applicationContext, nbId) }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Launch a coroutine that loads the current page and hands it to the drawing view.
     * Also restores the last-opened page position on first call (notebook open).
     *
     * Fast path: if the page's stored snapshot is valid (no strokes changed since it was
     * captured), the composite bitmap is decoded and displayed immediately — stroke
     * deserialization happens in the background ([postDisplayWork]).
     *
     * Full path: strokes are deserialized, a render bitmap is built off-thread, then
     * displayed.  A snapshot is captured and persisted after display so the NEXT load
     * can use the fast path.
     */
    /**
     * A `.soil` this build cannot open: report it to the library and step out, rather than letting the
     * exception kill the process. See [NotebookOpenFailure] for why this is caught broadly and why
     * returning to the library is what breaks the relaunch loop. Main thread.
     */
    /** Survives [recreate] via the Intent — one fix attempt per notebook launch, never a loop. */
    private var openFixAttempted: Boolean
        get() = intent.getBooleanExtra(EXTRA_RECOVERY_ATTEMPTED, false)
        set(value) { intent.putExtra(EXTRA_RECOVERY_ATTEMPTED, value) }

    /**
     * A notebook that won't open is not necessarily lost. Two very different failures land here and
     * must be handled differently:
     *
     *  - **Schema drift** — the file decrypts fine but Room rejects its schema. The common cause is a
     *    `.soil` bricked by the old `sqlcipher_export` user-version loss (version 0 + old schema →
     *    "Pre-packaged database has an invalid schema"). [SoilMigrator.repairMissingUserVersion] fixes
     *    that silently with the key we already have, and we retry. Recovery must NOT fire here — the
     *    key is fine; prompting for a passphrase would be wrong and futile.
     *  - **Key / decryption failure** — the passphrase the app knows is wrong for the file (restored
     *    from an older backup, re-keyed elsewhere). Only then do we offer [NotebookRecovery].
     *
     * Anything else, or a fix that doesn't apply, falls back to the library.
     */
    private fun failOpen(e: Throwable) {
        Log.e(TAG, "notebook open failed for $notebookId", e)
        runCatching { soilDatabase?.close() }
        soilDatabase = null
        binding.openingOverlay.visibility = View.GONE

        if (!encryptionInfo.encrypted || openFixAttempted) {
            bailToLibrary(e)
            return
        }
        openFixAttempted = true
        lifecycleScope.launch {
            val key = soilKey ?: KeySession.getFor(notebookId)

            // 1. Silent version-stamp repair for the sqlcipher_export brick — uses the key we have.
            if (key != null) {
                val repaired = runCatching {
                    withContext(Dispatchers.IO) {
                        com.notesprout.android.crypto.SoilMigrator.repairMissingUserVersion(
                            java.io.File(notebookSoilPath ?: return@withContext null), key
                        )
                    }
                }.getOrNull()
                if (repaired != null) {
                    Log.e(TAG, "notebook $notebookId repaired (stamped user_version=$repaired) — retrying")
                    recreate()
                    return@launch
                }
            }

            // 2. Genuine key/decryption failure → offer passphrase recovery. Schema errors are NOT
            //    key failures and must not reach here.
            if (isKeyFailure(e)) {
                val outcome = runCatching {
                    com.notesprout.android.crypto.NotebookRecovery.offer(
                        this@NotebookActivity, notebookId, notebookDisplayName, encryptionInfo
                    )
                }.getOrDefault(com.notesprout.android.crypto.NotebookRecovery.Outcome.DECLINED)
                if (outcome == com.notesprout.android.crypto.NotebookRecovery.Outcome.RETRY) {
                    recreate()
                    return@launch
                }
            }

            // 3. Unrepairable (schema drift we can't fix, or some other error) → library.
            bailToLibrary(e)
        }
    }

    /**
     * True when [e] indicates the file couldn't be decrypted/read (wrong key, corruption), as opposed
     * to a schema/migration mismatch. Only the former warrants a passphrase-recovery prompt.
     */
    private fun isKeyFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is android.database.sqlite.SQLiteDatabaseCorruptException) return true
            if (t is com.notesprout.android.crypto.SoilLockedException) return true
            val m = t.message?.lowercase().orEmpty()
            if ("file is not a database" in m || "not a database" in m ||
                "file is encrypted" in m || "corrupt" in m) return true
            // Schema/migration problems are explicitly NOT key failures.
            if (t is IllegalStateException &&
                ("invalid schema" in m || "migration" in m || "identity" in m)) return false
            t = t.cause
        }
        return false
    }

    /** Original fail-back: hand the reason to the library and finish. See [failOpen]. */
    private fun bailToLibrary(e: Throwable) {
        NotebookOpenFailure.set(
            NotebookOpenFailure.from(
                notebookName = notebookDisplayName,
                notebookId   = notebookId,
                soilPath     = notebookSoilPath,
                encrypted    = encryptionInfo.encrypted,
                keyScope     = encryptionInfo.keyScope?.name ?: "unknown",
                error        = e,
            )
        )
        finish()
    }

    private fun loadStrokes() {
        val db = soilDatabase ?: return
        // A followed other-notebook *page* link requests a specific opening page. Consume it so an
        // activity recreation (config change) falls back to the normal last-opened-page restore.
        val initialPageId = intent.getStringExtra(EXTRA_INITIAL_PAGE_ID)
        intent.removeExtra(EXTRA_INITIAL_PAGE_ID)
        var linkedPageMissing = false
        var savedUndoJson: String? = null
        lifecycleScope.launch {
            // Room opens the file lazily, so an unopenable .soil surfaces on the *first query* below —
            // not at build(). This is the catch that actually fires for schema drift; the one around
            // the builder covers failures that land earlier. See [failOpen].
            val result = try { withContext(Dispatchers.IO) {
                notebookMetadata = loadNotebookMetadataFromDb(db)
                val allPages = db.notebookDao().getPagesSorted()
                // Open to the linked page when requested; if it's gone, fall back to last-opened
                // and flag a toast. Otherwise restore the last-opened page position (first open only).
                val linkedIdx = initialPageId?.let { pid -> allPages.indexOfFirst { it.id == pid } }
                when {
                    linkedIdx != null && linkedIdx >= 0 -> currentPageIndex = linkedIdx
                    else -> {
                        if (initialPageId != null) linkedPageMissing = true
                        val lastPage = notebookMetadata?.lastOpenedPage
                        if (lastPage != null) {
                            val idx = allPages.indexOfFirst { it.id == lastPage }
                            if (idx >= 0) currentPageIndex = idx
                            // If idx == -1 (page deleted), currentPageIndex = 0 is the safe fallback.
                        }
                    }
                }
                // Read persisted undo/redo state for encrypted notebooks (P2.S3).
                // Plaintext notebooks use the *.undoredo sidecar — see onStart/onStop.
                if (encryptionInfo.encrypted) {
                    savedUndoJson = try {
                        db.openHelper.readableDatabase
                            .query("SELECT json FROM undo_redo_state WHERE id = 0")
                            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadStrokes: failed to read undo/redo state", e)
                        null
                    }
                }
                loadCurrentPage(db)
            } } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failOpen(e)
                return@launch
            }
            displayPage(result)
            binding.openingOverlay.visibility = View.GONE
            if (savedUndoJson != null) {
                try {
                    undoRedoManager = UndoRedoManager.fromJson(savedUndoJson!!)
                    updateUndoRedoButtons()
                } catch (e: Exception) {
                    Log.e(TAG, "loadStrokes: failed to restore undo/redo state", e)
                }
            }
            updatePageIndicator()
            postDisplayWork(db, result)
            syncRtrScheduler()
            if (linkedPageMissing) toast("Linked page is unavailable.")

            // Calendar "Send to Notebook → other/from-main": paste the handed-off content once the
            // initial page is loaded and the canvas is laid out.
            if (intent.getBooleanExtra(EXTRA_PASTE_PENDING, false)) {
                intent.removeExtra(EXTRA_PASTE_PENDING)
                val pending = CalendarTransfer.pending
                CalendarTransfer.pending = null
                if (pending != null) binding.drawingContainer.post { performScratchpadTransfer(pending) }
            }
        }
    }

    /**
     * Refresh [pages], [currentPageId], and [currentLayerId] from the database using
     * [currentPageIndex] as the target.  Does NOT load or deserialize stroke data.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun setupPageIds(db: SoilDatabase) {
        val dao = db.notebookDao()
        pages = dao.getPagesSorted().toMutableList()
        if (pages.isEmpty()) {
            Log.w(TAG, "setupPageIds: no pages in notebook")
            currentPageId = ""; currentLayerId = ""; return
        }
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        currentPageId = page.id
        Slog.d(TAG) { "setupPageIds: page $currentPageId (${currentPageIndex + 1}/${pages.size})" }
        val layer = dao.getLayerForPage(currentPageId)
        if (layer == null) {
            Log.w(TAG, "setupPageIds: no layer for page $currentPageId")
            currentLayerId = ""; return
        }
        currentLayerId = layer.id
        Slog.d(TAG) { "setupPageIds: layerId=$currentLayerId" }
    }

    /**
     * Deserialize stroke rows for [currentLayerId] and return them as [LiveStroke]s.
     * Also repopulates [persistedStrokeIds].
     * Must be called on [Dispatchers.IO] AFTER [setupPageIds] has set [currentLayerId].
     */
    private fun parseStrokeRow(obj: NotebookObject): LiveStroke? =
        LiveStroke.fromRow(obj) ?: run {
            Log.e(TAG, "deserializeStrokesFromDb: failed to parse stroke ${obj.id}")
            null
        }

    /**
     * Parse every stroke row for [layerId] into [LiveStroke]s. Pure — no view/[persistedStrokeIds]
     * side effects, so it is safe to call for the current page OR a prefetched neighbour. The JSON
     * parse is CPU-bound and independent per stroke (the dominant page-load cost on dense pages), so
     * it fans out across cores for big pages; chunk order is preserved (chunked → awaitAll → flatten)
     * so stroke z-order is unchanged. Small pages parse inline to avoid dispatch overhead.
     */
    private suspend fun parseStrokesForLayer(db: SoilDatabase, layerId: String): List<LiveStroke> = coroutineScope {
        val strokeObjects = db.notebookDao().getStrokesForLayer(layerId)
        if (strokeObjects.size < PARALLEL_PARSE_THRESHOLD) {
            strokeObjects.mapNotNull { parseStrokeRow(it) }
        } else {
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val chunkSize = (strokeObjects.size + cores - 1) / cores
            strokeObjects.chunked(chunkSize)
                .map { chunk -> async(Dispatchers.Default) { chunk.mapNotNull { parseStrokeRow(it) } } }
                .awaitAll()
                .flatten()
        }
    }

    /**
     * Load the current page's strokes, using the prefetch cache when valid. The cache is keyed by
     * pageId and validated inline against [getMaxContentUpdatedAt] — a ~10ms query — so a hit is
     * always fresh (any edit bumps the version → miss → re-parse). Neighbour pages are populated by
     * [prefetchNeighbors], making sequential turns validated cache hits that skip the ~260ms parse.
     */
    private suspend fun deserializeStrokesFromDb(db: SoilDatabase): List<LiveStroke> {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return emptyList()
        val pageId  = currentPageId
        val version = db.notebookDao().getMaxContentUpdatedAt(layerId) ?: 0L
        val cached  = synchronized(strokeCacheLock) { strokeCache[pageId] }
        val result: List<LiveStroke>
        if (cached != null && cached.version == version) {
            result = cached.strokes
        } else {
            result = parseStrokesForLayer(db, layerId)
            synchronized(strokeCacheLock) { strokeCache[pageId] = CachedStrokes(result, version) }
        }
        persistedStrokeIds.clear()
        persistedStrokeIds.addAll(result.map { it.id })
        return result
    }

    /**
     * After a page displays, parse the immediate neighbours (N-1, N+1) into [strokeCache] so the next
     * sequential turn is a validated cache hit. A cheap version check skips already-cached neighbours;
     * the job is cancelled when navigation moves on. Call on the main thread.
     */
    private fun prefetchNeighbors() {
        val db = soilDatabase ?: return
        val ids = listOf(currentPageIndex - 1, currentPageIndex + 1)
            .filter { it in pages.indices }
            .map { pages[it].id }
        if (ids.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            for (pageId in ids) {
                val layer   = db.notebookDao().getLayerForPage(pageId) ?: continue
                val version = db.notebookDao().getMaxContentUpdatedAt(layer.id) ?: 0L
                val fresh   = synchronized(strokeCacheLock) { strokeCache[pageId]?.version == version }
                if (fresh) continue
                val parsed = parseStrokesForLayer(db, layer.id)
                synchronized(strokeCacheLock) { strokeCache[pageId] = CachedStrokes(parsed, version) }
            }
        }
    }

    /**
     * Try to build a composite display bitmap (white → template → snapshot PNG) for the
     * current page.  Returns null if the snapshot is absent, stale, or the view isn't
     * laid out yet — callers fall through to the full render path.
     * Must be called on [Dispatchers.IO] AFTER [setupPageIds].
     */
    /**
     * Load the current page. Calls [setupPageIds] to resolve the current page/layer IDs from
     * [currentPageIndex], then deserializes strokes + objects. The GPU RenderNode committed layer
     * records directly from these lists in [displayPage] → `loadStrokesWithBitmap`; no bitmap is
     * rasterized here. Must be called on [Dispatchers.IO].
     */
    private suspend fun loadCurrentPage(db: SoilDatabase): PageLoadResult {
        setupPageIds(db)
        val templateBitmap  = loadPageTemplateFromDb(db)
        val headings        = loadHeadingsFromDb(db, currentLayerId)
        val textObjects     = loadTextObjectsFromDb(db, currentLayerId)
        val lineObjects     = loadLineObjectsFromDb(db, currentLayerId)
        val links           = loadLinksFromDb(db, currentLayerId)
        val stickyNotes     = loadStickyNotesFromDb(db, currentLayerId)
        val shapeObjects    = loadShapeObjectsFromDb(db, currentLayerId)
        val strokes         = deserializeStrokesFromDb(db)
        return PageLoadResult(strokes, templateBitmap, headings = headings, textObjects = textObjects, lineObjects = lineObjects, links = links, stickyNotes = stickyNotes, shapeObjects = shapeObjects)
    }

    /**
     * Load heading rows for [layerId] from [db] and convert them to render-time [HeadingStroke]s.
     * Rows with missing or malformed `boundingBox` or `data` are silently skipped.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadHeadingsFromDb(db: SoilDatabase, layerId: String): List<HeadingStroke> {
        if (layerId.isEmpty()) return emptyList()
        return db.notebookDao().loadHeadingsSubtree(layerId)
    }

    private suspend fun loadTextObjectsFromDb(db: SoilDatabase, layerId: String): List<TextRender> {
        if (layerId.isEmpty()) return emptyList()
        return db.notebookDao().loadTextsSubtree(layerId)
    }

    private suspend fun loadLineObjectsFromDb(db: SoilDatabase, layerId: String): List<LineRender> {
        if (layerId.isEmpty()) return emptyList()
        val density = resources.displayMetrics.density
        return db.notebookDao().getLineObjectsForLayer(layerId).mapNotNull { it.toLineRender(density) }
    }

    /**
     * Load link rows for [layerId] and convert them to render-time [LinkRender]s.
     * The embedded held objects (strokes/headings/text) reuse the render models directly; embedded
     * lines are inflated from [EmbeddedLine] (dp) into [LineRender] (px) for the current density.
     * Rows with a missing/malformed `boundingBox` or `data` are silently skipped.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun loadLinksFromDb(db: SoilDatabase, layerId: String): List<LinkRender> {
        if (layerId.isEmpty()) return emptyList()
        val density = resources.displayMetrics.density
        return db.notebookDao().loadLinksSubtree(layerId, density)
    }

    private suspend fun loadStickyNotesFromDb(db: SoilDatabase, layerId: String): List<StickyNoteRender> {
        if (layerId.isEmpty()) return emptyList()
        val density = resources.displayMetrics.density
        // The sticky editor now persists its content directly to this .soil in real-time (P2-12),
        // so a page load simply reflects whatever the editor already wrote — even after a kill.
        return db.notebookDao().loadStickyNotesSubtree(layerId, density)
    }

    private suspend fun loadShapeObjectsFromDb(db: SoilDatabase, layerId: String): List<com.notesprout.android.data.ShapeRender> {
        if (layerId.isEmpty()) return emptyList()
        val density = resources.displayMetrics.density
        return db.notebookDao().getShapeObjectsForLayer(layerId).mapNotNull { it.toShapeRender(density) }
    }

    private fun NotebookObject.parseBoundingBox(): android.graphics.RectF? =
        com.notesprout.android.data.parseBoundingBox(boundingBox)

    /**
     * Apply a [PageLoadResult] to the drawing view on the main thread.
     * Also keeps [currentTemplateBitmap] in sync for the undo/redo optimised stroke path.
     */
    private fun displayPage(result: PageLoadResult) {
        // A page flip is a context change — a popover anchored to the old view state shouldn't ride
        // along. (The armed ink itself persists; only the panel closes.)
        hidePenColorPanel()
        currentTemplateBitmap = result.templateBitmap
        drawingView.loadHeadings(result.headings)
        drawingView.loadTextObjects(result.textObjects)
        drawingView.loadLineObjects(result.lineObjects)
        drawingView.loadShapeObjects(result.shapeObjects)
        drawingView.loadLinks(result.links)
        drawingView.loadStickyNotes(result.stickyNotes)
        // Preserve any ink the user wrote during the eraseAll → loadCurrentPage window.
        // eraseAll() re-enables EPD drawing asynchronously (post { post { setRawDrawingEnabled }}),
        // so a fast writer can lay down strokes before the new page is swapped in. Those strokes
        // carry fresh UUIDs not yet in persistedStrokeIds; merging them here stops loadStrokes /
        // loadStrokesWithBitmap from silently discarding them (the cross-notebook-nav data-loss bug).
        val earlyStrokes = drawingView.getStrokes().filterNot { it.id in persistedStrokeIds }
        val mergedStrokes = if (earlyStrokes.isEmpty()) result.strokes else result.strokes + earlyStrokes

        // GPU path: loadStrokesWithBitmap records the committed RenderNode from the in-memory lists
        // (set above) + mergedStrokes (which folds in any early ink), and does ONE EPD refresh.
        drawingView.loadStrokesWithBitmap(mergedStrokes, null, result.templateBitmap)

        // Warm the cache for the neighbouring pages so the next sequential turn is a validated hit.
        prefetchNeighbors()
    }

    /**
     * Work to run after [displayPage] returns. In the GPU render path there is nothing to do:
     * [loadCurrentPage] already deserialized the strokes, [displayPage] recorded the committed
     * RenderNode from them, and no per-page snapshot is captured on arrival (the library-grid cover
     * is captured only at close). Retained as a call-site hook for the navigation paths.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun postDisplayWork(db: SoilDatabase, result: PageLoadResult) {
        // No-op: GPU committed-content render needs no post-display work.
    }

    /**
     * Read the current page's `template` property, look up the template row, and decode
     * its base64 image to a Bitmap.  Returns null if the page has no template (blank).
     *
     * Must be called on [Dispatchers.IO]. Uses [currentPageId] set by [loadStrokesFromDb].
     */
    private suspend fun loadPageTemplateFromDb(db: SoilDatabase): Bitmap? {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return null

        val page = db.notebookDao().getObjectById(pageId) ?: return null

        val templateId = page.pageData().template.takeIf { it.isNotEmpty() } ?: return null

        val templateObj = db.notebookDao().getTemplateById(templateId) ?: return null

        return try {
            val b64 = templateObj.templateDataOrNull()?.image?.takeIf { it.isNotEmpty() } ?: return null
            val bytes = Base64.decode(b64, android.util.Base64.DEFAULT)
            // Bounded decode (M-1): cap to the view size (fall back to MAX_DIMENSION before layout).
            val view = drawingView.asView()
            val reqW = view.width.takeIf  { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            val reqH = view.height.takeIf { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            BitmapDecode.decodeSampled(bytes, reqW, reqH)
        } catch (e: Exception) {
            Log.e(TAG, "loadPageTemplateFromDb: failed to decode template bitmap", e)
            null
        }
    }

    /**
     * Incrementally persist in-memory strokes to the database using INSERT OR IGNORE.
     *
     * Strokes already in the DB (same UUID) are silently skipped — no full delete +
     * re-insert cycle.  Erased strokes are removed from DB via the [DrawingView.onStrokeErased]
     * callback; this function only adds new ones.
     *
     * Returns the list of stroke IDs that were newly inserted in this call (i.e. not
     * previously in [persistedStrokeIds]).  The caller uses this to push [UndoRedoAction.StrokeAdded]
     * for each new stroke.  Returns an empty list when no new strokes were saved.
     *
     * Called from: page navigation (before switch), [closeNotebook], [onPenLifted].
     * Must be called on [Dispatchers.IO].
     */
    /**
     * Rebuild the render bitmap off-thread after an eraser-tool object deletion and swap it in,
     * so the EPD panel reflects the erased heading / text object / line. Shared by the
     * [DrawingView.onHeadingErased], [DrawingView.onTextErased], and [DrawingView.onLineErased]
     * callbacks. Must be called on the main thread; the view's in-memory object lists have
     * already had the erased object removed before this runs.
     */
    private suspend fun rebuildBitmapAfterErase() {
        val strokes = drawingView.getStrokes()
        val templateBmp = currentTemplateBitmap
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, templateBmp, drawingView.getHeadings())
        }
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
        } else {
            drawingView.loadStrokes(strokes)
        }
    }

    private suspend fun saveStrokes(db: SoilDatabase, strokesSnapshot: List<LiveStroke>? = null): List<String> {
        val layerId = currentLayerId
        if (layerId.isEmpty()) {
            Log.w(TAG, "saveStrokes: currentLayerId is empty — skipping")
            return emptyList()
        }
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        // Snapshot on the main thread: the view's stroke list is a plain ArrayList mutated by the
        // UI thread (renderStroke / erase / drag-commit), and this function runs on IO — copying
        // it from here races those mutations (CME / torn copy on the core ink-save path).
        // [strokesSnapshot] bypasses the hop for the seal path, whose blocking onDestroy variant
        // runs under runBlocking on the main thread (a Main hop there would deadlock).
        val currentStrokes = strokesSnapshot ?: withContext(Dispatchers.Main) { drawingView.getStrokes() }

        // Fix #2a — skip serialization for strokes already committed to the DB.
        // Take a snapshot of persistedStrokeIds before the transaction so the filter
        // is stable even if the main thread modifies the set during IO (edge case).
        val alreadyPersisted = persistedStrokeIds.toHashSet()
        val newStrokes = currentStrokes.filter { it.id !in alreadyPersisted }

        Slog.d(TAG) { "saveStrokes: ${newStrokes.size} new / ${currentStrokes.size} total strokes" }

        if (newStrokes.isEmpty()) {
            // Nothing to write — return immediately without touching the DB at all.
            return emptyList()
        }

        // Pre-build a stable index map so each new stroke keeps its global draw-order
        // position even though we iterate only the subset.
        val strokeIndexMap = currentStrokes.withIndex().associate { (i, s) -> s.id to i }

        // Single transaction — all new inserts share one BEGIN/COMMIT. Track what was actually
        // written: degenerate (<2-point) strokes are skipped, and reporting their ids as saved
        // used to create StrokeAdded undo entries for rows that don't exist and permanently
        // block those in-memory strokes from ever saving.
        val savedIds = ArrayList<String>(newStrokes.size)
        db.withTransaction {
            for (liveStroke in newStrokes) {
                if (liveStroke.points.size < 2) continue   // degenerate stroke — skip
                dao.insertOrIgnore(
                    liveStroke.toStrokeRow(
                        parentId  = layerId,
                        order     = strokeIndexMap[liveStroke.id] ?: 0,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                savedIds.add(liveStroke.id)
            }
        }

        // Extend the registry so subsequent saves skip these IDs immediately.
        val newIds: List<String> = savedIds
        persistedStrokeIds.addAll(newIds)

        // RTR: new ink landed — schedule a debounced re-recognition of this page.
        rtrScheduler?.noteEdit(currentPageId)
        return newIds
    }

    // ── Real-time recognition (RTR) ─────────────────────────────────────────────

    /**
     * (Re)build [rtrScheduler] to match the notebook's `rtrEnabled` flag and recognizer readiness.
     * Idempotent — safe to call after open, after page load, and after toggling the setting.
     * When it first switches on, it seeds the cache for any missing/stale page (backfill-on-open).
     */
    private fun syncRtrScheduler() {
        val enabled = notebookMetadata?.rtrEnabled == true
        val hwr = com.notesprout.android.recognition.HandwritingRecognizerProvider.instance
            ?.takeIf { it.isReady() }
        if (enabled && hwr != null) {
            if (rtrScheduler == null) {
                rtrScheduler = com.notesprout.android.recognition.RtrScheduler(
                    scope = NotesproutApplication.appScope,
                    recognizer = com.notesprout.android.recognition.PageTextRecognizer(hwr),
                    dbProvider = { soilDatabase },
                )
                rtrBackfillInBackground()
            }
        } else {
            rtrScheduler?.cancelAll()
            rtrScheduler = null
        }
    }

    /** Backfill every missing/stale page's text without UI (silent seed on open / enable). */
    private fun rtrBackfillInBackground() {
        val scheduler = rtrScheduler ?: return
        val db = soilDatabase ?: return
        NotesproutApplication.appScope.launch {
            val pageIds = runCatching { db.notebookDao().getPagesSorted().map { it.id } }
                .getOrNull() ?: return@launch
            scheduler.backfill(pageIds)
        }
    }


    // ── Template operations ───────────────────────────────────────────────────

    // ── Page index ────────────────────────────────────────────────────────────

    /**
     * Saves the current page's strokes, then launches [PageIndexActivity] (which renders its page
     * thumbnails on demand from the freshly-saved content).
     */
    private fun openPageIndex() {
        val db = soilDatabase ?: return
        if (notebookId.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            val i = Intent(this@NotebookActivity, PageIndexActivity::class.java).apply {
                putExtra(PageIndexActivity.EXTRA_NOTEBOOK_ID,           notebookId)
                putExtra(PageIndexActivity.EXTRA_NOTEBOOK_NAME,         notebookDisplayName)
                putExtra(PageIndexActivity.EXTRA_CURRENT_PAGE_INDEX,    currentPageIndex)
            }
            pageIndexLauncher.launch(i)
        }
    }

    // ── Copy / paste ──────────────────────────────────────────────────────────

    /** Copies the current page to the in-memory clipboard. Tapping again clears it (toggle). */
    private fun copyCurrentPage() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        pendingCopyPageId = if (pendingCopyPageId == pageId) null else pageId
    }

    /** Pastes the clipboard page immediately after the current page, then navigates to it. */
    private fun pasteCopiedPage() {
        val db     = soilDatabase ?: return
        val copyId = pendingCopyPageId ?: return
        lifecycleScope.launch {
            var newPageId: String? = null
            withContext(Dispatchers.IO) {
                saveStrokes(db)
                newPageId = copyPageAfter(copyId, currentPageId, db)
                if (newPageId != null) {
                    pages = db.notebookDao().getPagesSorted().toMutableList()
                    val idx = pages.indexOfFirst { it.id == newPageId }
                    if (idx >= 0) {
                        currentPageIndex = idx
                        currentPageId    = pages[idx].id
                        currentLayerId   = db.notebookDao().getLayerForPage(currentPageId)?.id ?: ""
                    }
                }
            }
            if (newPageId == null) return@launch
            pendingCopyPageId = null
            undoRedoManager.push(UndoRedoAction.PagePasted(currentPageId, currentPageIndex))
            updateUndoRedoButtons()
            drawingView.clearForPageLoad()
            val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
            displayPage(result)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            postDisplayWork(db, result)
        }
    }

    // ── Page long-press menu ────────────────────────────────────────────────────
    //
    // The page-related actions that used to be toolbar buttons (template, page index, insert
    // before/after, copy, paste, erase, delete) live here, opened by a finger long-press on the
    // canvas via [handlePageMenuLongPress]. Paste is offered only when a page has been copied
    // (mirrors the old disabled-button state — an unavailable action is simply absent on e-ink,
    // never a silently-inert row). Actions call the same underlying methods the buttons did.

    private fun showPageMenu() {
        // A page must be open for these actions to make sense.
        if (currentPageId.isEmpty()) return
        drawingView.releaseRender()
        val copied = pendingCopyPageId != null
        ActionSheetDialog(this)
            .title("Page")
            // Pin/unpin the notebook (was the btnPin toolbar button) — top of the menu.
            .addAction(
                if (notebookPinned) R.drawable.ic_pinned_off else R.drawable.ic_pinned,
                if (notebookPinned) "Unpin Notebook" else "Pin Notebook",
            ) { toggleNotebookPin() }
            .addAction(R.drawable.ic_template, "Template") { showTemplateDialog() }
            .addAction(R.drawable.ic_files, "Page Index") { openPageIndex() }
            .addAction(R.drawable.ic_insert_page_before, "Insert Page Before") { insertPageBeforeCurrentAndNavigate() }
            .addAction(R.drawable.ic_insert_page_after, "Insert Page After") { insertPageAfterCurrentAndNavigate() }
            .addAction(
                R.drawable.ic_copy_page,
                if (pendingCopyPageId == currentPageId) "Copy Page (copied — tap to clear)" else "Copy Page",
            ) { copyCurrentPage() }
            .apply {
                if (copied) addAction(R.drawable.ic_paste_page, "Paste Page") { pasteCopiedPage() }
            }
            .addAction(R.drawable.ic_erase_all, "Erase Page") { eraseCurrentPage() }
            .addAction(R.drawable.ic_page_delete, "Delete Page") { confirmDeleteCurrentPage() }
            // Text-recognition actions (were the btnTextRecognition popup) sit just above Export.
            .addAction(R.drawable.ic_text_recognition, "View recognized text") {
                openTextViewer(soilDatabase ?: return@addAction)
            }
            .addAction(
                R.drawable.ic_text_recognition,
                if (notebookMetadata?.rtrEnabled == true) "Real-time text: On" else "Real-time text: Off",
            ) { toggleRtr(soilDatabase ?: return@addAction) }
            // Export (was a toolbar button) lives at the bottom of the Page menu now.
            .addAction(R.drawable.ic_export, "Export") { openExportScreen(soilDatabase ?: return@addAction) }
            .show()
    }

    /** Toggle the notebook's pinned state in the global index and update the in-memory flag. */
    private fun toggleNotebookPin() {
        val nbId = notebookId.takeIf { it.isNotEmpty() } ?: return
        lifecycleScope.launch {
            notebookPinned = withContext(Dispatchers.IO) { indexRepo.togglePin(nbId) }
        }
    }

    /** Template picker for the current page (was the `btnTemplate` toolbar button). */
    private fun showTemplateDialog() {
        val db = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        TemplateDialog(
            activity       = this,
            lifecycleScope = lifecycleScope,
            db             = db,
            pageId         = pageId,
            onConfirm      = { templateId, bitmap ->
                applyTemplateToCurrentPage(templateId, bitmap)
            },
            onBrowseLibrary = {
                val intent = Intent(this, TemplateBrowserActivity::class.java)
                    .putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_PICK)
                templatePickLauncher.launch(intent)
            },
        ).show()
    }

    /** Erase all content on the current page, with confirmation (was the `btnEraseAll` button). */
    private fun eraseCurrentPage() {
        val db = soilDatabase
        val dialog = AlertDialog.Builder(this)
            .setMessage("Erase this page?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Erase") { _, _ ->
                // Snapshot the page/layer IDs now — they may change before the coroutine runs.
                val eraseAllPageId      = currentPageId
                val eraseAllLayerId     = currentLayerId
                val eraseAllHeadingIds  = drawingView.getHeadings().map { it.id }
                val eraseAllStickyIds   = drawingView.getStickyNotes().map { it.id }
                val hasContent = drawingView.getStrokes().isNotEmpty() ||
                                 eraseAllHeadingIds.isNotEmpty() ||
                                 drawingView.getTextObjects().isNotEmpty() ||
                                 drawingView.getLineObjects().isNotEmpty() ||
                                 eraseAllStickyIds.isNotEmpty() ||
                                 drawingView.getShapeObjects().isNotEmpty()
                drawingView.eraseAll()
                drawingView.loadHeadings(emptyList())
                // All content removed from memory and will be soft-deleted from DB.
                // Clear the persisted-ID registry; no snapshot needed for a user-initiated erase.
                persistedStrokeIds.clear()
                if (db != null && hasContent) {
                    lifecycleScope.launch {
                        val deletedAt = System.currentTimeMillis()
                        withContext(Dispatchers.IO) {
                            // Soft-delete all layer children (strokes, headings, any future types)
                            // with a shared timestamp so restoreChildrenDeletedSince recovers everything atomically.
                            db.notebookDao().softDeleteByParentId(eraseAllLayerId, deletedAt)
                            // Schedule RTR so the recognized-text cache tracks this erase.
                            noteContentEdit(db, eraseAllPageId)
                        }
                        // Record the erase as a single undoable action after the DB writes succeed.
                        // headingIds stored so undo can restore them explicitly by ID (belt-and-suspenders
                        // alongside restoreChildrenDeletedSince which uses a timestamp filter).
                        undoRedoManager.push(
                            UndoRedoAction.PageEraseAll(eraseAllPageId, eraseAllLayerId, deletedAt, eraseAllHeadingIds, eraseAllStickyIds)
                        )
                        updateUndoRedoButtons()
                    }
                }
            }
            .create()
        dialog.show()
        // Style after show() — window only exists once the dialog is displayed.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    /** Delete the current page, with confirmation (was the `btnDeletePage` toolbar button). */
    private fun confirmDeleteCurrentPage() {
        val db = soilDatabase ?: return
        // Confirmation dialog — flat Notesprout style (elevation=0, shape_bordered).
        val dialog = AlertDialog.Builder(this)
            .setMessage("Delete this page?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                // Capture pageId, pageIndex, and deletedAt timestamp BEFORE the delete runs.
                // All three soft-delete calls (page, layer, strokes) use the same timestamp
                // so PageDeleted.undo can restore exactly those rows via restoreChildrenDeletedSince.
                val deletedPageId    = currentPageId
                val deletedPageIndex = currentPageIndex
                val deletedAt        = System.currentTimeMillis()
                lifecycleScope.launch {
                    // No snapshot needed — the page being deleted is discarded.
                    withContext(Dispatchers.IO) { deletePage(db, deletedAt) }
                    undoRedoManager.push(
                        UndoRedoAction.PageDeleted(deletedPageId, deletedPageIndex, deletedAt)
                    )
                    updateUndoRedoButtons()
                    drawingView.clearForPageLoad()
                    val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
                    displayPage(result)
                    updatePageIndicator()
                    saveLastOpenedPage(currentPageId)
                    postDisplayWork(db, result)
                }
            }
            .create()
        dialog.show()
        // Style after show() — window only exists once the dialog is displayed.
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    // ── Library-grid cover ──────────────────────────────────────────────────

    /**
     * Write [snapshot] (the current page image, captured at close) to the global index as this
     * notebook's library-grid cover. Suppressed only for private (NOTEBOOK-scope) notebooks;
     * GLOBAL-scope covers are safe because the index is itself encrypted under the global key.
     */
    private suspend fun cacheSnapshotIfAllowed(nbId: String, snapshot: String) {
        if (encryptionInfo.encrypted && encryptionInfo.keyScope != KeyScope.GLOBAL) return
        runCatching { indexRepo.updateNotebookSnapshot(nbId, snapshot) }
    }

    /**
     * The "Text" toolbar button: the two recognition actions that used to hang off the Export
     * sheet. Export itself now opens [ExportActivity] directly.
     */
    /**
     * Open the export screen. Strokes are flushed first: [ExportActivity] renders from the `.soil`
     * on disk, so unsaved ink in the live view would silently be missing from the output.
     */
    private fun openExportScreen(db: SoilDatabase) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes(db) }
            startActivity(
                ExportActivity.intentFor(
                    context = this@NotebookActivity,
                    notebookId = notebookId,
                    notebookName = notebookDisplayName,
                    currentPageId = currentPageId.takeIf { it.isNotEmpty() },
                )
            )
        }
    }

    // ── Document editor ───────────────────────────────────────────────────────

    /**
     * Open the current page's document. The page is the draft: on a page that has no document yet,
     * its recognized text flows in once, here, before the editor is launched — which is also why the
     * seed never lands in the DB, only in the editor's buffer. Everything after that is the user's.
     *
     * Ink is flushed first: the draft is recognized from the `.soil`, so strokes still sitting in the
     * live view would be missing from it.
     */
    private fun openDocumentEditor(db: SoilDatabase) {
        if (currentPageId.isEmpty()) return
        val index = currentPageIndex
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes(db) }
            documentPageIndex = index
            documentPageId = pages.getOrNull(index)?.id ?: currentPageId
            val session = loadDocumentSession(db, documentPageId, index, showProgress = true)
            documentSrcUpdatedAt = session.srcUpdatedAt
            DocumentTransfer.host = documentHost
            DocumentTransfer.input = session
            DocumentTransfer.live = null
            documentLauncher.launch(
                DocumentEditorActivity.intent(this@NotebookActivity, notebookDisplayName)
            )
        }
    }

    /**
     * Everything the editor needs to show one page: its document (seeded from the page's recognized
     * text when it has none yet), whether the page has moved on since that text was drafted, and where
     * the page sits in the notebook. Shared by open and page-flip so both behave identically.
     */
    private suspend fun loadDocumentSession(
        db: SoilDatabase,
        pageId: String,
        index: Int,
        showProgress: Boolean,
    ): DocumentTransfer.Session {
        val dao = db.notebookDao()
        var draft = withContext(Dispatchers.IO) {
            DocumentRepository.get(dao, pageId)
        }?.let { DocumentTransfer.Draft(it.text, it.srcUpdatedAt) }

        if (DocumentDraft.isUndrafted(draft?.text)) {
            draft = seedDocumentDraft(db, pageId, showProgress) ?: draft
        }

        val layerMax = withContext(Dispatchers.IO) {
            com.notesprout.android.recognition.PageTextRepository.layerMaxUpdatedAt(dao, pageId)
        }
        val total = pages.size.coerceAtLeast(1)
        return DocumentTransfer.Session(
            text = draft?.text.orEmpty(),
            srcUpdatedAt = draft?.srcUpdatedAt,
            stale = DocumentDraft.isStale(draft?.srcUpdatedAt, layerMax),
            pageLabel = "${index + 1} / $total",
            hasPrev = index > 0,
            hasNext = index < pages.size - 1,
            caret = DocumentPreferences.caret(this, pageId),
        )
    }

    /**
     * Recognize [pageId] and return it as a fresh draft, or null when there is nothing to draft from
     * (no recognizer, or a page with no words on it). On a notebook without real-time text this is a
     * full page recognition, which is not instant.
     *
     * [showProgress] must be false when the editor is in front of us: this activity is stopped then, so
     * a dialog on its window is invisible at best and a bad-token crash at worst. The editor shows its
     * own progress in the source strip for that path.
     */
    private suspend fun seedDocumentDraft(
        db: SoilDatabase,
        pageId: String,
        showProgress: Boolean,
    ): DocumentTransfer.Draft? {
        val hwr = HandwritingRecognizerProvider.instance?.takeIf { it.isReady() } ?: return null

        val dialog = if (!showProgress) null else {
            val tvMessage = android.widget.TextView(this).apply {
                text = "Reading this page…"
                setPadding(64, 48, 64, 48)
                setTextColor(android.graphics.Color.BLACK)
                textSize = 16f
            }
            AlertDialog.Builder(this)
                .setView(tvMessage)
                .setCancelable(false)
                .create()
                .also {
                    it.show()
                    it.window?.setElevation(0f)
                    it.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                }
        }

        return try {
            withContext(Dispatchers.IO) {
                val pageText = com.notesprout.android.recognition.PageTextRepository.freshOrRecognize(
                    db.notebookDao(), pageId, com.notesprout.android.recognition.PageTextRecognizer(hwr),
                )
                pageText.text.takeIf { it.isNotBlank() }
                    ?.let { DocumentTransfer.Draft(it, pageText.sourceMaxUpdatedAt) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to draft page $pageId", e)
            null
        } finally {
            runCatching { dialog?.dismiss() }
        }
    }

    /**
     * What the editor is allowed to ask of the notebook. Both calls are keyed on [documentPageId] —
     * never on `currentPageId`, which a recreated host may have moved on from.
     */
    private val documentHost = object : DocumentTransfer.Host {

        override fun saveDocument(text: String, caret: Int) {
            val db = soilDatabase ?: return
            val pageId = documentPageId.takeIf { it.isNotEmpty() } ?: return
            val src = documentSrcUpdatedAt
            DocumentPreferences.saveCaret(this@NotebookActivity, pageId, caret)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { DocumentRepository.save(db.notebookDao(), pageId, text, src) }
                    .onFailure { Log.e(TAG, "Failed to save document for page $pageId", it) }
            }
        }

        override fun requestPageDraft(onResult: (DocumentTransfer.Draft?) -> Unit) {
            val db = soilDatabase
            val pageId = documentPageId
            if (db == null || pageId.isEmpty()) { onResult(null); return }
            lifecycleScope.launch {
                val draft = seedDocumentDraft(db, pageId, showProgress = false)
                // Re-anchor the document to the page state it was just drafted from. Only ever here
                // and at the seed — this is what makes "page has changed" meaningful.
                if (draft != null) documentSrcUpdatedAt = draft.srcUpdatedAt
                onResult(draft)
            }
        }

        override fun requestPage(delta: Int, onResult: (DocumentTransfer.Session?) -> Unit) {
            val db = soilDatabase
            val target = documentPageIndex + delta
            val pageId = pages.getOrNull(target)?.id
            if (db == null || pageId == null) { onResult(null); return }
            // Switch which page we write to *before* loading, and drop the outgoing page's published
            // text with it: a teardown between here and the editor's next save must not write the page
            // we left onto the page we arrived at. The editor has already stored it.
            DocumentTransfer.live = null
            documentPageIndex = target
            documentPageId = pageId
            documentSrcUpdatedAt = null
            lifecycleScope.launch {
                val session = loadDocumentSession(db, pageId, target, showProgress = false)
                documentSrcUpdatedAt = session.srcUpdatedAt
                DocumentTransfer.input = session
                onResult(session)
            }
        }
    }

    /**
     * Write the text the editor last published, before the connection it needs goes away. Called at
     * the top of every seal so a notebook closing under an open editor still stores what was typed.
     * Must run on [Dispatchers.IO].
     */
    private suspend fun flushPendingDocument(db: SoilDatabase) {
        val text = DocumentTransfer.live ?: return
        val pageId = documentPageId.takeIf { it.isNotEmpty() } ?: return
        DocumentPreferences.saveCaret(this, pageId, DocumentTransfer.liveCaret)
        runCatching { DocumentRepository.save(db.notebookDao(), pageId, text, documentSrcUpdatedAt) }
            .onFailure { Log.e(TAG, "Failed to flush document for page $pageId", it) }
    }

    /** Open the read-only recognized-text viewer for this notebook (flush current ink first). */
    private fun openTextViewer(db: SoilDatabase) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes(db) }
            val i = Intent(this@NotebookActivity, PageTextViewerActivity::class.java).apply {
                putExtra(PageTextViewerActivity.EXTRA_NOTEBOOK_ID, notebookId)
                putExtra(PageTextViewerActivity.EXTRA_NOTEBOOK_NAME, notebookDisplayName)
                putExtra(PageTextViewerActivity.EXTRA_CURRENT_PAGE_ID, currentPageId)
            }
            startActivity(i)
        }
    }

    /**
     * Flip the per-notebook RTR flag. Enabling kicks a progress-tracked backfill of every page;
     * disabling stops scheduling but keeps existing cached text (non-destructive).
     */
    private fun toggleRtr(db: SoilDatabase) {
        val meta = notebookMetadata ?: run { toast("Real-time text unavailable for this notebook."); return }
        val enabling = !meta.rtrEnabled
        if (enabling && com.notesprout.android.recognition.HandwritingRecognizerProvider.instance?.isReady() != true) {
            toast("Handwriting model not ready — connect once to download it.")
            return
        }
        val updated = meta.copy(rtrEnabled = enabling)
        notebookMetadata = updated
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val row = db.notebookDao().getNotebookObject() ?: return@withContext
                db.notebookDao().upsertNotebookObject(updated.writeOnto(row, System.currentTimeMillis()))
            }
            syncRtrScheduler()   // creates the scheduler + kicks a backfill of missing/stale pages
            toast(if (enabling) "Real-time text on — recognizing existing pages…" else "Real-time text off.")
        }
    }

    /**
     * Called when the user confirms a template selection in [TemplateDialog].
     * Updates the current page's `template` property in the DB, then applies the
     * bitmap to the drawing view so the change is immediately visible.
     *
     * [templateId] = "" means "Blank" (clear template).
     * [bitmap] is the full-resolution template Bitmap, or null for Blank.
     */
    private fun applyTemplateToCurrentPage(templateId: String, bitmap: Bitmap?) {
        val db = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        // Apply to drawing view immediately (before DB write so the user sees it at once).
        currentTemplateBitmap = bitmap
        drawingView.setTemplate(bitmap)

        // Persist the page's template property and record the undo action in the background.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Read current (previous) template ID before overwriting — needed for undo.
                val page = db.notebookDao().getObjectById(pageId)
                val previousTemplateId = page
                    ?.let { it.pageData().template }
                    ?.takeIf { it.isNotEmpty() }

                persistPageTemplate(db.notebookDao(), pageId, templateId)

                // Push undo action on the main thread after the DB write completes.
                withContext(Dispatchers.Main) {
                    undoRedoManager.push(
                        UndoRedoAction.TemplateChanged(
                            pageId           = pageId,
                            previousTemplateId = previousTemplateId,
                            newTemplateId    = templateId.takeIf { it.isNotEmpty() },
                        )
                    )
                    updateUndoRedoButtons()
                }
            }
            // TODO: apply template to all pages
        }
    }

    /**
     * Update the `template` property inside the page row's `data` JSON and persist it.
     * Must be called on [Dispatchers.IO].
     */
    private suspend fun persistPageTemplate(dao: NotebookDao, pageId: String, templateId: String) {
        // Columnar page: template lives in refId, size stays in boundingBox. Clears legacy data JSON.
        dao.updatePageTemplate(pageId, templateId, System.currentTimeMillis())
    }

    // ── Notebook metadata operations ──────────────────────────────────────────

    /**
     * Load the notebook metadata row from [db] and parse it into a [NotebookMetadata].
     * Returns null if the row doesn't exist (old notebook without metadata row) or
     * the JSON is malformed.  Must be called on [Dispatchers.IO].
     */
    private suspend fun loadNotebookMetadataFromDb(db: SoilDatabase): NotebookMetadata? {
        val obj = db.notebookDao().getNotebookObject() ?: return null
        return try {
            obj.notebookMetadata()
        } catch (e: Exception) {
            Log.e(TAG, "loadNotebookMetadataFromDb: failed to parse metadata row", e)
            null
        }
    }

    /**
     * Asynchronously persist [pageId] as the last-opened page in the notebook metadata row.
     * No-op if [notebookMetadata] is null (old notebook without the row).
     * Fast, non-blocking — launches a background coroutine and returns immediately.
     */
    private fun saveLastOpenedPage(pageId: String) {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val meta = notebookMetadata ?: return@withContext
                val updatedMeta = meta.copy(lastOpenedPage = pageId)
                notebookMetadata = updatedMeta
                val obj = db.notebookDao().getNotebookObject() ?: return@withContext
                val now = System.currentTimeMillis()
                db.notebookDao().upsertNotebookObject(updatedMeta.writeOnto(obj, now))
            }
        }
    }

    // ── Page operations ───────────────────────────────────────────────────────

    /**
     * Add a new page immediately after the current page.
     *
     * The new page inherits the current page's template: if the current page has a
     * non-empty template id, the new page starts with the same template.
     *
     * Steps: save current strokes → shift order of subsequent pages → insert new
     * page + bootstrap layer → reload pages → advance [currentPageIndex].
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun addPage(db: SoilDatabase) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        val bounds = screenBounds()
        val screenW = bounds.width().toFloat()
        val screenH = bounds.height().toFloat()
        val bboxJson = BoundingBox(0f, 0f, screenW, screenH).toJson()

        val insertionIndex = currentPageIndex + 1

        // Inherit the current page's template — re-read from DB so we always get the latest
        // template id even if the in-memory `pages` list is stale (e.g. after applyTemplateToCurrentPage
        // updated the DB without refreshing the list).
        val freshCurrentPage = dao.getObjectById(currentPageId)
        val inheritedTemplate = freshCurrentPage?.pageData()?.template ?: ""

        // Shift the order of every page that comes after the insertion point.
        for (i in insertionIndex until pages.size) {
            dao.updateOrder(pages[i].id, i + 1)
        }

        // Insert the new page.  parentId is the notebook metadata UUID (if present)
        // so the hierarchy is correct; falls back to NIL_UUID for legacy notebooks.
        val newPageId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newPageId,
                type        = "page",
                parentId    = notebookMetadata?.id ?: MainActivity.NIL_UUID,
                boundingBox = bboxJson,
                sortOrder   = insertionIndex,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = "",
                refId       = inheritedTemplate,
            )
        )

        // Bootstrap a content layer for the new page.
        val newLayerId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newLayerId,
                type        = "layer",
                parentId    = newPageId,
                boundingBox = bboxJson,
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = "",
                text        = "Content",
                flags       = LAYER_FLAGS_DEFAULT,
            )
        )

        // Reload and advance the page pointer so loadStrokesFromDb uses the new page.
        pages = dao.getPagesSorted().toMutableList()
        currentPageIndex = insertionIndex
        currentPageId = newPageId
        currentLayerId = newLayerId
    }

    /**
     * Insert a new page immediately BEFORE the current page.
     *
     * Mirrors [addPage] exactly, except insertionIndex = currentPageIndex so the new page
     * lands at the current position and the current page shifts forward by one.
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun addPageBefore(db: SoilDatabase) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = System.currentTimeMillis()
        val bounds = screenBounds()
        val screenW = bounds.width().toFloat()
        val screenH = bounds.height().toFloat()
        val bboxJson = BoundingBox(0f, 0f, screenW, screenH).toJson()

        val insertionIndex = currentPageIndex

        val freshCurrentPage = dao.getObjectById(currentPageId)
        val inheritedTemplate = freshCurrentPage?.pageData()?.template ?: ""

        for (i in insertionIndex until pages.size) {
            dao.updateOrder(pages[i].id, i + 1)
        }

        val newPageId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newPageId,
                type        = "page",
                parentId    = notebookMetadata?.id ?: MainActivity.NIL_UUID,
                boundingBox = bboxJson,
                sortOrder   = insertionIndex,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = "",
                refId       = inheritedTemplate,
            )
        )

        val newLayerId = UUID.randomUUID().toString()
        dao.insertObject(
            NotebookObject(
                id          = newLayerId,
                type        = "layer",
                parentId    = newPageId,
                boundingBox = bboxJson,
                sortOrder   = 0,
                createdAt   = now,
                updatedAt   = now,
                deletedAt   = null,
                data        = "",
                text        = "Content",
                flags       = LAYER_FLAGS_DEFAULT,
            )
        )

        pages = dao.getPagesSorted().toMutableList()
        currentPageIndex = insertionIndex
        currentPageId = newPageId
        currentLayerId = newLayerId
    }

    /**
     * Delete the current page, its layer, and all strokes under the layer.
     *
     * If the notebook would become empty, a fresh page + layer is bootstrapped
     * automatically so the user is never left with a blank notebook.
     *
     * @param deletedAt The timestamp to use for all soft-delete operations.  Callers must
     * pass the same value that was captured before calling this function so the undo action
     * ([UndoRedoAction.PageDeleted.deletedAt]) matches the DB rows exactly — every cascade
     * delete uses the identical timestamp so [NotebookDao.restoreChildrenDeletedSince] can
     * recover exactly those rows on undo.
     *
     * Must be called on [Dispatchers.IO].  Caller updates the UI after return.
     */
    private suspend fun deletePage(db: SoilDatabase, deletedAt: Long = System.currentTimeMillis()) {
        saveStrokes(db)
        val dao = db.notebookDao()
        val now = deletedAt

        // Cascade soft-deletes: page → layer → all strokes.
        dao.softDeleteById(currentPageId, now)
        dao.softDeleteByParentId(currentPageId, now)   // soft-deletes the layer row(s)
        dao.softDeleteByParentId(currentLayerId, now)  // soft-deletes all stroke rows

        // Reload surviving pages.
        pages = dao.getPagesSorted().toMutableList()

        if (pages.isEmpty()) {
            // Notebook is empty — bootstrap one fresh page so the user is never stuck.
            val bounds = screenBounds()
            val screenW = bounds.width().toFloat()
            val screenH = bounds.height().toFloat()
            val bboxJson = BoundingBox(0f, 0f, screenW, screenH).toJson()
            val newNow = System.currentTimeMillis()

            val newPageId = UUID.randomUUID().toString()
            dao.insertObject(
                NotebookObject(
                    id          = newPageId,
                    type        = "page",
                    parentId    = notebookMetadata?.id ?: MainActivity.NIL_UUID,
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = newNow,
                    updatedAt   = newNow,
                    deletedAt   = null,
                    data        = "",
                    refId       = "",
                )
            )
            val newLayerId = UUID.randomUUID().toString()
            dao.insertObject(
                NotebookObject(
                    id          = newLayerId,
                    type        = "layer",
                    parentId    = newPageId,
                    boundingBox = bboxJson,
                    sortOrder   = 0,
                    createdAt   = newNow,
                    updatedAt   = newNow,
                    deletedAt   = null,
                    data        = "",
                text        = "Content",
                flags       = LAYER_FLAGS_DEFAULT,
                )
            )
            pages = dao.getPagesSorted().toMutableList()
        }

        // Clamp the page pointer and resolve the new current page/layer IDs.
        currentPageIndex = currentPageIndex.coerceIn(0, pages.lastIndex)
        val page = pages[currentPageIndex]
        currentPageId = page.id
        currentLayerId = dao.getLayerForPage(currentPageId)?.id ?: ""
    }

    // ── Page navigation ───────────────────────────────────────────────────────

    /**
     * Save the current page's strokes and capture its snapshot, then switch to [newIndex]
     * and load its content via the snapshot fast path or full render path.
     * Persists [NotebookMetadata.lastOpenedPage] after every page turn.
     * Called from the swipe gesture detector.
     */
    private fun navigateToPage(newIndex: Int) {
        lifecycleScope.launch { navigateToPageInternal(newIndex) }
    }

    /**
     * Suspend implementation of [navigateToPage], callable directly from coroutines
     * (e.g. undo/redo execution).  Saves the current page, clears the canvas, loads
     * the new page, and updates the UI — fully handling all thread context switches.
     *
     * Always call from the main-thread coroutine context.
     */
    private suspend fun navigateToPageInternal(newIndex: Int) {
        val db = soilDatabase ?: return
        // Persist any unsaved ink before leaving the page. No per-page snapshot is captured — the
        // library-grid cover is captured to the global index only at notebook close.
        withContext(Dispatchers.IO) { saveStrokes(db) }

        currentPageIndex = newIndex
        selectedObjectIds.clear()
        drawingView.setLassoOverlay(null, null)
        hideFloatingSelectionToolbar()
        drawingView.clearForPageLoad()

        val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
        displayPage(result)
        updatePageIndicator()
        saveLastOpenedPage(currentPageId)
        postDisplayWork(db, result)
    }

    /**
     * Lightweight pre-flight for undo/redo cross-page navigation: saves and snapshots
     * the current page and sets [currentPageIndex] to [newIndex], but does NOT load the
     * new page's content — the undo/redo caller performs its DB operation and then runs a
     * final [loadCurrentPage] which loads the correct post-operation state in one pass.
     *
     * Avoids the double-load (navigate + undo reload) that [navigateToPageInternal] would cause,
     * eliminating the background-deserialization race that could call [setStrokeListSilently]
     * with stale data after the DB operation changed stroke rows.
     *
     * Must be called from the main-thread coroutine context ([captureSnapshot] requires main thread).
     */
    private suspend fun saveAndSwitchPage(newIndex: Int) {
        val db = soilDatabase ?: return
        withContext(Dispatchers.IO) {
            saveStrokes(db)
        }
        currentPageIndex = newIndex
        drawingView.clearForPageLoad()
    }

    /** Refresh the page indicator overlay text. Call on the main thread. */
    private fun updatePageIndicator() {
        val pageText = "${currentPageIndex + 1} / ${pages.size.coerceAtLeast(1)}"
        binding.tvPageIndicator.text =
            if (notebookDisplayName.isNotBlank()) "$notebookDisplayName · $pageText" else pageText
        val count = pages.size
        if (notebookId.isNotEmpty() && count != lastSyncedPageCount) {
            lastSyncedPageCount = count
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { indexRepo.updateNotebookPageCount(notebookId, count) }
            }
        }
    }

    // ── Undo/redo execution ───────────────────────────────────────────────────

    /**
     * Undo/redo buttons always appear enabled — matching the native BOOX notes app behaviour.
     * Tapping when the stack is empty is a no-op; [performUndo] / [performRedo] guard that.
     * No visual state change needed here; this function is kept as a call-site placeholder.
     */
    private fun updateUndoRedoButtons() {
        // No-op — buttons are statically enabled in the layout and never tinted.
    }

    // ── Lasso copy/paste helpers ──────────────────────────────────────────────

    /**
     * Compute the bounding box for a text heading from its embedded stroke positions and
     * recognized text, using the same 20sp paint + 8dp padding as [createHeadingFromStrokes].
     * Falls back to stroke-based bounds when [recognizedText] is null.
     */
    private fun headingBoundingBox(embeddedStrokes: List<LiveStroke>, recognizedText: String?): RectF {
        val strokeBox = RectF()
        embeddedStrokes.forEach { strokeBox.union(it.boundingBox) }
        val pad = 8f * resources.displayMetrics.density
        if (recognizedText == null) {
            strokeBox.inset(-pad, -pad)
            return strokeBox
        }
        val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
        }
        val (measuredW, measuredH) = TextObjectRenderer.measure(recognizedText, resources.displayMetrics.widthPixels, textPaint, resources.displayMetrics.density, singleLine = true)
        return RectF(
            strokeBox.left, strokeBox.top,
            strokeBox.left + pad + measuredW + pad,
            strokeBox.top  + pad + measuredH + pad,
        )
    }

    private fun computeUnionBoundingBox(
        strokes: List<LiveStroke>,
        headings: List<HeadingStroke>,
    ): RectF {
        val union = RectF()
        strokes.forEach { union.union(it.boundingBox) }
        headings.forEach { union.union(it.boundingBox) }
        return union
    }

    /** Copy the currently selected strokes/headings/text/line objects to [NotesproutClipboard]. */
    private fun performLassoCopy() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes      = drawingView.getStrokes().filter { it.id in ids }
        val headings     = drawingView.getHeadings().filter { it.id in ids }
        val textObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val linkObjects  = drawingView.getLinks().filter { it.id in ids }
        val stickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        val shapeObjects = drawingView.getShapeObjects().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && linkObjects.isEmpty() && stickyNotes.isEmpty() && shapeObjects.isEmpty()) return
        val box = computeUnionBoundingBox(strokes, headings)
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        linkObjects.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }
        shapeObjects.forEach { box.union(it.boundingBox) }
        val clip = NotesproutClipboard.ClipboardContent(
            strokes  = strokes.map { it.deepCopy() },
            headings = headings.map { h -> HeadingStroke(h.id, RectF(h.boundingBox),
                h.strokes.map { s -> s.deepCopy() },
                recognizedText = h.recognizedText,
                level = h.level) },
            boundingBox = box,
            textObjects = textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
            lineObjects = lineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) },
            links = linkObjects.map { it.translate(0f, 0f) },
            stickyNotes = stickyNotes.map { it.translate(0f, 0f) },
            shapeObjects = shapeObjects.map { it.copy(boundingBox = RectF(it.boundingBox)) },
        )
        lifecycleScope.launch {
            NotesproutClipboard.content = clip
            updateLassoButtonIcon()
            withContext(Dispatchers.IO) { indexRepo.saveClipboard(clip.toPayload(notebookId)) }
            // Deselect immediately so the user can paste without tapping off first.
            // Lasso mode stays active (isSmartLassoSession is preserved) so they can paste
            // on this page or navigate to another page and paste there.
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
        }
    }

    /**
     * Paste clipboard strokes/headings/text objects onto the current page, centered on
     * [tapX], [tapY].  Inserts new rows into the DB, updates in-memory lists, sets them
     * as the active selection, and shows the floating toolbar at the new selection box.
     * When the copy/cut was initiated via smart lasso, returns to pen tool after paste.
     */
    private fun performLassoPaste(tapX: Float, tapY: Float) {
        val clip = NotesproutClipboard.content ?: return
        val db = soilDatabase ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return

        val cx = clip.boundingBox.centerX()
        val cy = clip.boundingBox.centerY()
        val dx = tapX - cx
        val dy = tapY - cy

        val insertedAt = System.currentTimeMillis()
        val newStrokes = clip.strokes.map { stroke ->
            stroke.copy(
                id = java.util.UUID.randomUUID().toString(),
                points = stroke.points.map { PointF(it.x + dx, it.y + dy) },
            )
        }
        val newHeadings = clip.headings.map { h ->
            HeadingStroke(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy,
                                    h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s ->
                    s.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        points = s.points.map { PointF(it.x + dx, it.y + dy) },
                    )
                },
                recognizedText = h.recognizedText,
                level = h.level,
            )
        }
        val newTextObjects = clip.textObjects.map { t ->
            TextRender(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(t.boundingBox.left + dx, t.boundingBox.top + dy,
                                    t.boundingBox.right + dx, t.boundingBox.bottom + dy),
                text = t.text,
                strokes = t.strokes,
            )
        }
        val newLineObjects = clip.lineObjects.map { l ->
            l.copy(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(l.boundingBox.left + dx, l.boundingBox.top + dy,
                                    l.boundingBox.right + dx, l.boundingBox.bottom + dy),
                startX = l.startX + dx, startY = l.startY + dy,
                endX   = l.endX   + dx, endY   = l.endY   + dy,
            )
        }
        // Translate each link by the paste offset and assign a fresh UUID (embedded content moves too).
        val newLinks = clip.links.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        // Translate each sticky note's icon box; embedded content stays in its own coordinate space.
        val newStickyNotes = clip.stickyNotes.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val newShapes = clip.shapeObjects.map { s ->
            s.copy(
                id = java.util.UUID.randomUUID().toString(),
                centerX = s.centerX + dx,
                centerY = s.centerY + dy,
                boundingBox = RectF(s.boundingBox.left + dx, s.boundingBox.top + dy,
                                    s.boundingBox.right + dx, s.boundingBox.bottom + dy),
            )
        }
        val density = resources.displayMetrics.density

        lifecycleScope.launch {
            val existingStrokes = drawingView.getStrokes()
            val baseOrder = existingStrokes.size
            withContext(Dispatchers.IO) {
                val now = insertedAt
                val dao = db.notebookDao()
                db.withTransaction {
                    newStrokes.forEachIndexed { i, stroke ->
                        if (stroke.points.size < 2) return@forEachIndexed
                        dao.insertOrIgnore(
                            stroke.toStrokeRow(
                                parentId  = layerId,
                                order     = baseOrder + i,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                    }
                    newHeadings.forEach { heading ->
                        dao.insertHeadingSubtree(heading, layerId, 0, now, now)
                    }
                    newTextObjects.forEach { textObj ->
                        dao.insertTextSubtree(textObj, layerId, 0, now, now)
                    }
                    newLineObjects.forEach { lineObj ->
                        dao.insertOrIgnore(lineObj.toRow(layerId, 0, now, now, density))
                    }
                    newLinks.forEach { link ->
                        dao.insertLinkSubtree(link, layerId, 0, now, now, density)
                    }
                    newStickyNotes.forEach { note ->
                        dao.insertStickyNoteSubtree(note, layerId, 0, now, now, density)
                    }
                    newShapes.forEach { shape ->
                        dao.insertOrIgnore(shape.toRow(layerId, 0, now, now, density))
                    }
                }
                if (newLinks.isNotEmpty() || newStickyNotes.isNotEmpty() || newShapes.isNotEmpty()) noteContentEdit(db, pageId)
            }

            val allStrokes      = existingStrokes + newStrokes
            val allHeadings     = drawingView.getHeadings() + newHeadings
            val allTexts        = drawingView.getTextObjects() + newTextObjects
            val allLines        = drawingView.getLineObjects() + newLineObjects
            val allLinks        = drawingView.getLinks() + newLinks
            val allStickyNotes  = drawingView.getStickyNotes() + newStickyNotes
            val allShapes       = drawingView.getShapeObjects() + newShapes
            newStrokes.forEach { persistedStrokeIds.add(it.id) }
            drawingView.loadHeadings(allHeadings)
            drawingView.loadTextObjects(allTexts)
            drawingView.loadLineObjects(allLines)
            drawingView.loadLinks(allLinks)
            drawingView.loadStickyNotes(allStickyNotes)
            drawingView.loadShapeObjects(allShapes)

            undoRedoManager.push(UndoRedoAction.LassoPasted(
                strokeIds      = newStrokes.map { it.id },
                pageId         = pageId,
                insertedAt     = insertedAt,
                headingIds     = newHeadings.map { it.id },
                textIds        = newTextObjects.map { it.id },
                textObjects    = newTextObjects,
                lineIds        = newLineObjects.map { it.id },
                lines          = newLineObjects,
                linkIds        = newLinks.map { it.id },
                links          = newLinks,
                stickyNoteIds  = newStickyNotes.map { it.id },
                stickyNotes    = newStickyNotes,
                shapeIds       = newShapes.map { it.id },
                shapes         = newShapes,
            ))
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(allStrokes, templateBmp, allHeadings, allTexts, allLines, allLinks, stickyNotes = allStickyNotes, shapeObjects = allShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(allStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(allStrokes)
            }

            // Set pasted content as the active selection regardless of how paste was triggered.
            val newBox = computeUnionBoundingBox(newStrokes, newHeadings)
            newTextObjects.forEach { newBox.union(it.boundingBox) }
            newLineObjects.forEach { newBox.union(it.boundingBox) }
            newLinks.forEach { newBox.union(it.boundingBox) }
            newStickyNotes.forEach { newBox.union(it.boundingBox) }
            newShapes.forEach { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newStrokes.map { it.id })
            selectedObjectIds.addAll(newHeadings.map { it.id })
            selectedObjectIds.addAll(newTextObjects.map { it.id })
            selectedObjectIds.addAll(newLineObjects.map { it.id })
            selectedObjectIds.addAll(newLinks.map { it.id })
            selectedObjectIds.addAll(newStickyNotes.map { it.id })
            selectedObjectIds.addAll(newShapes.map { it.id })
            isSmartLassoSession = false
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
        }
    }

    /**
     * Paste content transferred from the scratch pad onto the current page, top-left aligned to
     * the page origin (0,0).  Leaves the pasted objects selected.
     */
    private fun performScratchpadTransfer(content: NotesproutClipboard.ClipboardContent) {
        val db      = soilDatabase ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return

        // Translate so the center of the selection lands at the center of the page.
        val pageCx = binding.drawingContainer.width  / 2f
        val pageCy = binding.drawingContainer.height / 2f
        val dx = pageCx - content.boundingBox.centerX()
        val dy = pageCy - content.boundingBox.centerY()

        val insertedAt = System.currentTimeMillis()
        val newStrokes = content.strokes.map { stroke ->
            stroke.copy(
                id = java.util.UUID.randomUUID().toString(),
                points = stroke.points.map { PointF(it.x + dx, it.y + dy) },
            )
        }
        val newHeadings = content.headings.map { h ->
            HeadingStroke(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy,
                                    h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                strokes = h.strokes.map { s ->
                    s.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        points = s.points.map { PointF(it.x + dx, it.y + dy) },
                    )
                },
                recognizedText = h.recognizedText,
                level = h.level,
            )
        }
        val newTextObjects = content.textObjects.map { t ->
            TextRender(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(t.boundingBox.left + dx, t.boundingBox.top + dy,
                                    t.boundingBox.right + dx, t.boundingBox.bottom + dy),
                text = t.text,
                strokes = t.strokes,
            )
        }
        val newLineObjects = content.lineObjects.map { l ->
            l.copy(
                id = java.util.UUID.randomUUID().toString(),
                boundingBox = RectF(l.boundingBox.left + dx, l.boundingBox.top + dy,
                                    l.boundingBox.right + dx, l.boundingBox.bottom + dy),
                startX = l.startX + dx, startY = l.startY + dy,
                endX   = l.endX   + dx, endY   = l.endY   + dy,
            )
        }
        val newLinks = content.links.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val newStickyNotes = content.stickyNotes.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) }
        val newShapes = content.shapeObjects.map { s ->
            s.copy(
                id          = java.util.UUID.randomUUID().toString(),
                centerX     = s.centerX + dx,
                centerY     = s.centerY + dy,
                boundingBox = RectF(s.boundingBox.left + dx, s.boundingBox.top + dy,
                                    s.boundingBox.right + dx, s.boundingBox.bottom + dy),
            )
        }
        val density = resources.displayMetrics.density

        lifecycleScope.launch {
            val existingStrokes = drawingView.getStrokes()
            val baseOrder = existingStrokes.size
            withContext(Dispatchers.IO) {
                val now = insertedAt
                val dao = db.notebookDao()
                db.withTransaction {
                    newStrokes.forEachIndexed { i, stroke ->
                        if (stroke.points.size < 2) return@forEachIndexed
                        dao.insertOrIgnore(
                            stroke.toStrokeRow(
                                parentId  = layerId,
                                order     = baseOrder + i,
                                createdAt = now,
                                updatedAt = now,
                            )
                        )
                    }
                    newHeadings.forEach { heading ->
                        dao.insertHeadingSubtree(heading, layerId, 0, now, now)
                    }
                    newTextObjects.forEach { textObj ->
                        dao.insertTextSubtree(textObj, layerId, 0, now, now)
                    }
                    newLineObjects.forEach { lineObj ->
                        dao.insertOrIgnore(lineObj.toRow(layerId, 0, now, now, density))
                    }
                    newLinks.forEach { link ->
                        dao.insertLinkSubtree(link, layerId, 0, now, now, density)
                    }
                    newStickyNotes.forEach { note ->
                        dao.insertStickyNoteSubtree(note, layerId, 0, now, now, density)
                    }
                    newShapes.forEach { shape ->
                        dao.insertOrIgnore(shape.toRow(layerId, 0, now, now, density))
                    }
                }
                if (newLinks.isNotEmpty() || newStickyNotes.isNotEmpty() || newShapes.isNotEmpty()) noteContentEdit(db, pageId)
            }

            val allStrokes      = existingStrokes + newStrokes
            val allHeadings     = drawingView.getHeadings() + newHeadings
            val allTexts        = drawingView.getTextObjects() + newTextObjects
            val allLines        = drawingView.getLineObjects() + newLineObjects
            val allLinks        = drawingView.getLinks() + newLinks
            val allStickyNotes  = drawingView.getStickyNotes() + newStickyNotes
            val allShapes       = drawingView.getShapeObjects() + newShapes
            newStrokes.forEach { persistedStrokeIds.add(it.id) }
            drawingView.loadHeadings(allHeadings)
            drawingView.loadTextObjects(allTexts)
            drawingView.loadLineObjects(allLines)
            drawingView.loadLinks(allLinks)
            drawingView.loadStickyNotes(allStickyNotes)
            drawingView.loadShapeObjects(allShapes)

            undoRedoManager.push(UndoRedoAction.LassoPasted(
                strokeIds      = newStrokes.map { it.id },
                pageId         = pageId,
                insertedAt     = insertedAt,
                headingIds     = newHeadings.map { it.id },
                textIds        = newTextObjects.map { it.id },
                textObjects    = newTextObjects,
                lineIds        = newLineObjects.map { it.id },
                lines          = newLineObjects,
                linkIds        = newLinks.map { it.id },
                links          = newLinks,
                stickyNoteIds  = newStickyNotes.map { it.id },
                stickyNotes    = newStickyNotes,
                shapeIds       = newShapes.map { it.id },
                shapes         = newShapes,
            ))
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(allStrokes, templateBmp, allHeadings, allTexts, allLines, allLinks, stickyNotes = allStickyNotes, shapeObjects = allShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(allStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(allStrokes)
            }

            val newBox = computeUnionBoundingBox(newStrokes, newHeadings)
            newTextObjects.forEach { newBox.union(it.boundingBox) }
            newLineObjects.forEach { newBox.union(it.boundingBox) }
            newLinks.forEach { newBox.union(it.boundingBox) }
            newStickyNotes.forEach { newBox.union(it.boundingBox) }
            newShapes.forEach { newBox.union(it.boundingBox) }
            val pad = 8f * resources.displayMetrics.density
            newBox.inset(-pad, -pad)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newStrokes.map { it.id })
            selectedObjectIds.addAll(newHeadings.map { it.id })
            selectedObjectIds.addAll(newTextObjects.map { it.id })
            selectedObjectIds.addAll(newLineObjects.map { it.id })
            selectedObjectIds.addAll(newLinks.map { it.id })
            selectedObjectIds.addAll(newStickyNotes.map { it.id })
            selectedObjectIds.addAll(newShapes.map { it.id })
            isSmartLassoSession = false
            if (!isLassoMode) enterLassoMode()
            drawingView.setLassoSelectedIds(selectedObjectIds.toSet(), newBox)
            updateFloatingSelectionToolbar(newBox)
        }
    }

    /**
     * Cut the currently selected strokes/headings/text objects: soft-delete them and populate
     * [NotesproutClipboard].  Lasso mode stays active so the user can immediately paste.
     */
    private fun performLassoCut() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        val selectedStrokes      = drawingView.getStrokes().filter { it.id in ids }
        val selectedHeadings     = drawingView.getHeadings().filter { it.id in ids }
        val selectedTextObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val selectedLineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val selectedLinks        = drawingView.getLinks().filter { it.id in ids }
        val selectedStickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        val selectedShapes       = drawingView.getShapeObjects().filter { it.id in ids }
        if (selectedStrokes.isEmpty() && selectedHeadings.isEmpty() && selectedTextObjects.isEmpty() && selectedLineObjects.isEmpty() && selectedLinks.isEmpty() && selectedStickyNotes.isEmpty() && selectedShapes.isEmpty()) return

        val box = computeUnionBoundingBox(selectedStrokes, selectedHeadings)
        selectedTextObjects.forEach { box.union(it.boundingBox) }
        selectedLineObjects.forEach { box.union(it.boundingBox) }
        selectedLinks.forEach { box.union(it.boundingBox) }
        selectedStickyNotes.forEach { box.union(it.boundingBox) }
        selectedShapes.forEach { box.union(it.boundingBox) }

        val clipStrokes  = selectedStrokes.map { s ->
            s.deepCopy()
        }
        val clipHeadings = selectedHeadings.map { h ->
            HeadingStroke(h.id, RectF(h.boundingBox),
                h.strokes.map { s -> s.deepCopy() },
                recognizedText = h.recognizedText,
                level = h.level)
        }
        val clipTextObjects  = selectedTextObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) }
        val clipLineObjects  = selectedLineObjects.map { l -> l.copy(boundingBox = RectF(l.boundingBox)) }
        val clipLinks        = selectedLinks.map { it.translate(0f, 0f) }
        val clipStickyNotes  = selectedStickyNotes.map { it.translate(0f, 0f) }
        val clip = NotesproutClipboard.ClipboardContent(
            strokes     = clipStrokes,
            headings    = clipHeadings,
            boundingBox = box,
            textObjects = clipTextObjects,
            lineObjects = clipLineObjects,
            links       = clipLinks,
            stickyNotes = clipStickyNotes,
            shapeObjects = selectedShapes.map { it.copy(boundingBox = RectF(it.boundingBox)) },
        )

        val strokeIds      = selectedStrokes.map { it.id }
        val headingIds     = selectedHeadings.map { it.id }
        val textIds        = selectedTextObjects.map { it.id }
        val lineIds        = selectedLineObjects.map { it.id }
        val linkIds        = selectedLinks.map { it.id }
        val stickyNoteIds  = selectedStickyNotes.map { it.id }
        val shapeIds       = selectedShapes.map { it.id }

        lifecycleScope.launch {
            NotesproutClipboard.content = clip
            withContext(Dispatchers.IO) {
            indexRepo.saveClipboard(clip.toPayload(notebookId))
            val deletedAt = System.currentTimeMillis()
            (strokeIds + headingIds + textIds + lineIds + linkIds + stickyNoteIds + shapeIds).forEach { id ->
                soilDatabase?.notebookDao()?.softDeleteById(id, deletedAt)
            }
            soilDatabase?.let { if (linkIds.isNotEmpty() || stickyNoteIds.isNotEmpty() || shapeIds.isNotEmpty()) noteContentEdit(it, pageId) }
            withContext(Dispatchers.Main) {
                val erasedStrokeSet      = strokeIds.toSet()
                val erasedHeadingSet     = headingIds.toSet()
                val erasedTextSet        = textIds.toSet()
                val erasedLineSet        = lineIds.toSet()
                val erasedLinkSet        = linkIds.toSet()
                val erasedStickyNoteSet  = stickyNoteIds.toSet()
                val updatedStrokes      = drawingView.getStrokes().filter { it.id !in erasedStrokeSet }
                val updatedHeadings     = drawingView.getHeadings().filter { it.id !in erasedHeadingSet }
                val updatedTexts        = drawingView.getTextObjects().filter { it.id !in erasedTextSet }
                val updatedLines        = drawingView.getLineObjects().filter { it.id !in erasedLineSet }
                val updatedLinks        = drawingView.getLinks().filter { it.id !in erasedLinkSet }
                val updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in erasedStickyNoteSet }
                val erasedShapeSet       = shapeIds.toSet()
                val updatedShapes        = drawingView.getShapeObjects().filter { it.id !in erasedShapeSet }
                persistedStrokeIds.removeAll(erasedStrokeSet)
                drawingView.setStrokeListSilently(updatedStrokes)
                drawingView.loadHeadings(updatedHeadings)
                drawingView.loadTextObjects(updatedTexts)
                drawingView.loadLineObjects(updatedLines)
                drawingView.loadLinks(updatedLinks)
                drawingView.loadStickyNotes(updatedStickyNotes)
                drawingView.loadShapeObjects(updatedShapes)
                undoRedoManager.push(UndoRedoAction.LassoCut(
                    strokeIds      = strokeIds,
                    pageId         = pageId,
                    deletedAt      = deletedAt,
                    strokes        = clipStrokes,
                    headingIds     = headingIds,
                    headings       = clipHeadings,
                    textIds        = textIds,
                    textObjects    = clipTextObjects,
                    lineIds        = lineIds,
                    lines          = clipLineObjects,
                    linkIds        = linkIds,
                    links          = clipLinks,
                    stickyNoteIds  = stickyNoteIds,
                    stickyNotes    = clipStickyNotes,
                    shapeIds       = shapeIds,
                    shapes         = selectedShapes,
                ))
                updateUndoRedoButtons()
                val templateBmp = currentTemplateBitmap
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, stickyNotes = updatedStickyNotes)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                } else {
                    drawingView.loadStrokes(updatedStrokes)
                }
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
                updateLassoButtonIcon()
            }
            }
        }
    }

    /** Clears both the in-memory clipboard and the persisted row in notesprout.db. */
    private fun clearClipboard() {
        NotesproutClipboard.clear()
        NotesproutApplication.appScope.launch { indexRepo.clearClipboard() }
    }

    /**
     * Send the current lasso selection into the scratch pad (background, no UI open).
     * Shows: encryption warning (if needed) → crop/cancel (if too large) → new/current page.
     */
    private suspend fun performSendToScratchpad() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val strokes      = drawingView.getStrokes().filter { it.id in ids }
        val headings     = drawingView.getHeadings().filter { it.id in ids }
        val textObjects  = drawingView.getTextObjects().filter { it.id in ids }
        val lineObjects  = drawingView.getLineObjects().filter { it.id in ids }
        val linkObjects  = drawingView.getLinks().filter { it.id in ids }
        val stickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        val shapeObjects = drawingView.getShapeObjects().filter { it.id in ids }
        if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && linkObjects.isEmpty() && stickyNotes.isEmpty() && shapeObjects.isEmpty()) return

        val box = computeUnionBoundingBox(strokes, headings)
        textObjects.forEach { box.union(it.boundingBox) }
        lineObjects.forEach { box.union(it.boundingBox) }
        linkObjects.forEach { box.union(it.boundingBox) }
        stickyNotes.forEach { box.union(it.boundingBox) }
        shapeObjects.forEach { box.union(it.boundingBox) }

        // No encryption gate: the scratch pad lives in notesprout.db, which is encrypted at rest.
        // Ensure the scratch pad is initialised so we can read page dimensions.
        withContext(Dispatchers.IO) { scratchpadRepo.ensureBootstrap() }

        // Get current scratch pad page size.
        val pages = withContext(Dispatchers.IO) { scratchpadRepo.getPages() }
        val savedIndex = ScratchpadPreferences.loadCurrentPageIndex(this)
        val pageIndex = savedIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val currentPage = pages.getOrNull(pageIndex)
        val pageData = currentPage?.toNotebookObject()?.pageData()
        val pageW = pageData?.width ?: 0f
        val pageH = pageData?.height ?: 0f

        // Fit check: compare selection dimensions against the current page.
        val selW = box.width()
        val selH = box.height()
        val doesNotFit = pageW > 0f && pageH > 0f && (selW > pageW || selH > pageH)

        if (doesNotFit) {
            val proceed = suspendCancellableCoroutine<Boolean> { cont ->
                AlertDialog.Builder(this)
                    .setMessage(
                        "This selection is larger than the scratch pad page. " +
                        "Crop it to fit (content may be clipped), or cancel?"
                    )
                    .setPositiveButton("Crop") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .create()
                    .also { d ->
                        d.show()
                        d.window?.setElevation(0f)
                        d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                    }
            }
            if (!proceed) return
        }

        // Placement prompt: new page or current page.
        val useNewPage = suspendCancellableCoroutine<Boolean?> { cont ->
            AlertDialog.Builder(this)
                .setMessage("Add to the scratch pad?")
                .setPositiveButton("New page") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNeutralButton("Current page") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
                .also { d ->
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                }
        } ?: return

        // Resolve the target page and layer. New page goes at the end of the list.
        data class LayerTarget(val pageId: String, val layerId: String)
        val target = withContext(Dispatchers.IO) {
            if (useNewPage) {
                val updatedPages = scratchpadRepo.getPages()
                val newPageId = scratchpadRepo.addPage(afterIndex = updatedPages.size - 1)
                val lId = scratchpadRepo.getLayerForPage(newPageId)?.id ?: return@withContext null
                LayerTarget(newPageId, lId)
            } else {
                val updatedPages = scratchpadRepo.getPages()
                val idx = ScratchpadPreferences.loadCurrentPageIndex(this@NotebookActivity)
                    .coerceIn(0, (updatedPages.size - 1).coerceAtLeast(0))
                val pId = updatedPages.getOrNull(idx)?.id ?: return@withContext null
                val lId = scratchpadRepo.getLayerForPage(pId)?.id ?: return@withContext null
                LayerTarget(pId, lId)
            }
        } ?: run {
            android.widget.Toast.makeText(this, "Could not send to scratch pad.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Compute dx/dy: center on the target page when dimensions are known; fall back to origin.
        val dx: Float
        val dy: Float
        if (pageW > 0f && pageH > 0f) {
            dx = (pageW - selW) / 2f - box.left
            dy = (pageH - selH) / 2f - box.top
        } else {
            dx = -box.left
            dy = -box.top
        }

        // Build translated, fresh-UUID content.
        val density = resources.displayMetrics.density
        val translatedContent = NotesproutClipboard.ClipboardContent(
            strokes = strokes.map { s ->
                s.translated(dx, dy, newId = java.util.UUID.randomUUID().toString())
            },
            headings = headings.map { h ->
                HeadingStroke(
                    id = java.util.UUID.randomUUID().toString(),
                    boundingBox = RectF(h.boundingBox.left + dx, h.boundingBox.top + dy,
                                        h.boundingBox.right + dx, h.boundingBox.bottom + dy),
                    strokes = h.strokes.map { s ->
                        s.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            points = s.points.map { android.graphics.PointF(it.x + dx, it.y + dy) },
                        )
                    },
                    recognizedText = h.recognizedText,
                    level = h.level,
                )
            },
            textObjects = textObjects.map { t ->
                TextRender(
                    id = java.util.UUID.randomUUID().toString(),
                    boundingBox = RectF(t.boundingBox.left + dx, t.boundingBox.top + dy,
                                        t.boundingBox.right + dx, t.boundingBox.bottom + dy),
                    text = t.text,
                    strokes = t.strokes,
                )
            },
            lineObjects = lineObjects.map { l ->
                l.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    boundingBox = RectF(l.boundingBox.left + dx, l.boundingBox.top + dy,
                                        l.boundingBox.right + dx, l.boundingBox.bottom + dy),
                    startX = l.startX + dx, startY = l.startY + dy,
                    endX   = l.endX   + dx, endY   = l.endY   + dy,
                )
            },
            links = linkObjects.map { link -> link.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) },
            stickyNotes = stickyNotes.map { it.translate(dx, dy, newId = java.util.UUID.randomUUID().toString()) },
            shapeObjects = shapeObjects.map { s ->
                s.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    centerX = s.centerX + dx,
                    centerY = s.centerY + dy,
                    boundingBox = RectF(s.boundingBox.left + dx, s.boundingBox.top + dy,
                                        s.boundingBox.right + dx, s.boundingBox.bottom + dy),
                )
            },
            boundingBox = RectF(0f, 0f, selW, selH),
        )

        withContext(Dispatchers.IO) { scratchpadRepo.insertObjects(target.layerId, translatedContent, density) }

        // Collect the IDs of all inserted objects to pre-select them in the scratch pad.
        val insertedIds = buildList<String> {
            translatedContent.strokes.mapTo(this) { it.id }
            translatedContent.headings.mapTo(this) { it.id }
            translatedContent.textObjects.mapTo(this) { it.id }
            translatedContent.lineObjects.mapTo(this) { it.id }
            translatedContent.links.mapTo(this) { it.id }
            translatedContent.stickyNotes.mapTo(this) { it.id }
            translatedContent.shapeObjects.mapTo(this) { it.id }
        }

        // Launch the scratch pad, navigating to the target page with the new objects pre-selected.
        startActivity(
            android.content.Intent(this, ScratchpadActivity::class.java).apply {
                putExtra(ScratchpadActivity.EXTRA_FROM_NOTEBOOK_ID,        notebookId)
                putExtra(ScratchpadActivity.EXTRA_FROM_NOTEBOOK_NAME,      notebookDisplayName)
                putExtra(ScratchpadActivity.EXTRA_JUMP_TO_PAGE_ID,         target.pageId)
                putExtra(ScratchpadActivity.EXTRA_SELECT_OBJECT_IDS,       insertedIds.joinToString(","))
            }
        )
    }

    /**
     * Delete the currently selected strokes/headings from the page.
     * Unlike cut, the clipboard is never touched. Lasso mode stays active with no selection.
     */
    private fun performLassoDelete() {
        val ids = drawingView.lassoSelectedIds
        if (ids.isEmpty()) return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        val selectedStrokes      = drawingView.getStrokes().filter { it.id in ids }
        val selectedHeadings     = drawingView.getHeadings().filter { it.id in ids }
        val selectedTexts        = drawingView.getTextObjects().filter { it.id in ids }
        val selectedLines        = drawingView.getLineObjects().filter { it.id in ids }
        val selectedLinks        = drawingView.getLinks().filter { it.id in ids }
        val selectedStickyNotes  = drawingView.getStickyNotes().filter { it.id in ids }
        val selectedShapes       = drawingView.getShapeObjects().filter { it.id in ids }
        if (selectedStrokes.isEmpty() && selectedHeadings.isEmpty() && selectedTexts.isEmpty() && selectedLines.isEmpty() && selectedLinks.isEmpty() && selectedStickyNotes.isEmpty() && selectedShapes.isEmpty()) return

        val strokeIds      = selectedStrokes.map { it.id }
        val headingIds     = selectedHeadings.map { it.id }
        val textIds        = selectedTexts.map { it.id }
        val lineIds        = selectedLines.map { it.id }
        val linkIds        = selectedLinks.map { it.id }
        val stickyNoteIds  = selectedStickyNotes.map { it.id }
        val shapeIds       = selectedShapes.map { it.id }
        val capturedStrokes  = selectedStrokes.map { s ->
            s.deepCopy()
        }
        val capturedHeadings = selectedHeadings.map { h ->
            HeadingStroke(h.id, RectF(h.boundingBox),
                h.strokes.map { s -> s.deepCopy() },
                recognizedText = h.recognizedText,
                level = h.level)
        }
        val capturedLinks        = selectedLinks.map { it.translate(0f, 0f) }
        val capturedStickyNotes  = selectedStickyNotes.map { it.translate(0f, 0f) }

        lifecycleScope.launch(Dispatchers.IO) {
            val deletedAt = System.currentTimeMillis()
            (strokeIds + headingIds + textIds + lineIds + linkIds + stickyNoteIds + shapeIds).forEach { id ->
                soilDatabase?.notebookDao()?.softDeleteById(id, deletedAt)
            }
            soilDatabase?.let { if (linkIds.isNotEmpty() || stickyNoteIds.isNotEmpty() || shapeIds.isNotEmpty()) noteContentEdit(it, pageId) }
            withContext(Dispatchers.Main) {
                val erasedStrokeSet      = strokeIds.toSet()
                val erasedHeadingSet     = headingIds.toSet()
                val erasedTextSet        = textIds.toSet()
                val erasedLineSet        = lineIds.toSet()
                val erasedLinkSet        = linkIds.toSet()
                val erasedStickyNoteSet  = stickyNoteIds.toSet()
                val erasedShapeSet       = shapeIds.toSet()
                val updatedStrokes      = drawingView.getStrokes().filter { it.id !in erasedStrokeSet }
                val updatedHeadings     = drawingView.getHeadings().filter { it.id !in erasedHeadingSet }
                val updatedTexts        = drawingView.getTextObjects().filter { it.id !in erasedTextSet }
                val updatedLines        = drawingView.getLineObjects().filter { it.id !in erasedLineSet }
                val updatedLinks        = drawingView.getLinks().filter { it.id !in erasedLinkSet }
                val updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in erasedStickyNoteSet }
                val updatedShapes       = drawingView.getShapeObjects().filter { it.id !in erasedShapeSet }
                persistedStrokeIds.removeAll(erasedStrokeSet)
                drawingView.setStrokeListSilently(updatedStrokes)
                drawingView.loadHeadings(updatedHeadings)
                drawingView.loadTextObjects(updatedTexts)
                drawingView.loadLineObjects(updatedLines)
                drawingView.loadLinks(updatedLinks)
                drawingView.loadStickyNotes(updatedStickyNotes)
                drawingView.loadShapeObjects(updatedShapes)
                undoRedoManager.push(UndoRedoAction.LassoDeleted(
                    strokeIds       = strokeIds,
                    pageId          = pageId,
                    deletedAt       = deletedAt,
                    strokes         = capturedStrokes,
                    headingIds      = headingIds,
                    headings        = capturedHeadings,
                    textIds         = textIds,
                    textObjects     = selectedTexts,
                    lineIds         = lineIds,
                    lines           = selectedLines,
                    linkIds         = linkIds,
                    links           = capturedLinks,
                    stickyNoteIds   = stickyNoteIds,
                    stickyNotes     = capturedStickyNotes,
                    shapeIds        = shapeIds,
                    shapes          = selectedShapes,
                ))
                updateUndoRedoButtons()
                val templateBmp = currentTemplateBitmap
                val bitmap = withContext(Dispatchers.IO) {
                    drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
                }
                if (bitmap != null) {
                    drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
                } else {
                    drawingView.loadStrokes(updatedStrokes)
                }
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
                if (isSmartLassoSession) {
                    exitLassoMode()
                    drawingView.enableDrawing()
                    binding.btnPen.isSelected = true
                }
            }
        }
    }

    /**
     * Convert the [selectedStrokes] lasso selection into a heading object.
     * [boundingBox] is the padded selection RectF used for hit-testing and layout.
     * Must be called on [Dispatchers.IO]; switches to Main for in-memory + UI updates.
     */
    private suspend fun createHeadingFromStrokes(
        selectedStrokes: List<LiveStroke>,
        boundingBox: RectF,
        level: Int = 1,
    ) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return

        val strokesToConvert = selectedStrokes.toList()
        val boundsToConvert  = RectF(boundingBox)

        val recognizer = HandwritingRecognizerProvider.instance
        val recognizedText: String = if (recognizer != null && recognizer.isReady()) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    recognizer.recognize(strokesToConvert, boundsToConvert) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        } else {
            HandwritingRecognizer.FALLBACK_TEXT
        }

        val isRecognized = recognizedText != HandwritingRecognizer.FALLBACK_TEXT
        val storedText: String? = if (isRecognized) HeadingObject.headingPrefix(level) + recognizedText else null
        val pad = 8f * resources.displayMetrics.density
        if (storedText != null) {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            }
            val (measuredW, measuredH) = TextObjectRenderer.measure(storedText, resources.displayMetrics.widthPixels, textPaint, resources.displayMetrics.density, singleLine = true)
            boundsToConvert.set(
                boundsToConvert.left, boundsToConvert.top,
                boundsToConvert.left + pad + measuredW + pad,
                boundsToConvert.top + pad + measuredH + pad,
            )
        } else {
            boundsToConvert.inset(-pad, -pad)
        }

        val deletedAt  = System.currentTimeMillis()
        val headingId  = UUID.randomUUID().toString()
        val originalStrokeIds = strokesToConvert.map { it.id }

        // Retain embedded strokes ONLY when recognition failed (fallback) — a recognized heading
        // renders as canvas text and never reverts to strokes, so its strokes are dead weight.
        // Parity with text objects (see convertLassoToText). Fresh UUIDs for the fallback copies.
        val storedStrokes = if (isRecognized) emptyList() else strokesToConvert.map { stroke ->
            stroke.copy(id = UUID.randomUUID().toString(), points = stroke.points.map { PointF(it.x, it.y) })
        }

        // Personalization capture: the only moment a heading's ink and its recognized text
        // coexist (recognized headings drop their strokes below). Fire-and-forget; gated on
        // the personalization toggle alone. An edit within the correction window confirms it.
        if (isRecognized &&
            com.notesprout.android.recognition.personal.TrainingPairRepository.captureAllowed(this)
        ) {
            NotesproutApplication.appScope.launch {
                com.notesprout.android.recognition.personal.TrainingPairRepository.addPair(
                    context = this@NotebookActivity.applicationContext,
                    source = com.notesprout.android.recognition.personal.TrainingPairRepository.SOURCE_HEADING_CONVERSION,
                    strokes = strokesToConvert,
                    label = recognizedText,
                    confirmed = false,
                    originalText = recognizedText,
                    objectId = headingId,
                    notebookId = notebookId,
                    pageId = pageId,
                )
            }
        }

        val newHeading = HeadingStroke(id = headingId, boundingBox = boundsToConvert, strokes = storedStrokes, recognizedText = storedText, level = level)
        db.withTransaction {
            val dao = db.notebookDao()
            originalStrokeIds.forEach { dao.softDeleteById(it, deletedAt) }
            val now = System.currentTimeMillis()
            dao.insertHeadingSubtree(newHeading, layerId, 0, now, now)
        }

        // Invalidate any existing page snapshot — it predates the heading.
        // The next close will capture a fresh snapshot via captureSnapshot().
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            val erasedSet = originalStrokeIds.toSet()
            val updatedStrokes = drawingView.getStrokes().filter { it.id !in erasedSet }
            persistedStrokeIds.removeAll(erasedSet)

            val updatedHeadings = drawingView.getHeadings() + newHeading
            drawingView.loadHeadings(updatedHeadings)

            undoRedoManager.push(
                UndoRedoAction.HeadingCreated(
                    headingId         = headingId,
                    pageId            = pageId,
                    layerId           = layerId,
                    deletedAt         = deletedAt,
                    originalStrokeIds = originalStrokeIds,
                    embeddedStrokes   = storedStrokes,
                    recognizedText    = storedText,
                    level             = level,
                )
            )
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            val newSelection = setOf(headingId)
            val pad = 8f * resources.displayMetrics.density
            val selectionBox = RectF(boundsToConvert).also { it.inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(newSelection, selectionBox)
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newSelection)
            updateFloatingSelectionToolbar(selectionBox)
        }
    }

    /**
     * Convert the [selectedStrokes] lasso selection into a type="text" object.
     * [selectionBox] is the padded selection RectF used as the initial bounding box.
     * On recognition success the box is resized to fit the text; on failure it stays as-is.
     * Must be called on [Dispatchers.IO]; switches to Main for in-memory + UI updates.
     */
    private suspend fun convertLassoToText(
        selectedStrokes: List<LiveStroke>,
        selectionBox: RectF,
    ) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return

        val strokesToConvert = selectedStrokes.toList()
        var boundsToConvert  = RectF(selectionBox)

        // Attempt ML Kit recognition, same path as heading conversion.
        val recognizer = HandwritingRecognizerProvider.instance
        val rawResult: String = if (recognizer != null && recognizer.isReady()) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    recognizer.recognize(strokesToConvert, boundsToConvert) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        } else {
            HandwritingRecognizer.FALLBACK_TEXT
        }

        // Treat FALLBACK_TEXT ("unrecognized") as failure — store blank text, render strokes.
        val isRecognized = rawResult != HandwritingRecognizer.FALLBACK_TEXT
        val textForObject = if (isRecognized) rawResult else ""

        if (isRecognized) {
            // Resize bounding box to fit the recognised text (same approach as heading conversion).
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color    = android.graphics.Color.BLACK
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            }
            val pageW = drawingView.asView().width
            val (measuredW, measuredH) = withContext(Dispatchers.Default) {
                TextObjectRenderer.measure(textForObject, pageW, paint, resources.displayMetrics.density)
            }
            val objW = measuredW.toFloat().coerceAtMost(pageW.toFloat())
            val objH = measuredH.toFloat()
            val newLeft = boundsToConvert.left.coerceIn(0f, (pageW - objW).coerceAtLeast(0f))
            val newTop  = boundsToConvert.top
            boundsToConvert = RectF(newLeft, newTop, newLeft + objW, newTop + objH)
        }

        val deletedAt   = System.currentTimeMillis()
        val textId      = UUID.randomUUID().toString()
        val originalStrokeIds = strokesToConvert.map { it.id }

        // Retain embedded strokes ONLY when recognition failed — a recognized text object renders
        // its markdown and never reverts to strokes, so keeping them is dead weight. Fresh UUIDs for
        // the fallback copies (same pattern as heading conversion).
        val embeddedStrokes = if (isRecognized) null else strokesToConvert.map { stroke ->
            stroke.copy(id = UUID.randomUUID().toString(), points = stroke.points.map { PointF(it.x, it.y) })
        }

        val now      = System.currentTimeMillis()
        val textRow  = TextRender(id = textId, boundingBox = boundsToConvert, text = textForObject, strokes = embeddedStrokes)

        db.withTransaction {
            val dao = db.notebookDao()
            originalStrokeIds.forEach { dao.softDeleteById(it, deletedAt) }
            dao.insertTextSubtree(textRow, layerId, 0, now, now)
        }

        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            val erasedSet      = originalStrokeIds.toSet()
            val updatedStrokes = drawingView.getStrokes().filter { it.id !in erasedSet }
            persistedStrokeIds.removeAll(erasedSet)

            val newTextRender  = TextRender(id = textId, boundingBox = boundsToConvert, text = textForObject, strokes = embeddedStrokes)
            val updatedTexts   = drawingView.getTextObjects() + newTextRender
            drawingView.loadTextObjects(updatedTexts)

            undoRedoManager.push(
                UndoRedoAction.TextConverted(
                    textId            = textId,
                    pageId            = pageId,
                    layerId           = layerId,
                    deletedAt         = deletedAt,
                    originalStrokeIds = originalStrokeIds,
                    textRender        = newTextRender,
                )
            )
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val currentHeadings = drawingView.getHeadings()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            if (!isLassoMode) enterLassoMode()
            val pad = 8f * resources.displayMetrics.density
            val selBox = RectF(boundsToConvert).apply { inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(setOf(textId), selBox)
            selectedObjectIds.clear()
            selectedObjectIds.add(textId)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    /**
     * Convert [stroke] into a shape object using [result] from [ShapeRecognizer].
     * Soft-deletes the original stroke row (or skips DB delete if unpersisted),
     * inserts the shape row, and pushes [UndoRedoAction.ShapeCreated].
     * Must be called on [Dispatchers.IO]; switches to Main for in-memory + UI updates.
     */
    private suspend fun convertStrokeToShape(
        stroke: com.notesprout.android.data.LiveStroke,
        result: com.notesprout.android.notebook.ShapeRecognizer.Result,
    ) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        val density = resources.displayMetrics.density

        val shapeId = UUID.randomUUID().toString()
        val now     = System.currentTimeMillis()

        val shapeObj = com.notesprout.android.data.ShapeObject(
            type          = result.type,
            centerX       = result.centerX,
            centerY       = result.centerY,
            width         = result.width,
            height        = result.height,
            rotationDeg   = result.rotationDeg,
            strokeWidthDp = stroke.strokeWidth / density,
            aspectLocked  = result.aspectLocked,
            pointCount    = result.pointCount,
        )
        val shapeRender = com.notesprout.android.data.ShapeRender.from(shapeId, shapeObj, density)

        // Was the stroke already persisted to the DB?
        val wasInDb      = stroke.id in persistedStrokeIds
        val strokeDeletedAt = if (wasInDb) now else 0L

        db.withTransaction {
            val dao = db.notebookDao()
            if (wasInDb) dao.softDeleteById(stroke.id, now)
            dao.insertObject(shapeRender.toRow(layerId, 0, now, now, density))
        }
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            val erasedSet      = setOf(stroke.id)
            val updatedStrokes = drawingView.getStrokes().filter { it.id !in erasedSet }
            persistedStrokeIds.removeAll(erasedSet)

            val updatedShapes = drawingView.getShapeObjects() + shapeRender
            drawingView.loadShapeObjects(updatedShapes)

            undoRedoManager.push(
                UndoRedoAction.ShapeCreated(
                    shapeId        = shapeId,
                    pageId         = pageId,
                    layerId        = layerId,
                    deletedAt      = strokeDeletedAt,
                    originalStroke = stroke,
                    shape          = shapeRender,
                )
            )
            updateUndoRedoButtons()

            val templateBmp     = currentTemplateBitmap
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, currentHeadings, currentTexts, currentLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            if (!isLassoMode) enterLassoMode()
            // Shape created from pen → treat as a smart session so tap-outside returns to pen.
            isSmartLassoSession = true
            val pad = 8f * density
            val selBox = RectF(shapeRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(setOf(shapeId), selBox)
            selectedObjectIds.clear()
            selectedObjectIds.add(shapeId)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    /**
     * Persist a shape transform (resize/rotate/aspect toggle) and push the undo action.
     * Called on [Dispatchers.IO] via the [drawingView.onShapeTransformed] callback.
     */
    private suspend fun persistShapeTransform(
        before: com.notesprout.android.data.ShapeRender,
        after: com.notesprout.android.data.ShapeRender,
    ) {
        val db     = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val now    = System.currentTimeMillis()
        val density = resources.displayMetrics.density

        if (before.centerX == after.centerX && before.centerY == after.centerY &&
            before.width == after.width && before.height == after.height &&
            before.rotationDeg == after.rotationDeg && before.aspectLocked == after.aspectLocked
        ) return  // No change — nothing to persist.

        db.withTransaction {
            db.notebookDao().updateColumns(after.toRow("", 0, now, now, density))
        }
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            undoRedoManager.push(UndoRedoAction.ShapeTransformed(
                shapeId = after.id,
                pageId  = pageId,
                before  = before,
                after   = after,
            ))
            updateUndoRedoButtons()

            // Rebuild bitmap so the shape renders at its new geometry.
            val updatedShapes = drawingView.getShapeObjects().map { if (it.id == after.id) after else it }
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp     = currentTemplateBitmap
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, currentLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }

            // Re-show floating selection toolbar at the shape's new bounds if still in lasso mode.
            if (isLassoMode && after.id in selectedObjectIds) {
                val pad = 8f * density
                val selBox = RectF(after.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
        }
    }

    /**
     * Like [createLinkFromSelection] but for a strokes-only selection where the user chose to
     * convert to text first. Runs ML Kit recognition, builds a [TextRender] (with embedded stroke
     * copies for fallback rendering), then creates the link embedding the text object rather than
     * the raw strokes. The original strokes are soft-deleted in the same transaction as the link
     * insert. Must be called on [Dispatchers.IO]; switches to Main for ML Kit and UI updates.
     */
    private suspend fun createLinkFromStrokesConvertingToText(
        selectedStrokes: List<LiveStroke>,
        chrome: LinkChrome,
        target: LinkTarget,
    ) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        if (selectedStrokes.isEmpty()) return

        val density = resources.displayMetrics.density

        // Initial bounds from the strokes (same pad as convertLassoToText).
        val strokesToConvert = selectedStrokes.toList()
        var textBounds = RectF()
        strokesToConvert.forEach { textBounds.union(it.boundingBox) }
        val pad = 8f * density
        textBounds.inset(-pad, -pad)

        // ML Kit recognition — must happen on the main thread.
        val rawResult: String = if (HandwritingRecognizerProvider.instance?.isReady() == true) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    HandwritingRecognizerProvider.instance!!.recognize(strokesToConvert, textBounds) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
        } else {
            HandwritingRecognizer.FALLBACK_TEXT
        }

        val isRecognized = rawResult != HandwritingRecognizer.FALLBACK_TEXT

        // Let the user correct the ML Kit result before the link is finalized.
        val finalText: String = if (isRecognized) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val editView = androidx.appcompat.widget.AppCompatEditText(this@NotebookActivity).apply {
                        setText(rawResult)
                        setSelection(rawResult.length)
                        setTextColor(getColor(R.color.inkBlack))
                        setHintTextColor(getColor(R.color.inkLight))
                        textSize = 14f
                        val dp8 = (8 * resources.displayMetrics.density).toInt()
                        setPadding(dp8, dp8, dp8, dp8)
                        minLines = 2
                        maxLines = 6
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    val dp16 = (16 * resources.displayMetrics.density).toInt()
                    val container = android.widget.FrameLayout(this@NotebookActivity).apply {
                        setPadding(dp16, dp16 / 2, dp16, dp16 / 2)
                        addView(editView, android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        ))
                    }
                    val dialog = AlertDialog.Builder(this@NotebookActivity)
                        .setTitle("Edit recognized text")
                        .setView(container)
                        .setPositiveButton("OK") { _, _ ->
                            if (cont.isActive) cont.resume(editView.text?.toString() ?: rawResult)
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            if (cont.isActive) cont.resume(rawResult)
                        }
                        .setOnCancelListener { if (cont.isActive) cont.resume(rawResult) }
                        .create()
                    cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                    dialog.show()
                    dialog.window?.setElevation(0f)
                    dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                    editView.requestFocus()
                    editView.postDelayed({
                        ViewCompat.getWindowInsetsController(editView)
                            ?.show(WindowInsetsCompat.Type.ime())
                            ?: run {
                                val imm = getSystemService(InputMethodManager::class.java)
                                @Suppress("DEPRECATION")
                                imm.showSoftInput(editView, InputMethodManager.SHOW_IMPLICIT)
                            }
                    }, 100)
                }
            }
        } else ""

        if (finalText.isNotBlank()) {
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color    = android.graphics.Color.BLACK
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            }
            val pageW = drawingView.asView().width
            val (measuredW, measuredH) = withContext(Dispatchers.Default) {
                TextObjectRenderer.measure(finalText, pageW, paint, resources.displayMetrics.density)
            }
            val objW = measuredW.toFloat().coerceAtMost(pageW.toFloat())
            val objH = measuredH.toFloat()
            val newLeft = textBounds.left.coerceIn(0f, (pageW - objW).coerceAtLeast(0f))
            textBounds = RectF(newLeft, textBounds.top, newLeft + objW, textBounds.top + objH)
        }

        // Embedded stroke copies kept inside the TextRender for fallback rendering.
        val embeddedStrokes = strokesToConvert.map { stroke ->
            stroke.copy(id = UUID.randomUUID().toString(), points = stroke.points.map { PointF(it.x, it.y) })
        }
        val embeddedText = TextRender(
            id          = UUID.randomUUID().toString(),
            boundingBox = RectF(textBounds),
            text        = finalText,
            strokes     = embeddedStrokes,
        )

        // Link bounding box wraps the text object with breathing room + icon clearance.
        val linkContentPadPx = 6f * density
        val unionBox = RectF(textBounds).apply { inset(-linkContentPadPx, -linkContentPadPx) }
        if (chrome == LinkChrome.DOTTED_CHEVRON) unionBox.right += 17f * density

        val originalStrokeIds = strokesToConvert.map { it.id }
        val deletedAt = System.currentTimeMillis()
        val linkId    = UUID.randomUUID().toString()

        val newLink = LinkRender(linkId, RectF(unionBox), target, chrome,
            strokes = emptyList(), headings = emptyList(),
            textObjects = listOf(embeddedText), lines = emptyList())

        db.withTransaction {
            val dao = db.notebookDao()
            originalStrokeIds.forEach { dao.softDeleteById(it, deletedAt) }
            val now = System.currentTimeMillis()
            dao.insertLinkSubtree(newLink, layerId, 0, now, now, density)
        }
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            val strokeSet      = originalStrokeIds.toSet()
            val updatedStrokes = drawingView.getStrokes().filter { it.id !in strokeSet }
            persistedStrokeIds.removeAll(strokeSet)

            val updatedLinks = drawingView.getLinks() + newLink
            drawingView.loadLinks(updatedLinks)

            undoRedoManager.push(UndoRedoAction.LinkCreated(
                linkId             = linkId,
                pageId             = pageId,
                layerId            = layerId,
                deletedAt          = deletedAt,
                originalStrokeIds  = originalStrokeIds,
                originalHeadingIds = emptyList(),
                originalTextIds    = emptyList(),
                originalLineIds    = emptyList(),
                link               = newLink,
            ))
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(
                    updatedStrokes, templateBmp,
                    drawingView.getHeadings(), drawingView.getTextObjects(),
                    drawingView.getLineObjects(), updatedLinks,
                )
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            val selPad = 8f * density
            val selBox = RectF(unionBox).also { it.inset(-selPad, -selPad) }
            selectedObjectIds.clear()
            selectedObjectIds.add(linkId)
            drawingView.setLassoSelectedIds(setOf(linkId), selBox)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    /**
     * Wrap a heterogeneous lasso selection into a single `type = "link"` object, mirroring
     * [createHeadingFromStrokes] but holding any mix of strokes/headings/text/lines.
     *
     * [chrome] and [target] come from the Session 2 target picker. The held objects are captured as
     * fresh-UUID copies; the originals are soft-deleted in one transaction. Must be called on
     * [Dispatchers.IO]; switches to Main for UI updates.
     */
    private suspend fun createLinkFromSelection(
        selectedStrokes: List<LiveStroke>,
        selectedHeadings: List<HeadingStroke>,
        selectedTexts: List<TextRender>,
        selectedLines: List<LineRender>,
        selectedShapes: List<ShapeRender>,
        chrome: LinkChrome,
        target: LinkTarget,
    ) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        if (selectedStrokes.isEmpty() && selectedHeadings.isEmpty() && selectedTexts.isEmpty() &&
            selectedLines.isEmpty() && selectedShapes.isEmpty()) return

        val density = resources.displayMetrics.density

        // Union bounding box of the whole selection — the chrome is drawn around this.
        // Expand by LINK_CONTENT_PAD so the chrome has breathing room between it and the content.
        val unionBox = RectF()
        selectedStrokes.forEach { unionBox.union(it.boundingBox) }
        selectedHeadings.forEach { unionBox.union(it.boundingBox) }
        selectedTexts.forEach { unionBox.union(it.boundingBox) }
        selectedLines.forEach { unionBox.union(it.boundingBox) }
        selectedShapes.forEach { unionBox.union(it.boundingBox) }
        val linkContentPadPx = 6f * density
        unionBox.inset(-linkContentPadPx, -linkContentPadPx)
        // Extra right-side room for the link icon (14dp icon + 3dp inner margin).
        // The base 6dp pad already provides the 6dp content-to-icon gap the user specified.
        if (chrome == LinkChrome.DOTTED_CHEVRON) unionBox.right += 17f * density

        // Fresh-UUID embedded copies (so the held objects are independent of the originals).
        val embeddedStrokes = selectedStrokes.map { it.copy(id = UUID.randomUUID().toString(), points = it.points.map { p -> PointF(p.x, p.y) }) }
        val embeddedHeadings = selectedHeadings.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }
        val embeddedTexts = selectedTexts.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }
        val embeddedLineRenders = selectedLines.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }
        val embeddedShapes = selectedShapes.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }

        val originalStrokeIds  = selectedStrokes.map { it.id }
        val originalHeadingIds = selectedHeadings.map { it.id }
        val originalTextIds    = selectedTexts.map { it.id }
        val originalLineIds    = selectedLines.map { it.id }
        val originalShapeIds   = selectedShapes.map { it.id }

        val deletedAt = System.currentTimeMillis()
        val linkId    = UUID.randomUUID().toString()

        val newLink = LinkRender(linkId, RectF(unionBox), target, chrome, embeddedStrokes, embeddedHeadings, embeddedTexts, embeddedLineRenders, embeddedShapes)

        db.withTransaction {
            val dao = db.notebookDao()
            (originalStrokeIds + originalHeadingIds + originalTextIds + originalLineIds + originalShapeIds).forEach { dao.softDeleteById(it, deletedAt) }
            val now = System.currentTimeMillis()
            dao.insertLinkSubtree(newLink, layerId, 0, now, now, density)
        }
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            val strokeSet  = originalStrokeIds.toSet()
            val headingSet = originalHeadingIds.toSet()
            val textSet    = originalTextIds.toSet()
            val lineSet    = originalLineIds.toSet()
            val shapeSet   = originalShapeIds.toSet()

            val updatedStrokes  = drawingView.getStrokes().filter { it.id !in strokeSet }
            val updatedHeadings = drawingView.getHeadings().filter { it.id !in headingSet }
            val updatedTexts    = drawingView.getTextObjects().filter { it.id !in textSet }
            val updatedLines    = drawingView.getLineObjects().filter { it.id !in lineSet }
            val updatedShapes   = drawingView.getShapeObjects().filter { it.id !in shapeSet }
            val updatedLinks    = drawingView.getLinks() + newLink
            persistedStrokeIds.removeAll(strokeSet)

            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadShapeObjects(updatedShapes)
            drawingView.loadLinks(updatedLinks)

            undoRedoManager.push(UndoRedoAction.LinkCreated(
                linkId            = linkId,
                pageId            = pageId,
                layerId           = layerId,
                deletedAt         = deletedAt,
                originalStrokeIds = originalStrokeIds,
                originalHeadingIds = originalHeadingIds,
                originalTextIds   = originalTextIds,
                originalLineIds   = originalLineIds,
                originalShapeIds  = originalShapeIds,
                link              = newLink,
            ))
            updateUndoRedoButtons()

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, updatedLinks)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            val pad = 8f * density
            val selBox = RectF(unionBox).also { it.inset(-pad, -pad) }
            selectedObjectIds.clear()
            selectedObjectIds.add(linkId)
            drawingView.setLassoSelectedIds(setOf(linkId), selBox)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    /**
     * Remove a link, re-inserting its embedded held objects as their own live rows (fresh UUIDs).
     * The link row is soft-deleted. Must be called on [Dispatchers.IO];
     * switches to Main for UI updates.
     */
    private suspend fun removeLink(link: LinkRender) {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        val now     = System.currentTimeMillis()
        val density = resources.displayMetrics.density

        val restoredStrokes  = link.strokes.map { it.copy(id = UUID.randomUUID().toString(), points = it.points.map { p -> PointF(p.x, p.y) }) }
        val restoredHeadings = link.headings.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }
        val restoredTexts    = link.textObjects.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }
        val restoredLines    = link.lines.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }
        val restoredShapes   = link.shapes.map { it.copy(id = UUID.randomUUID().toString(), boundingBox = RectF(it.boundingBox)) }

        db.withTransaction {
            val dao = db.notebookDao()
            dao.softDeleteById(link.id, now)
            restoredStrokes.forEach { s ->
                dao.insertObject(NotebookObject(s.id, layerId, s.boundingBox.toBoundingBoxJson(), 0, now, now, null, "stroke", s.toStrokeData().toJson()))
            }
            restoredHeadings.forEach { h ->
                dao.insertHeadingSubtree(h, layerId, 0, now, now)
            }
            restoredTexts.forEach { t ->
                dao.insertTextSubtree(t, layerId, 0, now, now)
            }
            restoredLines.forEach { l ->
                dao.insertObject(l.toRow(layerId, 0, now, now, density))
            }
            restoredShapes.forEach { s ->
                dao.insertObject(s.toRow(layerId, 0, now, now, density))
            }
        }
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            undoRedoManager.push(UndoRedoAction.LinkRemoved(
                linkId             = link.id,
                pageId             = pageId,
                restoredStrokeIds  = restoredStrokes.map { it.id },
                restoredHeadingIds = restoredHeadings.map { it.id },
                restoredTextIds    = restoredTexts.map { it.id },
                restoredLineIds    = restoredLines.map { it.id },
                restoredShapeIds   = restoredShapes.map { it.id },
            ))
            updateUndoRedoButtons()

            val updatedLinks    = drawingView.getLinks().filter { it.id != link.id }
            val updatedStrokes  = drawingView.getStrokes() + restoredStrokes
            val updatedHeadings = drawingView.getHeadings() + restoredHeadings
            val updatedTexts    = drawingView.getTextObjects() + restoredTexts
            val updatedLines    = drawingView.getLineObjects() + restoredLines
            val updatedShapes   = drawingView.getShapeObjects() + restoredShapes
            restoredStrokes.forEach { persistedStrokeIds.add(it.id) }

            drawingView.loadLinks(updatedLinks)
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadShapeObjects(updatedShapes)
            drawingView.setStrokeListSilently(updatedStrokes)

            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, updatedLinks, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }

            // Select the restored objects so the user can act on them immediately.
            val selBox = RectF(link.boundingBox)
            val pad = 8f * density
            selBox.inset(-pad, -pad)
            val restoredIds = (restoredStrokes.map { it.id } + restoredHeadings.map { it.id } + restoredTexts.map { it.id } + restoredLines.map { it.id } + restoredShapes.map { it.id }).toSet()
            selectedObjectIds.clear()
            selectedObjectIds.addAll(restoredIds)
            drawingView.setLassoSelectedIds(restoredIds, selBox)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    /**
     * Edit a link's chrome and/or target in place (Session 2). Rewrites the `data` column, swaps the
     * in-memory [LinkRender] so the chrome redraws without a full reload, and pushes a [LinkEdited]
     * action whose undo/redo flips the row's data back/forward. No-op when nothing actually changed.
     * Must be called on [Dispatchers.IO]; switches to Main for UI updates.
     */
    private suspend fun updateLink(
        linkId: String,
        newChrome: LinkChrome,
        newTarget: LinkTarget,
        newText: String? = null,
    ) {
        val db     = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val dao    = db.notebookDao()
        val density = resources.displayMetrics.density
        val link   = dao.loadLinkSubtree(linkId, density) ?: return
        val oldChrome = link.chrome
        val oldTarget = link.target

        // Apply text update only when the link embeds exactly one text object and no other content.
        val updatedTextObjects = if (newText != null && link.textObjects.size == 1
                && link.strokes.isEmpty() && link.headings.isEmpty() && link.lines.isEmpty()) {
            listOf(link.textObjects[0].copy(text = newText))
        } else link.textObjects

        val textChanged = updatedTextObjects !== link.textObjects
        if (oldChrome == newChrome && oldTarget == newTarget && !textChanged) return

        val updatedLink = link.copy(chrome = newChrome, target = newTarget, textObjects = updatedTextObjects)
        val now = System.currentTimeMillis()
        db.withTransaction { dao.replaceLinkSubtree(updatedLink, now, density) }
        noteContentEdit(db, pageId)

        withContext(Dispatchers.Main) {
            undoRedoManager.push(UndoRedoAction.LinkEdited(
                linkId    = linkId,
                pageId    = pageId,
                oldChrome = oldChrome,
                oldTarget = oldTarget,
                newChrome = newChrome,
                newTarget = newTarget,
            ))
            updateUndoRedoButtons()

            val updatedLinks = drawingView.getLinks().map {
                if (it.id == linkId) it.copy(
                    chrome      = newChrome,
                    target      = newTarget,
                    textObjects = if (textChanged) updatedTextObjects else it.textObjects,
                ) else it
            }
            drawingView.loadLinks(updatedLinks)

            val strokes  = drawingView.getStrokes()
            val headings = drawingView.getHeadings()
            val texts    = drawingView.getTextObjects()
            val lines    = drawingView.getLineObjects()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, headings, texts, lines, updatedLinks)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
        }
    }

    private fun showHeadingTextEditDialog(heading: HeadingStroke) {
        val dialogBinding = DialogEditHeadingTextBinding.inflate(layoutInflater)
        // Strip the markdown prefix (e.g. "# ") so the user sees only the words.
        val displayText = HeadingObject.stripHeadingPrefix(heading.recognizedText ?: "")
        dialogBinding.editHeadingText.setText(displayText)
        val headingLevel = heading.level
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Heading")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editHeadingText.windowToken, 0)
                val newText = dialogBinding.editHeadingText.text?.toString()?.trim().orEmpty()
                if (newText.isNotBlank()) {
                    updateHeadingText(heading, newText, headingLevel)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                val imm = getSystemService(InputMethodManager::class.java)
                imm.hideSoftInputFromWindow(dialogBinding.editHeadingText.windowToken, 0)
            }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)

        dialogBinding.editHeadingText.requestFocus()
        dialogBinding.editHeadingText.selectAll()
        dialogBinding.editHeadingText.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editHeadingText)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editHeadingText, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    private fun updateHeadingText(heading: HeadingStroke, newText: String, level: Int = heading.level) {
        val db = soilDatabase ?: return
        // previousText is already stored with the markdown prefix (e.g. "# Chapter One").
        val previousText = heading.recognizedText
        // Re-apply the heading prefix so stored text stays prefixed (level and prefix always agree).
        val prefixedText = HeadingObject.applyLevel(newText, level) ?: newText
        val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
        }
        val paddingPx = 8f * resources.displayMetrics.density
        val pageWidth = resources.displayMetrics.widthPixels
        // Measure using the prefixed text so per-level sizing (H1/H2/H3 via MarkdownParser) is correct.
        val (measuredW, measuredH) = TextObjectRenderer.measure(prefixedText, pageWidth, textPaint, resources.displayMetrics.density, singleLine = true)
        val newBox = RectF(
            heading.boundingBox.left,
            heading.boundingBox.top,
            heading.boundingBox.left + measuredW + 2f * paddingPx,
            heading.boundingBox.top + measuredH + 2f * paddingPx,
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val row = db.notebookDao().getObjectById(heading.id) ?: return@withContext
                // Persist prefixed text and preserve level so the two always agree.
                val updated = (db.notebookDao().assembleHeadingResolved(row) ?: return@withContext)
                    .copy(recognizedText = prefixedText, level = level, boundingBox = newBox)
                db.notebookDao().replaceHeadingSubtree(updated, System.currentTimeMillis())
            }
            // Personalization: a human just corrected this heading's text — upgrade the pair
            // captured at conversion time (no-ops when none exists). Unprefixed text.
            if (com.notesprout.android.recognition.personal.TrainingPairRepository.captureAllowed(
                    this@NotebookActivity)
            ) {
                NotesproutApplication.appScope.launch {
                    com.notesprout.android.recognition.personal.TrainingPairRepository.confirmByObjectId(
                        context = applicationContext,
                        objectId = heading.id,
                        newLabel = newText,
                        source = com.notesprout.android.recognition.personal.TrainingPairRepository.SOURCE_HEADING_CORRECTION,
                    )
                }
            }
            val updatedHeadings = drawingView.getHeadings().map { h ->
                if (h.id == heading.id) h.copy(recognizedText = prefixedText, boundingBox = newBox, level = level) else h
            }
            drawingView.loadHeadings(updatedHeadings)
            val strokes = drawingView.getStrokes()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
            val pad = 8f * resources.displayMetrics.density
            val selBox = RectF(newBox).also { it.inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                updateFloatingSelectionToolbar(selBox)
            }
            undoRedoManager.push(
                UndoRedoAction.HeadingTextEdited(
                    headingId    = heading.id,
                    pageId       = currentPageId,
                    previousText = previousText,
                    // Store prefixed text in the undo action so undo/redo handlers that write
                    // recognizedText = targetText directly receive correctly prefixed text.
                    newText      = prefixedText,
                )
            )
            updateUndoRedoButtons()
        }
    }

    /**
     * Change [heading]'s level to [newLevel], persisting to DB and rebuilding the canvas.
     *
     * Modeled on [updateHeadingText]: runs on the main dispatcher; uses withContext(IO) for DB/bitmap.
     * The stored text is re-prefixed to match the new level; for stroke-only headings
     * (recognizedText == null) the bounding box is kept unchanged.
     */
    private fun changeHeadingLevel(heading: HeadingStroke, newLevel: Int) {
        val db = soilDatabase ?: return
        val previousLevel = heading.level
        val previousText  = heading.recognizedText
        val previousBox   = RectF(heading.boundingBox)
        val newText       = HeadingObject.applyLevel(heading.recognizedText, newLevel)   // null stays null

        // Compute new bounding box: re-measure for text headings; keep existing box for stroke headings.
        val newBox: RectF
        if (newText != null) {
            val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            }
            val paddingPx = 8f * resources.displayMetrics.density
            val pageWidth = resources.displayMetrics.widthPixels
            val (measuredW, measuredH) = TextObjectRenderer.measure(newText, pageWidth, textPaint, resources.displayMetrics.density, singleLine = true)
            newBox = RectF(
                heading.boundingBox.left,
                heading.boundingBox.top,
                heading.boundingBox.left + measuredW + 2f * paddingPx,
                heading.boundingBox.top  + measuredH + 2f * paddingPx,
            )
        } else {
            newBox = RectF(heading.boundingBox)
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val row     = db.notebookDao().getObjectById(heading.id) ?: return@withContext
                val updated = (db.notebookDao().assembleHeadingResolved(row) ?: return@withContext)
                    .copy(recognizedText = newText, level = newLevel, boundingBox = newBox)
                db.notebookDao().replaceHeadingSubtree(updated, System.currentTimeMillis())
            }
            val updatedHeadings = drawingView.getHeadings().map { h ->
                if (h.id == heading.id) h.copy(recognizedText = newText, boundingBox = newBox, level = newLevel) else h
            }
            drawingView.loadHeadings(updatedHeadings)
            val strokes     = drawingView.getStrokes()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
            val pad    = 8f * resources.displayMetrics.density
            val selBox = RectF(newBox).also { it.inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                updateFloatingSelectionToolbar(selBox)
            }
            undoRedoManager.push(
                UndoRedoAction.HeadingLevelChanged(
                    headingId     = heading.id,
                    pageId        = currentPageId,
                    previousLevel = previousLevel,
                    newLevel      = newLevel,
                    previousText  = previousText,
                    newText       = newText,
                    previousBox   = previousBox,
                    newBox        = newBox,
                )
            )
            updateUndoRedoButtons()
        }
    }

    /** Update btnLasso icon: ic_lasso_clipboard when clipboard has content, ic_lasso otherwise. */
    private fun updateLassoButtonIcon() {
        binding.btnLasso.setImageResource(
            if (NotesproutClipboard.hasContent()) R.drawable.ic_lasso_clipboard
            else R.drawable.ic_lasso
        )
    }

    // ── Floating toolbar helpers ──────────────────────────────────────────────

    private val selectedHeadings: List<HeadingStroke>
        get() = drawingView.getHeadings().filter { it.id in drawingView.lassoSelectedIds }

    private val selectedStrokes: List<LiveStroke>
        get() = drawingView.getStrokes().filter { it.id in drawingView.lassoSelectedIds }

    // Selection captured when the heading-type submenu is opened in convert mode (S3), used when the
    // user picks a level. Read on the H1/H2/H3 button taps; cleared implicitly when the submenu hides.
    private var pendingHeadingStrokes: List<LiveStroke> = emptyList()
    private var pendingHeadingBox: RectF? = null

    // "Convert to Shape" lasso button: cached recognizer result for the currently-selected stroke.
    private var shapeConvertJob: Job? = null
    private var shapeConvertStroke: LiveStroke? = null
    private var shapeConvertResult: com.notesprout.android.notebook.ShapeRecognizer.Result? = null

    // S4: disambiguate shared H1/H2/H3 buttons between convert-from-strokes (S3) and change-level (S4).
    private enum class HeadingSubmenuMode { CONVERT, CHANGE }
    private var headingSubmenuMode = HeadingSubmenuMode.CONVERT
    // S4: the heading whose level is being changed; set when opening the submenu in CHANGE mode.
    private var pendingChangeHeading: HeadingStroke? = null

    /**
     * Position and show the floating selection toolbar relative to [selectionBox].
     * Default: centered below the box with an 8dp gap.
     * Fallback: centered above when the bottom edge would clip off screen.
     */
    private fun updateFloatingSelectionToolbar(selectionBox: RectF) {
        // The selection changed — drop any stale heading-type submenu.
        hideHeadingTypeSubmenu()
        val selHeadings = selectedHeadings
        val selStrokes  = selectedStrokes
        val selTextObjects = drawingView.getTextObjects().filter { it.id in drawingView.lassoSelectedIds }
        val selLines = drawingView.getLineObjects().filter { it.id in drawingView.lassoSelectedIds }
        val selLinks = drawingView.getLinks().filter { it.id in drawingView.lassoSelectedIds }
        val selShapes = drawingView.getShapeObjects().filter { it.id in drawingView.lassoSelectedIds }
        val selectionIsSingleHeading = selHeadings.size == 1 && selStrokes.isEmpty()
        val selectionIsPureStrokes   = selStrokes.isNotEmpty() && selHeadings.isEmpty()
        val selectionIsNonStrokeGroup = selStrokes.isEmpty() && (selHeadings.size + selTextObjects.size + selLines.size) >= 2
        // A pure-stroke selection is the only one that can be converted to a heading / text
        // object. Warm the recognizer now so its cold load overlaps the user's next taps.
        if (selectionIsPureStrokes) {
            com.notesprout.android.recognition.HandwritingRecognizerProvider.warmUpActive()
        }
        binding.btnMakeHeading.visibility  = if (selectionIsPureStrokes)   View.VISIBLE else View.GONE
        // S4: btnHeadingMenu opens the submenu in CHANGE mode for a single heading (change level).
        binding.btnHeadingMenu.visibility  = if (selectionIsSingleHeading) View.VISIBLE else View.GONE
        binding.headingDivider.visibility =
            if (selectionIsPureStrokes || selectionIsSingleHeading) View.VISIBLE else View.GONE
        binding.btnConvertText.visibility   = if (selectionIsPureStrokes) View.VISIBLE else View.GONE
        binding.textConvertDivider.visibility = if (selectionIsPureStrokes) View.VISIBLE else View.GONE
        // "Convert to Shape": show only for a single stroke with no other objects selected.
        // Run recognition on a background thread; reveal the button only if a shape is detected.
        val isSingleStrokeOnly = selStrokes.size == 1
            && selHeadings.isEmpty() && selTextObjects.isEmpty()
            && selLines.isEmpty() && selLinks.isEmpty() && selShapes.isEmpty()
            && drawingView.lassoSelectedIds.size == 1
        shapeConvertJob?.cancel()
        if (isSingleStrokeOnly) {
            val stroke = selStrokes.first()
            val density = resources.displayMetrics.density
            // Hide until recognition completes (or re-use cached result for the same stroke).
            if (shapeConvertStroke?.id != stroke.id) {
                binding.btnConvertShape.visibility  = View.GONE
                binding.shapeConvertDivider.visibility = View.GONE
                shapeConvertStroke = stroke
                shapeConvertResult = null
                shapeConvertJob = lifecycleScope.launch(Dispatchers.IO) {
                    val result = com.notesprout.android.notebook.ShapeRecognizer.recognize(stroke.points, density)
                    withContext(Dispatchers.Main) {
                        if (shapeConvertStroke?.id == stroke.id) {
                            shapeConvertResult = result
                            val visible = if (result != null) View.VISIBLE else View.GONE
                            binding.btnConvertShape.visibility  = visible
                            binding.shapeConvertDivider.visibility = visible
                        }
                    }
                }
            }
            // If the same stroke is already recognized, button visibility is already correct.
        } else {
            shapeConvertStroke = null
            shapeConvertResult = null
            binding.btnConvertShape.visibility  = View.GONE
            binding.shapeConvertDivider.visibility = View.GONE
        }
        binding.alignDivider.visibility = if (selectionIsNonStrokeGroup) View.VISIBLE else View.GONE
        binding.btnAlignLeft.visibility = if (selectionIsNonStrokeGroup) View.VISIBLE else View.GONE
        binding.btnAlignTop.visibility  = if (selectionIsNonStrokeGroup) View.VISIBLE else View.GONE
        // Link buttons (A6): "add" when the selection has no link; "remove" when it is exactly one
        // link; nothing when a link is mixed with other objects (or more than one link is selected).
        val anyNonLinkSelected = selStrokes.isNotEmpty() || selHeadings.isNotEmpty() || selTextObjects.isNotEmpty() || selLines.isNotEmpty()
        val canAddLink = selLinks.isEmpty() && anyNonLinkSelected
        val selectionIsSingleLink = selLinks.size == 1 && !anyNonLinkSelected
        binding.btnLink.visibility     = if (canAddLink) View.VISIBLE else View.GONE
        binding.btnLinkEdit.visibility = if (selectionIsSingleLink) View.VISIBLE else View.GONE
        binding.btnUnlink.visibility   = if (selectionIsSingleLink) View.VISIBLE else View.GONE
        binding.linkDivider.visibility = if (canAddLink || selectionIsSingleLink) View.VISIBLE else View.GONE
        binding.floatingSelectionToolbar.visibility = View.VISIBLE
        binding.floatingSelectionToolbar.post {
            positionPopover(binding.floatingSelectionToolbar, selectionBox)
        }
    }

    /**
     * Place [view] (a popover/toolbar) relative to [selectionBox]: centered below the box with an
     * 8dp gap, falling back to above when the bottom edge would clip, then clamped to the safe area
     * so it stays clear of the main toolbar on whichever edge that bar is anchored. [view] must
     * already be measured (call from a `post {}` after making it visible).
     */
    private fun positionPopover(view: View, selectionBox: RectF) {
        val viewW   = view.measuredWidth.toFloat()
        val viewH   = view.measuredHeight.toFloat()
        val screenW = binding.root.width.toFloat()
        val screenH = binding.root.height.toFloat()
        val dpGap   = 8f * resources.displayMetrics.density
        // Keep clear of the main bar, whichever edge it's anchored to. Use the fixed bar thickness,
        // not drawingToolbar.height — a vertical bar's height is the full screen, which would push
        // the safe area off-screen.
        val barThick = toolbarLayoutManager.barThickness().toFloat()
        val place    = toolbarConfig.placement
        // Popovers are tappable, so they never enter the top guard band either. A top bar sits below
        // the guard, so clearing it means clearing guard + thickness.
        val guard    = toolbarLayoutManager.topGuard().toFloat()
        val minY     = if (place == ToolbarPlacement.TOP) guard + barThick + dpGap else guard + dpGap
        val maxY     = if (place == ToolbarPlacement.BOTTOM) screenH - barThick - viewH - dpGap
                       else screenH - viewH
        val minX     = if (place == ToolbarPlacement.LEFT)   barThick + dpGap else 0f
        val maxX     = if (place == ToolbarPlacement.RIGHT)  screenW - barThick - viewW - dpGap
                       else screenW - viewW

        var x = selectionBox.centerX() - viewW / 2f
        var y = selectionBox.bottom + dpGap

        // Fallback: above the selection box if clipped at the bottom.
        if (y + viewH > screenH) {
            y = selectionBox.top - dpGap - viewH
        }
        // Clamp to the safe area, clear of the main bar on whichever edge it sits.
        y = y.coerceIn(minY, maxY.coerceAtLeast(minY))
        x = x.coerceIn(minX, maxX.coerceAtLeast(minX))

        view.x = x
        view.y = y
    }

    private fun hideFloatingSelectionToolbar() {
        binding.floatingSelectionToolbar.visibility = View.GONE
        hideHeadingTypeSubmenu()
    }

    private fun hideHeadingTypeSubmenu() {
        binding.headingTypeSubmenu.visibility = View.GONE
        // Clear any level-highlight backgrounds when the submenu is dismissed.
        applyHeadingLevelHighlight(0)
    }

    /**
     * Highlight the H1/H2/H3 button matching [activeLevel] with [bg_heading_type_selected],
     * and clear the background of the other two. Pass [activeLevel] = 0 to clear all three.
     */
    private fun applyHeadingLevelHighlight(activeLevel: Int) {
        val selectedBg = if (activeLevel in 1..3)
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_heading_type_selected)
        else null
        binding.btnHeadingH1.background = if (activeLevel == 1) selectedBg else null
        binding.btnHeadingH2.background = if (activeLevel == 2) selectedBg else null
        binding.btnHeadingH3.background = if (activeLevel == 3) selectedBg else null
    }

    private fun updateSnapToggleIcon() {
        binding.btnSnapToggle.setImageResource(
            if (isSnapEnabled) R.drawable.ic_snap_off else R.drawable.ic_snap_on
        )
    }

    /**
     * Align and distribute all selected non-stroke objects.
     *
     * [alignVertical] = true  → align left edges to selection bbox left, distribute vertically.
     * [alignVertical] = false → align top edges to selection bbox top, distribute horizontally.
     *
     * Undo/redo reuses [UndoRedoAction.StrokesMoved] with empty stroke lists.
     */
    private suspend fun performAlign(alignVertical: Boolean) {
        val db     = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        val ids             = drawingView.lassoSelectedIds
        val origHeadings    = drawingView.getHeadings().filter { it.id in ids }
        val origTextObjects = drawingView.getTextObjects().filter { it.id in ids }
        val origLines       = drawingView.getLineObjects().filter { it.id in ids }
        if (origHeadings.size + origTextObjects.size + origLines.size < 2) return

        // Union bounding box of the entire selection.
        val selBbox = RectF()
        origHeadings.forEach { selBbox.union(it.boundingBox) }
        origTextObjects.forEach { selBbox.union(it.boundingBox) }
        origLines.forEach { selBbox.union(it.boundingBox) }

        // Unified items for sorting — (id, current bbox).
        data class AlignItem(val id: String, val bbox: RectF)
        val items = origHeadings.map { AlignItem(it.id, RectF(it.boundingBox)) } +
                    origTextObjects.map { AlignItem(it.id, RectF(it.boundingBox)) } +
                    origLines.map { AlignItem(it.id, RectF(it.boundingBox)) }

        val sorted = if (alignVertical) items.sortedBy { it.bbox.centerY() }
                     else               items.sortedBy { it.bbox.centerX() }

        // Compute new bounding box for each item.
        val newBboxMap = mutableMapOf<String, RectF>()
        if (alignVertical) {
            val sumHeights = sorted.sumOf { it.bbox.height().toDouble() }.toFloat()
            val gap = if (sorted.size > 1) (selBbox.height() - sumHeights) / (sorted.size - 1) else 0f
            var y = selBbox.top
            for (item in sorted) {
                newBboxMap[item.id] = RectF(selBbox.left, y, selBbox.left + item.bbox.width(), y + item.bbox.height())
                y += item.bbox.height() + gap
            }
        } else {
            val sumWidths = sorted.sumOf { it.bbox.width().toDouble() }.toFloat()
            val gap = if (sorted.size > 1) (selBbox.width() - sumWidths) / (sorted.size - 1) else 0f
            var x = selBbox.left
            for (item in sorted) {
                newBboxMap[item.id] = RectF(x, selBbox.top, x + item.bbox.width(), selBbox.top + item.bbox.height())
                x += item.bbox.width() + gap
            }
        }

        val movedHeadings    = origHeadings.map { h -> h.copy(boundingBox = newBboxMap[h.id] ?: h.boundingBox) }
        val movedTextObjects = origTextObjects.map { t -> t.copy(boundingBox = newBboxMap[t.id] ?: t.boundingBox) }
        val movedLines = origLines.map { line ->
            val newBbox = newBboxMap[line.id] ?: line.boundingBox
            val dx = newBbox.left - line.boundingBox.left
            val dy = newBbox.top  - line.boundingBox.top
            line.copy(
                boundingBox = newBbox,
                startX = line.startX + dx, startY = line.startY + dy,
                endX   = line.endX   + dx, endY   = line.endY   + dy,
            )
        }

        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            db.withTransaction {
                for (heading in movedHeadings) {
                    db.notebookDao().replaceHeadingSubtree(heading, now)
                }
                for (textObj in movedTextObjects) {
                    db.notebookDao().replaceTextSubtree(textObj, now)
                }
                for (lineObj in movedLines) {
                    db.notebookDao().updateColumns(lineObj.toRow("", 0, now, now, resources.displayMetrics.density))
                }
            }
            noteContentEdit(db, pageId)
        }

        // Update in-memory state.
        val movedHeadingIds = movedHeadings.associateBy { it.id }
        val movedTextIds    = movedTextObjects.associateBy { it.id }
        val movedLineIds    = movedLines.associateBy { it.id }
        val allHeadings = drawingView.getHeadings().map { movedHeadingIds[it.id] ?: it }
        val allTexts    = drawingView.getTextObjects().map { movedTextIds[it.id] ?: it }
        val allLines    = drawingView.getLineObjects().map { movedLineIds[it.id] ?: it }
        drawingView.loadHeadings(allHeadings)
        drawingView.loadTextObjects(allTexts)
        drawingView.loadLineObjects(allLines)

        // Rebuild render bitmap off-thread.
        val strokes     = drawingView.getStrokes()
        val templateBmp = currentTemplateBitmap
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, templateBmp, allHeadings, allTexts, allLines)
        }
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
        } else {
            drawingView.loadStrokes(strokes)
        }

        // Refresh selection overlay and floating toolbar.
        val newSelBox = RectF()
        movedHeadings.forEach { newSelBox.union(it.boundingBox) }
        movedTextObjects.forEach { newSelBox.union(it.boundingBox) }
        movedLines.forEach { newSelBox.union(it.boundingBox) }
        val pad = 8f * resources.displayMetrics.density
        newSelBox.inset(-pad, -pad)
        drawingView.setLassoOverlay(null, newSelBox)
        updateFloatingSelectionToolbar(newSelBox)

        undoRedoManager.push(
            UndoRedoAction.StrokesMoved(
                pageId              = pageId,
                originalStrokes     = emptyList(),
                movedStrokes        = emptyList(),
                originalHeadings    = origHeadings,
                movedHeadings       = movedHeadings,
                originalTextObjects = origTextObjects,
                movedTextObjects    = movedTextObjects,
                originalLineObjects = origLines,
                movedLineObjects    = movedLines,
            )
        )
        updateUndoRedoButtons()
    }

    /** Compute and store btnLasso's bounds in root-view coordinates. The lasso popup grows from
     *  whichever edge of the button faces the canvas, away from the bar. */
    private fun computeLassoToolbarAnchor() {
        val btnLoc  = IntArray(2).also { binding.btnLasso.getLocationOnScreen(it) }
        val rootLoc = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        val left = (btnLoc[0] - rootLoc[0]).toFloat()
        val top  = (btnLoc[1] - rootLoc[1]).toFloat()
        lassoToolbarAnchor = RectF(
            left, top,
            left + binding.btnLasso.width,
            top + binding.btnLasso.height,
        )
    }

    /**
     * Position and show the lasso popup anchored to btnLasso, growing away from the bar: below a top
     * bar, above a bottom bar, right of a left bar, left of a right bar. The popup is centred on the
     * button along the bar's main axis and clamped to the screen.
     */
    private fun updateLassoPopupToolbar() {
        val btn = lassoToolbarAnchor ?: run { computeLassoToolbarAnchor(); lassoToolbarAnchor } ?: return
        val dpGap  = 8f * resources.displayMetrics.density
        binding.lassoPopupToolbar.visibility = View.VISIBLE
        binding.lassoPopupToolbar.post {
            val w = binding.lassoPopupToolbar.measuredWidth.toFloat()
            val h = binding.lassoPopupToolbar.measuredHeight.toFloat()
            val screenW = binding.root.width.toFloat()
            val screenH = binding.root.height.toFloat()
            val (px, py) = when (toolbarConfig.placement) {
                ToolbarPlacement.LEFT ->
                    (btn.right + dpGap) to (btn.centerY() - h / 2f)
                ToolbarPlacement.RIGHT ->
                    (btn.left - dpGap - w) to (btn.centerY() - h / 2f)
                ToolbarPlacement.BOTTOM ->
                    (btn.centerX() - w / 2f) to (btn.top - dpGap - h)
                else ->
                    (btn.centerX() - w / 2f) to (btn.bottom + dpGap)
            }
            val guard = toolbarLayoutManager.topGuard().toFloat()
            binding.lassoPopupToolbar.x = px.coerceIn(0f, (screenW - w).coerceAtLeast(0f))
            binding.lassoPopupToolbar.y = py.coerceIn(guard, (screenH - h).coerceAtLeast(guard))
        }
    }

    private fun hideLassoPopupToolbar() {
        binding.lassoPopupToolbar.visibility = View.GONE
    }

    private fun showShapeInsertToolbar() {
        val btnLoc  = IntArray(2).also { binding.btnInsertShape.getLocationOnScreen(it) }
        val rootLoc = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        val left = (btnLoc[0] - rootLoc[0]).toFloat()
        val top  = (btnLoc[1] - rootLoc[1]).toFloat()
        val btn = RectF(left, top, left + binding.btnInsertShape.width, top + binding.btnInsertShape.height)
        val dpGap = 8f * resources.displayMetrics.density
        binding.shapeInsertToolbar.visibility = View.VISIBLE
        binding.btnInsertShape.isSelected = true
        binding.shapeInsertToolbar.post {
            val w = binding.shapeInsertToolbar.measuredWidth.toFloat()
            val h = binding.shapeInsertToolbar.measuredHeight.toFloat()
            val screenW = binding.root.width.toFloat()
            val screenH = binding.root.height.toFloat()
            val (px, py) = when (toolbarConfig.placement) {
                ToolbarPlacement.LEFT ->
                    (btn.right + dpGap) to (btn.centerY() - h / 2f)
                ToolbarPlacement.RIGHT ->
                    (btn.left - dpGap - w) to (btn.centerY() - h / 2f)
                ToolbarPlacement.BOTTOM ->
                    (btn.centerX() - w / 2f) to (btn.top - dpGap - h)
                else ->
                    (btn.centerX() - w / 2f) to (btn.bottom + dpGap)
            }
            val guard = toolbarLayoutManager.topGuard().toFloat()
            binding.shapeInsertToolbar.x = px.coerceIn(0f, (screenW - w).coerceAtLeast(0f))
            binding.shapeInsertToolbar.y = py.coerceIn(guard, (screenH - h).coerceAtLeast(guard))
            // Update the BOOX pen exclusion zone now that the toolbar is positioned and measured.
            pushToolbarExclusion()
        }
    }

    private fun hideShapeInsertToolbar() {
        binding.shapeInsertToolbar.visibility = View.GONE
        binding.btnInsertShape.isSelected = false
        // Restore the pen exclusion zone to the toolbar only (no longer need to cover the popup).
        pushToolbarExclusion()
    }

    // ── Pen colour ───────────────────────────────────────────────────────────

    private fun togglePenColorPanel() {
        if (penColorPanel.isVisible) {
            hidePenColorPanel()
        } else {
            drawingView.releaseRender()   // flush the EPD frame so the panel is visible on e-ink
            penColorPanel.show(
                PenColorPreferences.load(this),
                PenColorPreferences.loadPalette(this),
                PenColorPreferences.loadSlots(this),
            )
        }
    }

    private fun hidePenColorPanel() {
        if (!penColorPanel.isVisible) return
        penColorPanel.hide()   // pushes the exclusion rect via onVisibilityChanged
    }

    /**
     * Persist the chosen ink. Arming the drawing view and re-tinting the button happen in
     * [penColorListener], so this host and every other live one react through one path.
     */
    private fun applyPenColor(hex: String) {
        PenColorPreferences.save(this, hex)
    }

    /**
     * Reacts to the global ink changing — from this surface or any other live one. The overlapping
     * surfaces (a sticky note or scratch pad floating over a notebook) are the reason this exists:
     * without it the host underneath kept showing a stale pen tint until it was reopened.
     */
    private val penColorListener: (String) -> Unit = { hex ->
        drawingView.setPenColor(hex)
        applyPenTintToButton()
    }

    private fun applyPenTintToButton() {
        PenColorPanelController.applyPenTint(binding.btnPen, PenColorPreferences.load(this))
    }

    /**
     * Mix a colour into custom slot [index]. Slots are positional and never shuffle, so a colour
     * stays exactly where the user put it — the whole point of slots over a recents list.
     */
    private fun showCustomColorDialog(index: Int, initial: String) {
        drawingView.releaseRender()   // flush the EPD frame so the dialog paints cleanly
        CustomColorDialog(
            context = this,
            initial = initial,
            onChosen = { hex ->
                PenColorPreferences.saveSlot(this, index, hex)
                applyPenColor(hex)
            },
        ).show()
    }

    private fun insertShape(type: com.notesprout.android.data.ShapeType) {
        hideShapeInsertToolbar()
        closeOverflowMenu()
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf  { it.isNotEmpty() } ?: return
        val pageW   = drawingView.asView().width.takeIf  { it > 0 } ?: return
        val pageH   = drawingView.asView().height.takeIf { it > 0 } ?: return
        val density = resources.displayMetrics.density

        lifecycleScope.launch {
            val isOpenShape = type == com.notesprout.android.data.ShapeType.LINE
                || type == com.notesprout.android.data.ShapeType.ARROW
                || type == com.notesprout.android.data.ShapeType.ARCH

            val width: Float
            val height: Float
            val aspectLocked: Boolean

            if (isOpenShape) {
                val openWidth = pageW * 0.5f
                width = openWidth
                height = when (type) {
                    com.notesprout.android.data.ShapeType.ARCH -> openWidth * 0.5f
                    else -> 1f
                }
                aspectLocked = false
            } else {
                val closedSize = STICKY_NOTE_ICON_SIZE_DP * density
                width = closedSize
                height = closedSize
                aspectLocked = type == com.notesprout.android.data.ShapeType.ELLIPSE
                    || type == com.notesprout.android.data.ShapeType.RECTANGLE
                    || type == com.notesprout.android.data.ShapeType.STAR
            }

            val cx = pageW / 2f
            val cy = pageH / 2f

            val shapeObj = com.notesprout.android.data.ShapeObject(
                type          = type,
                centerX       = cx,
                centerY       = cy,
                width         = width,
                height        = height,
                rotationDeg   = 0f,
                strokeWidthDp = 2f,
                aspectLocked  = aspectLocked,
                pointCount    = 5,
            )
            val shapeId     = UUID.randomUUID().toString()
            val now         = System.currentTimeMillis()
            val shapeRender = com.notesprout.android.data.ShapeRender.from(shapeId, shapeObj, density)

            withContext(Dispatchers.IO) {
                db.notebookDao().insertObject(shapeRender.toRow(layerId, 0, now, now, density))
                noteContentEdit(db, pageId)
            }

            val updatedShapes = drawingView.getShapeObjects() + shapeRender
            drawingView.loadShapeObjects(updatedShapes)

            val templateBmp     = currentTemplateBitmap
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, currentLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }

            undoRedoManager.push(UndoRedoAction.ShapeInserted(
                shapeId = shapeId,
                pageId  = pageId,
                layerId = layerId,
                shape   = shapeRender,
            ))
            updateUndoRedoButtons()

            if (!isLassoMode) enterLassoMode()
            isSmartLassoSession = true
            val pad = 8f * density
            val selBox = RectF(shapeRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoSelectedIds(setOf(shapeId), selBox)
            selectedObjectIds.clear()
            selectedObjectIds.add(shapeId)
            updateFloatingSelectionToolbar(selBox)
        }
    }

    // ── Lasso mode helpers ────────────────────────────────────────────────────

    private fun enterLassoMode() {
        isLassoMode = true
        // Ensure the drawing engine is in a neutral state before handing off to lasso.
        if (isEraserActive) {
            isEraserActive = false
            drawingView.setEraserMode(false)
        }
        drawingView.setLassoMode(true)
        binding.btnLasso.isSelected  = true
        binding.btnPen.isSelected    = false
        binding.btnEraser.isSelected = false
    }

    /** Exit lasso mode, clearing all selection state. The caller is responsible for activating the desired tool. */
    private fun exitLassoMode() {
        isLassoMode = false
        isSmartLassoSession = false
        selectedObjectIds.clear()
        drawingView.setLassoMode(false)
        binding.btnLasso.isSelected = false
        hideFloatingSelectionToolbar()
        hideLassoPopupToolbar()
        hideShapeTransformToolbar()
    }

    private fun enterLassoEraserMode() {
        if (isLassoMode) exitLassoMode()
        if (isEraserActive) {
            isEraserActive = false
            drawingView.setEraserMode(false)
        }
        isLassoEraserMode = true
        drawingView.setLassoEraserMode(true)
        binding.btnLassoEraser.isSelected = true
        binding.btnPen.isSelected         = false
        binding.btnEraser.isSelected      = false
        binding.btnLasso.isSelected       = false
    }

    private fun exitLassoEraserMode() {
        isLassoEraserMode = false
        drawingView.setLassoEraserMode(false)
        binding.btnLassoEraser.isSelected = false
    }

    // ── Shape transform mode helpers ──────────────────────────────────────────

    private fun enterShapeTransformMode(shape: com.notesprout.android.data.ShapeRender) {
        isShapeTransformMode = true
        // Clear the lasso selection overlay — the transform controller draws its own
        // oriented bounding box and handles on top of the bitmap.
        drawingView.setLassoOverlay(null, null)
        drawingView.enterShapeTransform(shape)
        hideFloatingSelectionToolbar()
        showShapeTransformToolbar(shape.boundingBox)
        // Async rebuild bitmap without the transforming shape so the original doesn't ghost
        // behind the moving handles.
        val shapesExcluded = drawingView.getShapeObjects().filter { it.id != shape.id }
        val templateBmp = currentTemplateBitmap
        val strokes = drawingView.getStrokes()
        val headings = drawingView.getHeadings()
        val texts = drawingView.getTextObjects()
        val lines = drawingView.getLineObjects()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, headings, texts, lines,
                    shapeObjects = shapesExcluded)
            }
            if (bitmap != null && isShapeTransformMode) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            }
        }
    }

    private fun exitShapeTransformMode(clearSelection: Boolean = false) {
        val finalRender = drawingView.getShapeTransformWorkingRender()
        isShapeTransformMode = false
        // exitShapeTransform fires onShapeTransformed → persistShapeTransform
        drawingView.exitShapeTransform()
        hideShapeTransformToolbar()
        if (clearSelection || finalRender == null) {
            // Tap outside — clear selection and mirror the normal lasso tap-to-dismiss behavior.
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            if (isSmartLassoSession) {
                exitLassoMode()
                drawingView.enableDrawing()
                binding.btnPen.isSelected = true
            }
        } else {
            // Done — keep the shape selected at its new bounding box.
            val pad = 8f * resources.displayMetrics.density
            val selBox = RectF(finalRender.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            updateFloatingSelectionToolbar(selBox)
        }
        // Rebuild bitmap immediately with the final shape included. persistShapeTransform
        // (fired async via onShapeTransformed) early-returns when geometry is unchanged,
        // which would leave the shape invisible since enterShapeTransformMode excluded it.
        val updatedShapes = if (finalRender != null) {
            drawingView.getShapeObjects().map { if (it.id == finalRender.id) finalRender else it }
        } else {
            drawingView.getShapeObjects()
        }
        if (finalRender != null) drawingView.loadShapeObjects(updatedShapes)
        val templateBmp = currentTemplateBitmap
        val strokes     = drawingView.getStrokes()
        val headings    = drawingView.getHeadings()
        val texts       = drawingView.getTextObjects()
        val lines       = drawingView.getLineObjects()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, headings, texts, lines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            else drawingView.loadStrokes(strokes)
        }
    }

    private fun showShapeTransformToolbar(selBox: android.graphics.RectF) {
        binding.shapeTransformToolbar.visibility = android.view.View.VISIBLE
        binding.shapeTransformToolbar.post {
            positionPopover(binding.shapeTransformToolbar, selBox)
        }
        val working = drawingView.getShapeTransformWorkingRender()
        if (working != null) updateShapeTransformToggle(working)
    }

    private fun hideShapeTransformToolbar() {
        binding.shapeTransformToolbar.visibility = android.view.View.GONE
    }

    private fun updateShapeTransformToggle(shape: com.notesprout.android.data.ShapeRender) {
        val isLocked = shape.aspectLocked
        val label = if (shape.type == com.notesprout.android.data.ShapeType.ELLIPSE) {
            if (isLocked) "Circle" else "Oval"
        } else {
            if (isLocked) "Locked" else "Lock ratio"
        }
        binding.btnShapeAspectLock.text = label
        binding.btnShapeAspectLock.isSelected = isLocked
    }

    // Text placement mode (the Insert Text button + tap-to-place-text flow) was retired —
    // strokes are converted to text instead. Existing type="text" objects remain fully
    // editable via [showTextEditDialogForTextObject] and the TextInserted undo/redo path.

    /**
     * Opens [TextEditDialog] pre-filled with [textRender]'s current markdown.
     * Non-empty confirm → [updateTextObject]; empty confirm → [deleteTextObjectFromEdit].
     */
    private fun showTextEditDialogForTextObject(textRender: TextRender) {
        TextEditDialog(
            context = this,
            initialMarkdown = textRender.text,
            onConfirm = { markdown ->
                if (markdown.isNotBlank()) {
                    updateTextObject(textRender, markdown)
                } else {
                    deleteTextObjectFromEdit(textRender)
                }
            },
        ).show()
    }

    private fun insertStickyNote() {
        val db      = soilDatabase ?: return
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val pageW   = drawingView.asView().width.takeIf { it > 0 } ?: return
        val pageH   = drawingView.asView().height.takeIf { it > 0 } ?: return
        val density = resources.displayMetrics.density

        lifecycleScope.launch {
            val iconSizePx = (STICKY_NOTE_ICON_SIZE_DP * density)
            val left  = ((pageW - iconSizePx) / 2f).coerceAtLeast(0f)
            val top   = ((pageH - iconSizePx) / 2f).coerceAtLeast(0f)
            val bbox  = RectF(left, top, left + iconSizePx, top + iconSizePx)

            val noteId   = java.util.UUID.randomUUID().toString()
            val now      = System.currentTimeMillis()
            val noteRender = StickyNoteRender(id = noteId, boundingBox = bbox)

            withContext(Dispatchers.IO) {
                db.notebookDao().insertStickyNoteSubtree(noteRender, layerId, 0, now, now, density)
                noteContentEdit(db, pageId)
            }

            val updatedNotes   = drawingView.getStickyNotes() + noteRender
            drawingView.loadStickyNotes(updatedNotes)

            val templateBmp    = currentTemplateBitmap
            val currentStrokes = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, stickyNotes = updatedNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            }

            undoRedoManager.push(UndoRedoAction.StickyNoteInserted(
                noteId  = noteId,
                pageId  = pageId,
                layerId = layerId,
                note    = noteRender,
            ))
            updateUndoRedoButtons()

            // Open the editor immediately so the user can draw content before placing the icon.
            openStickyNote(noteRender, initialCreate = true)
        }
    }

    /** Set the transfer singleton and launch [StickyNoteEditorActivity]. */
    private fun openStickyNote(note: StickyNoteRender, initialCreate: Boolean) {
        StickyNoteEditorTransfer.input = StickyNoteEditorTransfer.Content(
            strokes       = note.strokes,
            headings      = note.headings,
            textObjects   = note.textObjects,
            lines         = note.lines,
            shapes        = note.shapes,
            contentWidth  = note.contentWidth,
            contentHeight = note.contentHeight,
        )
        StickyNoteEditorTransfer.output = null
        // Service the editor's real-time saves ourselves. It used to open its own connection to this
        // same `.soil`, which put two writers on one WAL file and killed the app mid-writing; ours is
        // already open, so both saves now serialize through Room instead of fighting for the lock.
        StickyNoteEditorTransfer.persistToHost = { render -> persistStickyNoteFromEditor(render) }
        pendingStickyNote          = note
        pendingStickyInitialCreate = initialCreate
        editorLauncher.launch(StickyNoteEditorActivity.intent(this, note, StickyNoteEditorActivity.HOST_NOTEBOOK, notebookId, encryptionInfo.encrypted))
    }

    /**
     * Write the editor's in-progress sticky content on **this** Activity's connection.
     *
     * Called from the editor's debounced persist while it is in the foreground and we are merely
     * paused — same process, so a direct call, and our `soilDatabase` is still open. Failures are
     * logged, never thrown: this runs inside the editor's coroutine and must not take it (or the
     * process) down. The host's normal close-callback write remains the backstop.
     */
    private suspend fun persistStickyNoteFromEditor(render: StickyNoteRender) {
        val db = soilDatabase ?: return
        val density = resources.displayMetrics.density
        withContext(Dispatchers.IO) {
            runCatching {
                db.withTransaction {
                    db.notebookDao().replaceStickyNoteSubtree(render, System.currentTimeMillis(), density)
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Sticky real-time persist failed", e)
            }
        }
    }

    /** Topmost sticky note icon whose bbox contains the view-space point ([x],[y]), or null. */
    private fun stickyNoteAt(x: Float, y: Float): StickyNoteRender? =
        drawingView.getStickyNotes().lastOrNull { it.boundingBox.contains(x, y) }

    /** Enter lasso mode and select [note]'s icon box — used after create-flow returns. */
    private fun selectStickyNoteIcon(note: StickyNoteRender) {
        if (!isLassoMode) enterLassoMode()
        selectedObjectIds.clear()
        selectedObjectIds.add(note.id)
        drawingView.lassoSelectedIds = selectedObjectIds.toSet()
        val pad = 8f * resources.displayMetrics.density
        val selBox = RectF(note.boundingBox).apply { inset(-pad, -pad) }
        drawingView.setLassoOverlay(null, selBox)
        updateFloatingSelectionToolbar(selBox)
    }

    private fun insertLineObjects(db: SoilDatabase, lines: List<LineRender>) {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val pageId  = currentPageId.takeIf { it.isNotEmpty() } ?: return

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val dao = db.notebookDao()
                    lines.forEach { line ->
                        dao.insertObject(line.toRow(layerId, 0, now, now, resources.displayMetrics.density))
                    }
                    noteContentEdit(db, pageId)
                }
            }
            val updatedLines = drawingView.getLineObjects() + lines
            drawingView.loadLineObjects(updatedLines)

            undoRedoManager.push(UndoRedoAction.LinesInserted(
                lineIds = lines.map { it.id },
                pageId  = pageId,
                layerId = layerId,
                lines   = lines,
            ))
            updateUndoRedoButtons()

            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }

            if (!isLassoMode) enterLassoMode()
            val newIds = lines.mapTo(mutableSetOf()) { it.id }
            selectedObjectIds.clear()
            selectedObjectIds.addAll(newIds)
            drawingView.lassoSelectedIds = newIds
            val unionBox = lines.fold(RectF()) { acc, l ->
                if (acc.isEmpty) RectF(l.boundingBox) else acc.also { it.union(l.boundingBox) }
            }
            val pad = 8f * resources.displayMetrics.density
            unionBox.inset(-pad, -pad)
            drawingView.setLassoOverlay(null, unionBox)
            updateFloatingSelectionToolbar(unionBox)
        }
    }

    /**
     * Persists [newMarkdown] and a resized bounding box (top-left fixed, clamped to page) for
     * [textRender], rebuilds the canvas, refreshes the selection highlight, and pushes a
     * [UndoRedoAction.TextEdited] undo entry.
     */
    private fun updateTextObject(textRender: TextRender, newMarkdown: String) {
        val db     = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val pageW  = drawingView.asView().width
        val pageH  = drawingView.asView().height

        lifecycleScope.launch {
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color    = android.graphics.Color.BLACK
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
            }
            val (measuredW, measuredH) = withContext(Dispatchers.Default) {
                TextObjectRenderer.measure(newMarkdown, pageW, paint, resources.displayMetrics.density)
            }

            val objW    = measuredW.toFloat().coerceAtMost(pageW.toFloat())
            val objH    = measuredH.toFloat()
            // Keep top-left fixed; clamp if new size would overflow the page edge.
            val newLeft = textRender.boundingBox.left.coerceIn(0f, (pageW - objW).coerceAtLeast(0f))
            val newTop  = textRender.boundingBox.top.coerceIn(0f, (pageH - objH).coerceAtLeast(0f))
            val newBbox = RectF(newLeft, newTop, newLeft + objW, newTop + objH)

            val now      = System.currentTimeMillis()
            val newTextRender = TextRender(id = textRender.id, boundingBox = newBbox, text = newMarkdown, strokes = textRender.strokes)

            withContext(Dispatchers.IO) {
                db.notebookDao().replaceTextSubtree(newTextRender, now)
                noteContentEdit(db, pageId)
            }

            val updatedTexts  = drawingView.getTextObjects().map { t ->
                if (t.id == textRender.id) newTextRender else t
            }
            drawingView.loadTextObjects(updatedTexts)

            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }

            // Refresh selection overlay to match new bounding box.
            if (textRender.id in selectedObjectIds) {
                val pad    = 8f * resources.displayMetrics.density
                val selBox = RectF(newBbox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                    updateFloatingSelectionToolbar(selBox)
                }
            }

            undoRedoManager.push(UndoRedoAction.TextEdited(
                textId       = textRender.id,
                pageId       = pageId,
                oldTextRender = textRender,
                newTextRender = newTextRender,
            ))
            updateUndoRedoButtons()
        }
    }

    /**
     * Soft-deletes [textRender] (triggered by an empty-confirm in [TextEditDialog]), clears its
     * selection, rebuilds the canvas, and pushes a [UndoRedoAction.TextRemoved] undo entry.
     *
     * Tap-to-edit is GATED on [TextRender.text].isNotBlank(), so this path is only reached
     * for recognized text objects (non-blank text). Soft-deleting the entire row — including
     * any embedded [TextRender.strokes] — is intentional: the user explicitly cleared the text.
     */
    private fun deleteTextObjectFromEdit(textRender: TextRender) {
        val db     = soilDatabase ?: return
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return

        lifecycleScope.launch {
            val deletedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                db.notebookDao().softDeleteById(textRender.id, deletedAt)
                noteContentEdit(db, pageId)
            }

            val updatedTexts    = drawingView.getTextObjects().filter { it.id != textRender.id }
            drawingView.loadTextObjects(updatedTexts)

            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }

            selectedObjectIds.remove(textRender.id)
            drawingView.lassoSelectedIds = selectedObjectIds.toSet()
            if (selectedObjectIds.isEmpty()) {
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            }

            undoRedoManager.push(UndoRedoAction.TextRemoved(
                textId    = textRender.id,
                pageId    = pageId,
                textRender = textRender,
                deletedAt = deletedAt,
            ))
            updateUndoRedoButtons()
        }
    }

    /**
     * Serializes [executeAction] runs. Each execution suspends at several IO points while
     * same-page handlers snapshot the live stroke list and write back FULL lists — two
     * interleaved executions (rapid multi-finger double-taps, button mashing) each write a list
     * missing only their own change, resurrecting a just-undone stroke on screen while its row is
     * soft-deleted. Cross-page pairs additionally race the page switch.
     */
    private val undoRedoExecutionMutex = kotlinx.coroutines.sync.Mutex()

    private fun performUndo() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            undoRedoExecutionMutex.withLock {
                val action = undoRedoManager.undo() ?: return@withLock
                updateUndoRedoButtons()
                executeAction(db, action, isUndo = true)
            }
        }
    }

    private fun performRedo() {
        val db = soilDatabase ?: return
        lifecycleScope.launch {
            undoRedoExecutionMutex.withLock {
                val action = undoRedoManager.redo() ?: return@withLock
                updateUndoRedoButtons()
                executeAction(db, action, isUndo = false)
            }
        }
    }

    /**
     * Reorders [pageId] to be immediately after [afterPageId] (or first if null).
     * All page `order` values are reassigned sequentially in a single transaction.
     * Must be called on [Dispatchers.IO] (called inside [withContext] in [executeAction]).
     */
    private suspend fun movePageToAfter(db: SoilDatabase, pageId: String, afterPageId: String?) {
        db.withTransaction {
            val dao = db.notebookDao()
            val allPages = dao.getPagesSorted().toMutableList()
            val movingIdx = allPages.indexOfFirst { it.id == pageId }
            if (movingIdx < 0) return@withTransaction
            val movingPage = allPages.removeAt(movingIdx)
            val insertAt = if (afterPageId == null) {
                0
            } else {
                val afterIdx = allPages.indexOfFirst { it.id == afterPageId }
                if (afterIdx >= 0) afterIdx + 1 else allPages.size
            }
            allPages.add(insertAt, movingPage)
            allPages.forEachIndexed { i, page -> dao.updateOrder(page.id, i) }
        }
    }

    /**
     * Execute a single undo or redo [action].
     *
     * Called from the main-thread coroutine context (launched via [lifecycleScope]).
     * Handles all thread switching internally.
     *
     * Flow — stroke actions, same page:
     *   DB op → in-memory stroke list update → rebuild bitmap → [loadStrokesWithBitmap]
     *   (one EPD handoff; user sees only the affected stroke change)
     *
     * Flow — stroke actions, cross-page (two-phase):
     *   Phase 1: save leaving page → set [currentPageIndex] → load target page in its
     *            PRE-undo state (stroke still present/absent as it was) → display it so
     *            the user can see the page, THEN
     *   Phase 2: DB op → in-memory update → [loadStrokesWithBitmap] so the stroke
     *            visibly appears or disappears in front of the user.
     *
     * Flow — page / template actions:
     *   [saveAndSwitchPage] if cross-page → DB op → snapshot invalidation →
     *   [eraseAll] → [loadCurrentPage] → [displayPage]
     */
    private suspend fun executeAction(db: SoilDatabase, action: UndoRedoAction, isUndo: Boolean) {
        val now = System.currentTimeMillis()
        val dao = db.notebookDao()

        val targetPageId: String? = when (action) {
            is UndoRedoAction.StrokeAdded      -> action.pageId
            is UndoRedoAction.StrokeErased     -> action.pageId
            is UndoRedoAction.LassoErased      -> action.pageId
            is UndoRedoAction.LassoPasted      -> action.pageId
            is UndoRedoAction.LassoCut         -> action.pageId
            is UndoRedoAction.LassoDeleted     -> action.pageId
            is UndoRedoAction.StrokesMoved     -> action.pageId
            is UndoRedoAction.TemplateChanged  -> action.pageId
            is UndoRedoAction.PageEraseAll     -> action.pageId
            is UndoRedoAction.HeadingCreated      -> action.pageId
            is UndoRedoAction.HeadingTextEdited   -> action.pageId
            is UndoRedoAction.HeadingLevelChanged -> action.pageId
            is UndoRedoAction.TextInserted     -> action.pageId
            is UndoRedoAction.TextEdited       -> action.pageId
            is UndoRedoAction.TextRemoved      -> action.pageId
            is UndoRedoAction.TextConverted    -> action.pageId
            is UndoRedoAction.ShapeCreated      -> action.pageId
            is UndoRedoAction.ShapeTransformed  -> action.pageId
            is UndoRedoAction.ScribbleErased   -> action.pageId
            is UndoRedoAction.LinesInserted    -> action.pageId
            is UndoRedoAction.LinesRemoved     -> action.pageId
            is UndoRedoAction.LinkCreated         -> action.pageId
            is UndoRedoAction.LinkRemoved         -> action.pageId
            is UndoRedoAction.LinkEdited          -> action.pageId
            is UndoRedoAction.StickyNoteInserted       -> action.pageId
            is UndoRedoAction.StickyNoteContentEdited  -> action.pageId
            is UndoRedoAction.ShapeInserted            -> action.pageId
            else                                       -> null
        }
        val isCrossPage = targetPageId != null && targetPageId != currentPageId

        if (handleCrossPageAction(db, action, isUndo, now, dao, isCrossPage)) return

        // ── Step 1: Cross-page navigation (template / page actions only) ──────
        if (isCrossPage) {
            val idx = pages.indexOfFirst { it.id == targetPageId }
            if (idx >= 0) saveAndSwitchPage(idx)
        }

        // ── Step 2: Action-specific DB operation ──────────────────────────────
        executeActionDb(db, action, isUndo, now, dao)

        // ── Step 3a: Same-page optimised handlers ─────────────────────────────
        if (handleSamePageAction(db, action, isUndo, now, dao)) return

        // ── Step 3b: Page / template actions — full reload ────────────────────
        val snapshotPageId: String? = when (action) {
            is UndoRedoAction.TemplateChanged -> action.pageId
            is UndoRedoAction.PageDeleted     -> if (isUndo) action.pageId else null
            is UndoRedoAction.PageAdded       -> if (!isUndo) action.pageId else null
            is UndoRedoAction.PagePasted      -> if (!isUndo) action.pageId else null
            is UndoRedoAction.PageEraseAll    -> action.pageId
            else                              -> null
        }
        if (snapshotPageId != null) {
            withContext(Dispatchers.IO) { noteContentEdit(db, snapshotPageId) }
        }

        // Link create/remove/edit undo/redo take the full-reload path (no optimised same-page
        // handler). The reload re-reads links from the DB and re-renders; clear any lingering
        // selection so the dashed box / floating toolbar don't point at a just-changed object.
        if (action is UndoRedoAction.LinkCreated || action is UndoRedoAction.LinkRemoved ||
            action is UndoRedoAction.LinkEdited) {
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
        }

        persistedStrokeIds.clear()
        drawingView.clearForPageLoad()
        val result = withContext(Dispatchers.IO) { loadCurrentPage(db) }
        displayPage(result)
        updatePageIndicator()
        saveLastOpenedPage(currentPageId)
        postDisplayWork(db, result)
    }

    // ── Cross-page action handler ─────────────────────────────────────────
    // Returns true if the action was handled (and executeAction should return early).
    @Suppress("ReturnCount")
    private suspend fun handleCrossPageAction(
        db: SoilDatabase,
        action: UndoRedoAction,
        isUndo: Boolean,
        now: Long,
        dao: com.notesprout.android.data.NotebookDao,
        isCrossPage: Boolean,
    ): Boolean {
        if (!isCrossPage) return false

        // ── Cross-page LassoErased: two-phase display ─────────────────────────
        if (action is UndoRedoAction.LassoErased) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoLines: List<LineRender>
            val preUndoLinks: List<LinkRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts    = loadTextObjectsFromDb(db, currentLayerId)
                preUndoLines    = loadLineObjectsFromDb(db, currentLayerId)
                preUndoLinks    = loadLinksFromDb(db, currentLayerId)
                preUndoShapes   = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadLineObjects(preUndoLines)
            drawingView.loadLinks(preUndoLinks)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, preUndoLines)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.linkIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            val idsSet        = action.strokeIds.toSet()
            val textIdsSet    = action.textIds.toSet()
            val lineIdsSet    = action.lineIds.toSet()
            val shapeIdsSet   = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo: re-erase → remove from in-memory stroke, heading, text, line, and shape lists.
                val preUndoHeadingIds = preUndoHeadings.mapTo(mutableSetOf()) { it.id }
                val erasedHeadingIds  = idsSet.intersect(preUndoHeadingIds)
                updatedStrokes  = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in erasedHeadingIds }
                updatedTexts    = preUndoTexts.filter { it.id !in textIdsSet }
                updatedLines    = preUndoLines.filter { it.id !in lineIdsSet }
                updatedShapes   = preUndoShapes.filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet - erasedHeadingIds - textIdsSet - lineIdsSet)
            } else {
                // Undo: restore → fetch from DB, partition by type.
                val restoredRows = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id -> dao.getObjectById(id) }
                }
                val restoredStrokes = restoredRows
                    .filter { it.type != TYPE_HEADING && it.type != TYPE_TEXT && it.type != TYPE_LINE }
                    .mapNotNull { obj ->
                        try { LiveStroke.fromRow(obj) }
                        catch (e: Exception) {
                            Log.e(TAG, "executeAction LassoErased: failed to deserialize ${obj.id}", e); null
                        }
                    }
                val restoredHeadings = restoredRows
                    .filter { it.type == TYPE_HEADING }
                    .mapNotNull { obj -> db.notebookDao().assembleHeadingResolved(obj) }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + restoredHeadings
                updatedTexts    = preUndoTexts + action.textObjects
                updatedLines    = preUndoLines + action.lines
                updatedShapes   = preUndoShapes + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            // Link rows were flipped above; re-read the post-op link state for this page.
            val updatedLinks = withContext(Dispatchers.IO) { loadLinksFromDb(db, currentLayerId) }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return true
        }

        // ── Cross-page ScribbleErased: two-phase display (mirrors LassoErased) ──────────────
        if (action is UndoRedoAction.ScribbleErased) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoLines: List<LineRender>
            val preUndoLinks: List<LinkRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts    = loadTextObjectsFromDb(db, currentLayerId)
                preUndoLines    = loadLineObjectsFromDb(db, currentLayerId)
                preUndoLinks    = loadLinksFromDb(db, currentLayerId)
                preUndoShapes   = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadLineObjects(preUndoLines)
            drawingView.loadLinks(preUndoLinks)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, preUndoLines)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.erasedObjectIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.erasedObjectIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.linkIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            val erasedIdsSet  = action.erasedObjectIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val textIdsSet    = action.textIds.toSet()
            val lineIdsSet    = action.lineIds.toSet()
            val linkIdsSet    = action.linkIds.toSet()
            val shapeIdsSet   = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo: re-erase → remove erased objects from in-memory lists.
                updatedStrokes  = preUndoStrokes.filter { it.id !in erasedIdsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in headingIdsSet }
                updatedTexts    = preUndoTexts.filter { it.id !in textIdsSet }
                updatedLines    = preUndoLines.filter { it.id !in lineIdsSet }
                updatedShapes   = preUndoShapes.filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(erasedIdsSet - headingIdsSet - textIdsSet - lineIdsSet - linkIdsSet)
            } else {
                // Undo: restore erased content from DB / action snapshot.
                val pureStrokeIdsSet = erasedIdsSet - headingIdsSet - textIdsSet - lineIdsSet - linkIdsSet
                val restoredStrokeRows = withContext(Dispatchers.IO) {
                    pureStrokeIdsSet.mapNotNull { id -> dao.getObjectById(id) }
                }
                val restoredStrokes = restoredStrokeRows.mapNotNull { obj ->
                    try { LiveStroke.fromRow(obj) }
                    catch (e: Exception) {
                        Log.e(TAG, "executeAction ScribbleErased: failed to deserialize ${obj.id}", e); null
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + action.headings
                updatedTexts    = preUndoTexts + action.textObjects
                updatedLines    = preUndoLines + action.lines
                updatedShapes   = preUndoShapes + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            val updatedLinks = withContext(Dispatchers.IO) { loadLinksFromDb(db, currentLayerId) }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return true
        }

        // ── Cross-page LassoCut: two-phase display (same as LassoErased + clipboard on redo) ─
        if (action is UndoRedoAction.LassoCut) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoLines: List<LineRender>
            val preUndoLinks: List<LinkRender>
            val preUndoStickyNotes: List<StickyNoteRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp         = loadPageTemplateFromDb(db)
                preUndoStrokes      = deserializeStrokesFromDb(db)
                preUndoHeadings     = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts        = loadTextObjectsFromDb(db, currentLayerId)
                preUndoLines        = loadLineObjectsFromDb(db, currentLayerId)
                preUndoLinks        = loadLinksFromDb(db, currentLayerId)
                preUndoStickyNotes  = loadStickyNotesFromDb(db, currentLayerId)
                preUndoShapes       = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadLineObjects(preUndoLines)
            drawingView.loadLinks(preUndoLinks)
            drawingView.loadStickyNotes(preUndoStickyNotes)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, preUndoLines, stickyNotes = preUndoStickyNotes)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                    action.textIds.forEach { dao.restoreById(it, now) }
                    action.lineIds.forEach { dao.restoreById(it, now) }
                    action.linkIds.forEach { dao.restoreById(it, now) }
                    action.stickyNoteIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                    action.textIds.forEach { dao.softDeleteById(it, now) }
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                    action.linkIds.forEach { dao.softDeleteById(it, now) }
                    action.stickyNoteIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.linkIds.isNotEmpty() || action.stickyNoteIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            val idsSet            = action.strokeIds.toSet()
            val headingIdsSet     = action.headingIds.toSet()
            val textIdsSet        = action.textIds.toSet()
            val lineIdsSet        = action.lineIds.toSet()
            val stickyIdsSet      = action.stickyNoteIds.toSet()
            val shapeIdsSet       = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo cut: remove from in-memory lists and repopulate clipboard.
                updatedStrokes      = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings     = preUndoHeadings.filter { it.id !in headingIdsSet }
                updatedTexts        = preUndoTexts.filter { it.id !in textIdsSet }
                updatedLines        = preUndoLines.filter { it.id !in lineIdsSet }
                updatedStickyNotes  = preUndoStickyNotes.filter { it.id !in stickyIdsSet }
                updatedShapes       = preUndoShapes.filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet)
                val clipBox = computeUnionBoundingBox(action.strokes, action.headings)
                action.textObjects.forEach { clipBox.union(it.boundingBox) }
                action.lines.forEach { clipBox.union(it.boundingBox) }
                action.links.forEach { clipBox.union(it.boundingBox) }
                action.stickyNotes.forEach { clipBox.union(it.boundingBox) }
                action.shapes.forEach { clipBox.union(it.boundingBox) }
                NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
                    strokes     = action.strokes.map { s -> s.deepCopy() },
                    headings    = action.headings.map { h -> HeadingStroke(h.id, RectF(h.boundingBox),
                        h.strokes.map { s -> s.deepCopy() },
                        recognizedText = h.recognizedText,
                        level = h.level) },
                    boundingBox = clipBox,
                    textObjects = action.textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
                    lineObjects = action.lines,
                    links       = action.links.map { it.translate(0f, 0f) },
                    stickyNotes = action.stickyNotes.map { it.translate(0f, 0f) },
                    shapeObjects = action.shapes,
                )
                updateLassoButtonIcon()
            } else {
                // Undo cut: fetch strokes from DB; use action snapshots for the rest.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoCut: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes      = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings     = preUndoHeadings + action.headings
                updatedTexts        = preUndoTexts + action.textObjects
                updatedLines        = preUndoLines + action.lines
                updatedStickyNotes  = preUndoStickyNotes + action.stickyNotes
                updatedShapes       = preUndoShapes + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            val updatedLinks = withContext(Dispatchers.IO) { loadLinksFromDb(db, currentLayerId) }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, stickyNotes = updatedStickyNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return true
        }

        // ── Cross-page LassoDeleted: two-phase display (identical to LassoErased — no clipboard) ─
        if (action is UndoRedoAction.LassoDeleted) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoLines: List<LineRender>
            val preUndoLinks: List<LinkRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts    = loadTextObjectsFromDb(db, currentLayerId)
                preUndoLines    = loadLineObjectsFromDb(db, currentLayerId)
                preUndoLinks    = loadLinksFromDb(db, currentLayerId)
                preUndoShapes   = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadLineObjects(preUndoLines)
            drawingView.loadLinks(preUndoLinks)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, preUndoLines)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                    action.textIds.forEach { dao.restoreById(it, now) }
                    action.lineIds.forEach { dao.restoreById(it, now) }
                    action.linkIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                    action.textIds.forEach { dao.softDeleteById(it, now) }
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                    action.linkIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.linkIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val textIdsSet    = action.textIds.toSet()
            val lineIdsSet    = action.lineIds.toSet()
            val shapeIdsSet   = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo delete: remove from in-memory lists.
                updatedStrokes  = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings = preUndoHeadings.filter { it.id !in headingIdsSet }
                updatedTexts    = preUndoTexts.filter { it.id !in textIdsSet }
                updatedLines    = preUndoLines.filter { it.id !in lineIdsSet }
                updatedShapes   = preUndoShapes.filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Undo delete: fetch strokes from DB; use action.headings/textObjects/lines/shapes.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoDeleted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings + action.headings
                updatedTexts    = preUndoTexts + action.textObjects
                updatedLines    = preUndoLines + action.lines
                updatedShapes   = preUndoShapes + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            val updatedLinks = withContext(Dispatchers.IO) { loadLinksFromDb(db, currentLayerId) }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return true
        }

        // ── Cross-page LassoPasted: two-phase display (inverse of LassoErased) ─
        // Undo paste = remove strokes/headings/text objects (mirrors LassoErased redo).
        // Redo paste = restore strokes/headings/text objects (mirrors LassoErased undo).
        if (action is UndoRedoAction.LassoPasted) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoLines: List<LineRender>
            val preUndoLinks: List<LinkRender>
            val preUndoStickyNotes: List<StickyNoteRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp         = loadPageTemplateFromDb(db)
                preUndoStrokes      = deserializeStrokesFromDb(db)
                preUndoHeadings     = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts        = loadTextObjectsFromDb(db, currentLayerId)
                preUndoLines        = loadLineObjectsFromDb(db, currentLayerId)
                preUndoLinks        = loadLinksFromDb(db, currentLayerId)
                preUndoStickyNotes  = loadStickyNotesFromDb(db, currentLayerId)
                preUndoShapes       = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadLineObjects(preUndoLines)
            drawingView.loadLinks(preUndoLinks)
            drawingView.loadStickyNotes(preUndoStickyNotes)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, preUndoLines, stickyNotes = preUndoStickyNotes)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                    action.textIds.forEach { dao.softDeleteById(it, now) }
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                    action.linkIds.forEach { dao.softDeleteById(it, now) }
                    action.stickyNoteIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                    action.textIds.forEach { dao.restoreById(it, now) }
                    action.lineIds.forEach { dao.restoreById(it, now) }
                    action.linkIds.forEach { dao.restoreById(it, now) }
                    action.stickyNoteIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                }
                if (action.linkIds.isNotEmpty() || action.stickyNoteIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            val idsSet            = action.strokeIds.toSet()
            val headingIdsSet     = action.headingIds.toSet()
            val textIdsSet        = action.textIds.toSet()
            val lineIdsSet        = action.lineIds.toSet()
            val stickyIdsSet      = action.stickyNoteIds.toSet()
            val shapeIdsSet       = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (isUndo) {
                // Undo paste → remove pasted strokes, headings, text objects, lines, sticky notes, shapes.
                updatedStrokes      = preUndoStrokes.filter { it.id !in idsSet }
                updatedHeadings     = preUndoHeadings.filter { it.id !in headingIdsSet }
                updatedTexts        = preUndoTexts.filter { it.id !in textIdsSet }
                updatedLines        = preUndoLines.filter { it.id !in lineIdsSet }
                updatedStickyNotes  = preUndoStickyNotes.filter { it.id !in stickyIdsSet }
                updatedShapes       = preUndoShapes.filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Redo paste → fetch restored strokes/headings from DB; use action lists for the rest.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoPasted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                val restoredHeadings = withContext(Dispatchers.IO) {
                    action.headingIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { dao.assembleHeadingResolved(it) }
                    }
                }
                updatedStrokes      = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings     = preUndoHeadings + restoredHeadings
                updatedTexts        = preUndoTexts + action.textObjects
                updatedLines        = preUndoLines + action.lines
                updatedStickyNotes  = preUndoStickyNotes + action.stickyNotes
                updatedShapes       = preUndoShapes + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            val updatedLinks = withContext(Dispatchers.IO) { loadLinksFromDb(db, currentLayerId) }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, stickyNotes = updatedStickyNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page HeadingCreated: two-phase display ─────────────────────
        // Undo: heading soft-deleted, original strokes restored — page shows strokes, no heading.
        // Redo: original strokes soft-deleted, heading restored — page shows heading, no strokes.
        if (action is UndoRedoAction.HeadingCreated) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.headingId, now)
                    action.originalStrokeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.originalStrokeIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.headingId, now)
                }
            }

            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.originalStrokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction HeadingCreated: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes  = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedHeadings = preUndoHeadings.filter { it.id != action.headingId }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            } else {
                val idsSet = action.originalStrokeIds.toSet()
                updatedStrokes = preUndoStrokes.filter { it.id !in idsSet }
                persistedStrokeIds.removeAll(idsSet)
                val headingBox = headingBoundingBox(action.embeddedStrokes, action.recognizedText)
                val newHeading = HeadingStroke(id = action.headingId, boundingBox = headingBox, strokes = action.embeddedStrokes, recognizedText = action.recognizedText, level = action.level)
                updatedHeadings = preUndoHeadings + newHeading
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page HeadingTextEdited: two-phase display ──────────────────
        if (action is UndoRedoAction.HeadingTextEdited) {
            val targetText = if (isUndo) action.previousText else action.newText
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            val pageWidthForHeading = resources.displayMetrics.widthPixels
            withContext(Dispatchers.IO) {
                val row = dao.getObjectById(action.headingId) ?: return@withContext
                val base = db.notebookDao().assembleHeadingResolved(row) ?: return@withContext
                if (targetText != null) {
                    val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
                    }
                    val paddingPx = 8f * resources.displayMetrics.density
                    val box = base.boundingBox
                    val (measuredW, measuredH) = TextObjectRenderer.measure(targetText, pageWidthForHeading, textPaint, resources.displayMetrics.density, singleLine = true)
                    val newBox = RectF(box.left, box.top, box.left + measuredW + 2f * paddingPx, box.top + measuredH + 2f * paddingPx)
                    dao.replaceHeadingSubtree(base.copy(recognizedText = targetText, boundingBox = newBox), now)
                } else {
                    dao.replaceHeadingSubtree(base.copy(recognizedText = targetText), now)
                }
            }

            val updatedHeadings = withContext(Dispatchers.IO) {
                loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page HeadingLevelChanged ───────────────────────────────────
        if (action is UndoRedoAction.HeadingLevelChanged) {
            val targetLevel = if (isUndo) action.previousLevel else action.newLevel
            val targetText  = if (isUndo) action.previousText  else action.newText
            val targetBox   = if (isUndo) action.previousBox   else action.newBox
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            // Write target level/text/box directly — no re-measure needed (boxes are pre-computed).
            withContext(Dispatchers.IO) {
                val row     = dao.getObjectById(action.headingId) ?: return@withContext
                val updated = (db.notebookDao().assembleHeadingResolved(row) ?: return@withContext)
                    .copy(recognizedText = targetText, level = targetLevel, boundingBox = targetBox)
                dao.replaceHeadingSubtree(updated, now)
            }

            val updatedHeadings = withContext(Dispatchers.IO) {
                loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(updatedHeadings)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page TextConverted: two-phase display ──────────────────────
        // Undo: text row soft-deleted, original strokes restored — page shows strokes, no text obj.
        // Redo: original strokes soft-deleted, text row restored — page shows text obj, no strokes.
        if (action is UndoRedoAction.TextConverted) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
            }
            val preUndoTexts = withContext(Dispatchers.IO) { loadTextObjectsFromDb(db, currentLayerId) }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.textId, now)
                    action.originalStrokeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.originalStrokeIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.textId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            val updatedStrokes: List<LiveStroke>
            val updatedTexts: List<TextRender>
            if (isUndo) {
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.originalStrokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction TextConverted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes = buildList { addAll(preUndoStrokes); addAll(restoredStrokes) }
                updatedTexts   = preUndoTexts.filter { it.id != action.textId }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            } else {
                val idsSet = action.originalStrokeIds.toSet()
                updatedStrokes = preUndoStrokes.filter { it.id !in idsSet }
                persistedStrokeIds.removeAll(idsSet)
                updatedTexts = preUndoTexts + action.textRender
            }
            drawingView.loadTextObjects(updatedTexts)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, preUndoHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page TextEdited / TextRemoved: two-phase display ───────────
        if (action is UndoRedoAction.TextEdited || action is UndoRedoAction.TextRemoved) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            val targetPage = when (action) {
                is UndoRedoAction.TextEdited  -> action.pageId
                is UndoRedoAction.TextRemoved -> action.pageId
                else -> error("unreachable")
            }
            currentPageIndex = pages.indexOfFirst { it.id == targetPage }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val loadedStrokes: List<LiveStroke>
            val loadedHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                loadedStrokes   = deserializeStrokesFromDb(db)
                loadedHeadings  = loadHeadingsFromDb(db, currentLayerId)
            }
            val loadedTexts = withContext(Dispatchers.IO) {
                loadTextObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(loadedHeadings)
            drawingView.loadTextObjects(loadedTexts)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(loadedStrokes, templateBmp, loadedHeadings, loadedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(loadedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(loadedStrokes)
            }
            currentTemplateBitmap = templateBmp
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Cross-page StrokesMoved: two-phase display ────────────────────────
        if (action is UndoRedoAction.StrokesMoved) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            // Phase 1: load and display the target page in its pre-undo state.
            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoLines: List<LineRender>
            val preUndoLinks: List<LinkRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts    = loadTextObjectsFromDb(db, currentLayerId)
                preUndoLines    = loadLineObjectsFromDb(db, currentLayerId)
                preUndoLinks    = loadLinksFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadLineObjects(preUndoLines)
            drawingView.loadLinks(preUndoLinks)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, preUndoLines)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            // Phase 2: apply DB update and rebuild with target positions.
            val targetStrokes  = if (isUndo) action.originalStrokes else action.movedStrokes
            val targetHeadings = if (isUndo) action.originalHeadings else action.movedHeadings
            val targetTexts    = if (isUndo) action.originalTextObjects else action.movedTextObjects
            val targetLines    = if (isUndo) action.originalLineObjects else action.movedLineObjects
            val targetLinks    = if (isUndo) action.originalLinks else action.movedLinks
            withContext(Dispatchers.IO) {
                val ts = System.currentTimeMillis()
                val density = resources.displayMetrics.density
                db.withTransaction {
                    for (stroke in targetStrokes) {
                        dao.updateStrokeBlob(stroke.id, stroke.strokeBlob(), stroke.color, stroke.strokeWidth, ts)
                    }
                    for (heading in targetHeadings) {
                        dao.replaceHeadingSubtree(heading, ts)
                    }
                    for (textObj in targetTexts) {
                        dao.replaceTextSubtree(textObj, ts)
                    }
                    for (lineObj in targetLines) {
                        dao.updateColumns(lineObj.toRow("", 0, ts, ts, density))
                    }
                    for (link in targetLinks) {
                        dao.replaceLinkSubtree(link, ts, density)
                    }
                }
                if (targetLinks.isNotEmpty()) noteContentEdit(db, action.pageId)
            }
            val strokeById  = targetStrokes.associateBy { it.id }
            val headingById = targetHeadings.associateBy { it.id }
            val textById    = targetTexts.associateBy { it.id }
            val lineById    = targetLines.associateBy { it.id }
            val linkById    = targetLinks.associateBy { it.id }
            val updatedStrokes  = preUndoStrokes.map { strokeById[it.id] ?: it }
            val updatedHeadings = preUndoHeadings.map { headingById[it.id] ?: it }
            val updatedTexts    = preUndoTexts.map { textById[it.id] ?: it }
            val updatedLines    = preUndoLines.map { lineById[it.id] ?: it }
            val updatedLinks    = preUndoLinks.map { linkById[it.id] ?: it }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            // No selection box update — user is no longer on the original page.
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page stroke: two-phase display ──────────────────────────────
        // Phase 1 displays the target page in its pre-undo state so the user sees
        // the stroke present/absent before the action is applied.  Phase 2 applies
        // the DB change and updates the visual so the stroke visibly appears/disappears.
        if (action is UndoRedoAction.StrokeAdded || action is UndoRedoAction.StrokeErased) {
            val strokeId = when (action) {
                is UndoRedoAction.StrokeAdded  -> action.strokeId
                is UndoRedoAction.StrokeErased -> action.strokeId
                else -> error("unreachable")
            }

            // ── Phase 1a: save and snapshot the page we are leaving ───────────
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }

            // Switch page index; setupPageIds (called below) will resolve IDs.
            val strokeTargetPageId = when (action) {
                is UndoRedoAction.StrokeAdded  -> action.pageId
                is UndoRedoAction.StrokeErased -> action.pageId
                else -> error("unreachable")
            }
            currentPageIndex = pages.indexOfFirst { it.id == strokeTargetPageId }.coerceAtLeast(0)

            // ── Phase 1b: load and display target page in PRE-undo state ─────
            // The DB has not been modified yet, so the stroke is still in its
            // original state — present for StrokeAdded, absent for StrokeErased.
            // Force the full render path (bypass snapshot) so strokes are in memory
            // for Phase 2's in-memory update.
            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp      = loadPageTemplateFromDb(db)
                preUndoStrokes   = deserializeStrokesFromDb(db)
                preUndoHeadings  = loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings)
            }
            // Display: user now sees the target page with stroke in original state.
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            // ── Phase 2a: apply the DB operation ─────────────────────────────
            withContext(Dispatchers.IO) {
                when (action) {
                    is UndoRedoAction.StrokeAdded  ->
                        if (isUndo) dao.softDeleteById(strokeId, now) else dao.restoreById(strokeId, now)
                    is UndoRedoAction.StrokeErased ->
                        if (isUndo) dao.restoreById(strokeId, now) else dao.softDeleteById(strokeId, now)
                    else -> Unit
                }
            }

            // ── Phase 2b: in-memory visual update — stroke appears/disappears ─
            val shouldRemove = (action is UndoRedoAction.StrokeAdded  && isUndo) ||
                               (action is UndoRedoAction.StrokeErased && !isUndo)
            val updatedStrokes: List<LiveStroke>
            if (shouldRemove) {
                updatedStrokes = preUndoStrokes.filter { it.id != strokeId }
                persistedStrokeIds.remove(strokeId)
            } else {
                val strokeObj = withContext(Dispatchers.IO) { dao.getObjectById(strokeId) }
                val restored = strokeObj?.let {
                    try { LiveStroke.fromRow(it) }
                    catch (e: Exception) {
                        Log.e(TAG, "executeAction: failed to deserialize stroke $strokeId", e); null
                    }
                }
                updatedStrokes = buildList { addAll(preUndoStrokes); restored?.let { add(it) } }
                if (restored != null) persistedStrokeIds.add(strokeId)
            }
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, preUndoHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            return true
        }

        // ── Cross-page ShapeCreated: two-phase display ───────────────────────
        // Undo: shape row soft-deleted, original stroke restored — page shows stroke, no shape.
        // Redo: original stroke soft-deleted, shape row restored — page shows shape, no stroke.
        if (action is UndoRedoAction.ShapeCreated) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts    = loadTextObjectsFromDb(db, currentLayerId)
                preUndoShapes   = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, shapeObjects = preUndoShapes)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.shapeId, now)
                    if (action.deletedAt == 0L) {
                        // Stroke was never persisted — re-insert it
                        val stroke = action.originalStroke
                        dao.insertObject(
                            stroke.toStrokeRow(parentId = action.layerId, order = 0, createdAt = now, updatedAt = now)
                        )
                    } else {
                        dao.restoreById(action.originalStroke.id, now)
                    }
                } else {
                    dao.softDeleteById(action.originalStroke.id, now)
                    dao.restoreById(action.shapeId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            val updatedStrokes: List<LiveStroke>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (isUndo) {
                updatedShapes  = preUndoShapes.filter { it.id != action.shapeId }
                updatedStrokes = buildList { addAll(preUndoStrokes); add(action.originalStroke) }
                persistedStrokeIds.add(action.originalStroke.id)
            } else {
                updatedStrokes = preUndoStrokes.filter { it.id != action.originalStroke.id }
                persistedStrokeIds.remove(action.originalStroke.id)
                updatedShapes  = preUndoShapes + action.shape
            }
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, preUndoHeadings, preUndoTexts, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        // ── Cross-page ShapeTransformed: navigate to the shape's page and apply geometry ──
        if (action is UndoRedoAction.ShapeTransformed) {
            withContext(Dispatchers.IO) {
                saveStrokes(db)
            }
            currentPageIndex = pages.indexOfFirst { it.id == action.pageId }.coerceAtLeast(0)

            val templateBmp: Bitmap?
            val preUndoStrokes: List<LiveStroke>
            val preUndoHeadings: List<HeadingStroke>
            val preUndoTexts: List<TextRender>
            val preUndoShapes: List<com.notesprout.android.data.ShapeRender>
            withContext(Dispatchers.IO) {
                setupPageIds(db)
                templateBmp     = loadPageTemplateFromDb(db)
                preUndoStrokes  = deserializeStrokesFromDb(db)
                preUndoHeadings = loadHeadingsFromDb(db, currentLayerId)
                preUndoTexts    = loadTextObjectsFromDb(db, currentLayerId)
                preUndoShapes   = loadShapeObjectsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(preUndoHeadings)
            drawingView.loadTextObjects(preUndoTexts)
            drawingView.loadShapeObjects(preUndoShapes)
            val preUndobitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, shapeObjects = preUndoShapes)
            }
            if (preUndobitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, preUndobitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            currentTemplateBitmap = templateBmp
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)

            val density = resources.displayMetrics.density
            val target = if (isUndo) action.before else action.after
            withContext(Dispatchers.IO) {
                db.notebookDao().updateColumns(target.toRow("", 0, now, now, density))
                noteContentEdit(db, action.pageId)
            }

            val updatedShapes = preUndoShapes.map { if (it.id == target.id) target else it }
            drawingView.loadShapeObjects(updatedShapes)
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(preUndoStrokes, templateBmp, preUndoHeadings, preUndoTexts, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(preUndoStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(preUndoStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            return true
        }

        return false
    }

    private suspend fun executeActionDb(
        db: SoilDatabase,
        action: UndoRedoAction,
        isUndo: Boolean,
        now: Long,
        dao: com.notesprout.android.data.NotebookDao,
    ) {
        when (action) {
            is UndoRedoAction.StrokeAdded -> withContext(Dispatchers.IO) {
                if (isUndo) dao.softDeleteById(action.strokeId, now)
                else        dao.restoreById(action.strokeId, now)
            }

            is UndoRedoAction.StrokeErased -> withContext(Dispatchers.IO) {
                if (isUndo) dao.restoreById(action.strokeId, now)
                else        dao.softDeleteById(action.strokeId, now)
            }

            is UndoRedoAction.PageAdded -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.pageId, now)
                    dao.softDeleteByParentId(action.pageId, now)          // layer
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) dao.softDeleteByParentId(layer.id, now) // strokes
                    // For insert-before: the original page returns to action.pageIndex after removal.
                    // For insert-after: the original page was at action.pageIndex - 1.
                    currentPageIndex = if (action.insertedBefore) action.pageIndex
                                       else (action.pageIndex - 1).coerceAtLeast(0)
                } else {
                    // Redo: restore the page and its layer (strokes were never deleted on add).
                    dao.restoreById(action.pageId, now)
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) dao.restoreById(layer.id, now)
                    currentPageIndex = action.pageIndex
                }
            }

            is UndoRedoAction.PageDeleted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Restore the page row and all children soft-deleted at the same instant.
                    dao.restoreById(action.pageId, now)
                    dao.restoreChildrenDeletedSince(action.pageId, action.deletedAt, now)
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) {
                        dao.restoreChildrenDeletedSince(layer.id, action.deletedAt, now)
                    }
                    currentPageIndex = action.pageIndex
                } else {
                    // Redo: soft-delete the page and all surviving children.
                    dao.softDeleteById(action.pageId, now)
                    dao.softDeleteByParentId(action.pageId, now)
                    val layer = dao.getLayerForPageAny(action.pageId)
                    if (layer != null) dao.softDeleteByParentId(layer.id, now)
                    // currentPageIndex will be coerced by setupPageIds in loadCurrentPage.
                }
            }

            is UndoRedoAction.PagesDeleted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Restore every page in the batch + its children (each by its own deletedAt).
                    action.pages.forEach { ref ->
                        dao.restoreById(ref.pageId, now)
                        dao.restoreChildrenDeletedSince(ref.pageId, ref.deletedAt, now)
                        val layer = dao.getLayerForPageAny(ref.pageId)
                        if (layer != null) {
                            dao.restoreChildrenDeletedSince(layer.id, ref.deletedAt, now)
                        }
                        noteContentEdit(db, ref.pageId)
                    }
                    // Land near the restored block; loadCurrentPage will coerce into range.
                    currentPageIndex = action.pages.minOf { it.pageIndex }
                } else {
                    // Redo: re-soft-delete page + surviving children for each.
                    action.pages.forEach { ref ->
                        dao.softDeleteById(ref.pageId, now)
                        dao.softDeleteByParentId(ref.pageId, now)
                        val layer = dao.getLayerForPageAny(ref.pageId)
                        if (layer != null) dao.softDeleteByParentId(layer.id, now)
                    }
                    // currentPageIndex will be coerced by setupPageIds in loadCurrentPage.
                }
            }

            is UndoRedoAction.CrossNotebookPagesRemoved -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.pageIds.forEach { pageId ->
                        dao.restoreById(pageId, now)
                        dao.restoreChildrenDeletedSince(pageId, action.deletedAt, now)
                        val layer = dao.getLayerForPageAny(pageId)
                        if (layer != null) {
                            dao.restoreChildrenDeletedSince(layer.id, action.deletedAt, now)
                        }
                        noteContentEdit(db, pageId)
                    }
                } else {
                    action.pageIds.forEach { pageId ->
                        dao.softDeleteById(pageId, now)
                        dao.softDeleteByParentId(pageId, now)
                        val layer = dao.getLayerForPageAny(pageId)
                        if (layer != null) dao.softDeleteByParentId(layer.id, now)
                    }
                }
            }

            is UndoRedoAction.TemplateChanged -> {
                val templateId = if (isUndo) action.previousTemplateId else action.newTemplateId
                withContext(Dispatchers.IO) {
                    persistPageTemplate(dao, action.pageId, templateId ?: "")
                }
                // Visual update is handled by the full page reload in step 3b below.
            }

            is UndoRedoAction.TemplatesChanged -> withContext(Dispatchers.IO) {
                // Revert (undo) or re-apply (redo) every page's template in one atomic step.
                action.changes.forEach { ref ->
                    val templateId = if (isUndo) ref.previousTemplateId else ref.newTemplateId
                    persistPageTemplate(dao, ref.pageId, templateId ?: "")
                    noteContentEdit(db, ref.pageId)
                }
                // Visual update for the open page is handled by the full page reload in step 3b below.
            }

            is UndoRedoAction.PageEraseAll -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    // Restore all content soft-deleted by the clear (timestamp-based, type-agnostic).
                    dao.restoreChildrenDeletedSince(action.layerId, action.deletedAt, now)
                    // Belt-and-suspenders: restore heading rows explicitly by ID.
                    // restoreChildrenDeletedSince filters by deletedAt >= since, which should cover
                    // these rows, but explicit ID restore ensures headings are never silently skipped.
                    action.headingIds.forEach { dao.restoreById(it, now) }
                } else {
                    // Redo: soft-delete all surviving content on this layer.
                    dao.softDeleteByParentId(action.layerId, now)
                }
            }

            is UndoRedoAction.PagePasted -> {
                if (isUndo) {
                    val deletedAt = withContext(Dispatchers.IO) {
                        val ts = System.currentTimeMillis()
                        dao.softDeleteById(action.pageId, ts)
                        dao.softDeleteByParentId(action.pageId, ts)          // layer
                        val layer = dao.getLayerForPageAny(action.pageId)
                        if (layer != null) dao.softDeleteByParentId(layer.id, ts) // strokes
                        ts
                    }
                    // Store the soft-delete timestamp so redo can restore exactly these rows.
                    undoRedoManager.amendLastRedo(action.copy(undoDeletedAt = deletedAt))
                    currentPageIndex = (action.pageIndex - 1).coerceAtLeast(0)
                } else {
                    withContext(Dispatchers.IO) {
                        dao.restoreById(action.pageId, now)
                        val layer = dao.getLayerForPageAny(action.pageId)
                        if (layer != null) {
                            dao.restoreById(layer.id, now)
                            if (action.undoDeletedAt > 0) {
                                dao.restoreChildrenDeletedSince(layer.id, action.undoDeletedAt, now)
                            }
                        }
                    }
                    currentPageIndex = action.pageIndex
                }
            }

            is UndoRedoAction.PageMoved -> withContext(Dispatchers.IO) {
                // Undo: put the page back where it was; redo: put it at the target position.
                val afterId = if (isUndo) action.previousAfterPageId else action.targetPageId
                movePageToAfter(db, action.pageId, afterId)
                // currentPageIndex is resolved by setupPageIds in the full reload below.
            }

            is UndoRedoAction.PagesPasted -> {
                // Undo: soft-delete every pasted page + its children.
                // Redo: restore every page + its children (using per-ref undoDeletedAt).
                // Each ref's undoDeletedAt starts at 0; after undo it is patched via amendLastRedo
                // so redo knows which rows to restore (same pattern as single PagePasted).
                if (isUndo) {
                    val updatedRefs = withContext(Dispatchers.IO) {
                        action.pages.map { ref ->
                            val ts = System.currentTimeMillis()
                            dao.softDeleteById(ref.pageId, ts)
                            dao.softDeleteByParentId(ref.pageId, ts)      // layer
                            val layer = dao.getLayerForPageAny(ref.pageId)
                            if (layer != null) dao.softDeleteByParentId(layer.id, ts) // strokes
                            ref.copy(undoDeletedAt = ts)
                        }
                    }
                    undoRedoManager.amendLastRedo(action.copy(pages = updatedRefs))
                    // Land just before the first pasted page (same heuristic as single PagePasted).
                    val firstPastedIndex = action.pages.minOfOrNull { it.pageIndex } ?: 1
                    currentPageIndex = (firstPastedIndex - 1).coerceAtLeast(0)
                } else {
                    withContext(Dispatchers.IO) {
                        action.pages.forEach { ref ->
                            dao.restoreById(ref.pageId, now)
                            val layer = dao.getLayerForPageAny(ref.pageId)
                            if (layer != null) {
                                dao.restoreById(layer.id, now)
                                if (ref.undoDeletedAt > 0) {
                                    dao.restoreChildrenDeletedSince(layer.id, ref.undoDeletedAt, now)
                                }
                            }
                            // Invalidate snapshot for each restored page so the index shows
                            // current content (mirrors what single PagePasted does in step 3b).
                            noteContentEdit(db, ref.pageId)
                        }
                    }
                    currentPageIndex = action.pages.minOfOrNull { it.pageIndex }
                        ?.coerceAtLeast(0) ?: currentPageIndex
                }
            }

            is UndoRedoAction.PagesMoved -> withContext(Dispatchers.IO) {
                // moves is in original document order and the moved pages form a contiguous block,
                // so BOTH undo and redo process forward:
                //  - undo moves each page after its original predecessor (previousAfterPageId),
                //  - redo moves each page after its post-move predecessor (newAfterPageId).
                // Forward order ensures a predecessor that is itself in the block is already placed
                // before this page is moved relative to it. Cross-operation ordering is handled by
                // the undo stack (one PagesMoved per move operation).
                if (isUndo) {
                    action.moves.forEach { ref ->
                        movePageToAfter(db, ref.pageId, ref.previousAfterPageId)
                    }
                } else {
                    action.moves.forEach { ref ->
                        movePageToAfter(db, ref.pageId, ref.newAfterPageId)
                    }
                }
                // currentPageIndex is resolved by setupPageIds in the full reload below.
            }

            is UndoRedoAction.LassoErased -> withContext(Dispatchers.IO) {
                // strokeIds already contains heading + text IDs; textIds is a redundant subset
                // stored for undo/redo partitioning. All three sets are covered by strokeIds.
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.stickyNoteIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.stickyNoteIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.stickyNoteIds.isNotEmpty() || action.shapeIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LassoCut -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                    action.textIds.forEach { dao.restoreById(it, now) }
                    action.lineIds.forEach { dao.restoreById(it, now) }
                    action.linkIds.forEach { dao.restoreById(it, now) }
                    action.stickyNoteIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                    action.textIds.forEach { dao.softDeleteById(it, now) }
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                    action.linkIds.forEach { dao.softDeleteById(it, now) }
                    action.stickyNoteIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.linkIds.isNotEmpty() || action.stickyNoteIds.isNotEmpty() || action.shapeIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LassoDeleted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                    action.textIds.forEach { dao.restoreById(it, now) }
                    action.lineIds.forEach { dao.restoreById(it, now) }
                    action.linkIds.forEach { dao.restoreById(it, now) }
                    action.stickyNoteIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                    action.textIds.forEach { dao.softDeleteById(it, now) }
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                    action.linkIds.forEach { dao.softDeleteById(it, now) }
                    action.stickyNoteIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                }
                if (action.linkIds.isNotEmpty() || action.stickyNoteIds.isNotEmpty() || action.shapeIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LassoPasted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.strokeIds.forEach { dao.softDeleteById(it, now) }
                    action.headingIds.forEach { dao.softDeleteById(it, now) }
                    action.textIds.forEach { dao.softDeleteById(it, now) }
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                    action.linkIds.forEach { dao.softDeleteById(it, now) }
                    action.stickyNoteIds.forEach { dao.softDeleteById(it, now) }
                    action.shapeIds.forEach { dao.softDeleteById(it, now) }
                } else {
                    action.strokeIds.forEach { dao.restoreById(it, now) }
                    action.headingIds.forEach { dao.restoreById(it, now) }
                    action.textIds.forEach { dao.restoreById(it, now) }
                    action.lineIds.forEach { dao.restoreById(it, now) }
                    action.linkIds.forEach { dao.restoreById(it, now) }
                    action.stickyNoteIds.forEach { dao.restoreById(it, now) }
                    action.shapeIds.forEach { dao.restoreById(it, now) }
                }
                if (action.linkIds.isNotEmpty() || action.stickyNoteIds.isNotEmpty() || action.shapeIds.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.StrokesMoved -> withContext(Dispatchers.IO) {
                val targetStrokes      = if (isUndo) action.originalStrokes      else action.movedStrokes
                val targetHeadings     = if (isUndo) action.originalHeadings     else action.movedHeadings
                val targetTexts        = if (isUndo) action.originalTextObjects  else action.movedTextObjects
                val targetLines        = if (isUndo) action.originalLineObjects  else action.movedLineObjects
                val targetLinks        = if (isUndo) action.originalLinks        else action.movedLinks
                val targetStickyNotes  = if (isUndo) action.originalStickyNotes else action.movedStickyNotes
                val density            = resources.displayMetrics.density
                db.withTransaction {
                    for (stroke in targetStrokes) {
                        dao.updateStrokeBlob(stroke.id, stroke.strokeBlob(), stroke.color, stroke.strokeWidth, now)
                    }
                    for (heading in targetHeadings) {
                        dao.replaceHeadingSubtree(heading, now)
                    }
                    for (textObj in targetTexts) {
                        dao.replaceTextSubtree(textObj, now)
                    }
                    for (lineObj in targetLines) {
                        dao.updateColumns(lineObj.toRow("", 0, now, now, density))
                    }
                    for (link in targetLinks) {
                        dao.replaceLinkSubtree(link, now, density)
                    }
                    for (note in targetStickyNotes) {
                        dao.replaceStickyNoteSubtree(note, now, density)
                    }
                    val targetShapes = if (isUndo) action.originalShapes else action.movedShapes
                    for (shape in targetShapes) {
                        dao.updateColumns(shape.toRow("", 0, now, now, density))
                    }
                }
                if (action.movedLinks.isNotEmpty() || action.movedStickyNotes.isNotEmpty() || action.movedShapes.isNotEmpty()) noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.HeadingCreated -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.headingId, now)
                    action.originalStrokeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.originalStrokeIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.headingId, now)
                }
            }

            is UndoRedoAction.HeadingTextEdited -> {
                val pageWidthForEdit = resources.displayMetrics.widthPixels
                withContext(Dispatchers.IO) {
                val targetText = if (isUndo) action.previousText else action.newText
                val row = dao.getObjectById(action.headingId) ?: return@withContext
                val base = db.notebookDao().assembleHeadingResolved(row) ?: return@withContext
                if (targetText != null) {
                    val textPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
                    }
                    val paddingPx = 8f * resources.displayMetrics.density
                    val box = base.boundingBox
                    val (measuredW, measuredH) = TextObjectRenderer.measure(targetText, pageWidthForEdit, textPaint, resources.displayMetrics.density, singleLine = true)
                    val newBox = RectF(box.left, box.top, box.left + measuredW + 2f * paddingPx, box.top + measuredH + 2f * paddingPx)
                    dao.replaceHeadingSubtree(base.copy(recognizedText = targetText, boundingBox = newBox), now)
                } else {
                    // Restoring null: keep existing bounding box, just clear the text field.
                    dao.replaceHeadingSubtree(base.copy(recognizedText = targetText), now)
                }
            }}

            is UndoRedoAction.HeadingLevelChanged -> withContext(Dispatchers.IO) {
                // Boxes are pre-computed and stored in the action — write directly, no re-measure needed.
                val targetLevel = if (isUndo) action.previousLevel else action.newLevel
                val targetText  = if (isUndo) action.previousText  else action.newText
                val targetBox   = if (isUndo) action.previousBox   else action.newBox
                val row     = dao.getObjectById(action.headingId) ?: return@withContext
                val updated = (db.notebookDao().assembleHeadingResolved(row) ?: return@withContext)
                    .copy(recognizedText = targetText, level = targetLevel, boundingBox = targetBox)
                dao.replaceHeadingSubtree(updated, now)
            }

            is UndoRedoAction.TextInserted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.textId, now)
                } else {
                    dao.restoreById(action.textId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.TextEdited -> withContext(Dispatchers.IO) {
                val target = if (isUndo) action.oldTextRender else action.newTextRender
                dao.replaceTextSubtree(target, now)
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.TextRemoved -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.restoreById(action.textId, now)
                } else {
                    dao.softDeleteById(action.textId, action.deletedAt)
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.TextConverted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.textId, now)
                    action.originalStrokeIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.originalStrokeIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.textId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.ShapeCreated -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.shapeId, now)
                    if (action.deletedAt == 0L) {
                        val stroke = action.originalStroke
                        // insertOrIgnore: undo→redo→undo repeats this branch; the row from the
                        // first undo still exists (redo only soft-deleted it), and a plain insert
                        // would abort with a UNIQUE crash. Restore covers the soft-deleted case.
                        dao.insertOrIgnore(
                            stroke.toStrokeRow(parentId = action.layerId, order = 0, createdAt = now, updatedAt = now)
                        )
                        dao.restoreById(stroke.id, now)
                    } else {
                        dao.restoreById(action.originalStroke.id, now)
                    }
                } else {
                    dao.softDeleteById(action.originalStroke.id, now)
                    dao.restoreById(action.shapeId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.ShapeTransformed -> withContext(Dispatchers.IO) {
                val target  = if (isUndo) action.before else action.after
                val density = resources.displayMetrics.density
                dao.updateColumns(target.toRow("", 0, now, now, density))
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.ScribbleErased -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.erasedObjectIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.erasedObjectIds.forEach { dao.softDeleteById(it, now) }
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LinesInserted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                } else {
                    action.lineIds.forEach { dao.restoreById(it, now) }
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LinesRemoved -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    action.lineIds.forEach { dao.restoreById(it, now) }
                } else {
                    action.lineIds.forEach { dao.softDeleteById(it, now) }
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LinkCreated -> withContext(Dispatchers.IO) {
                val originalIds = action.originalStrokeIds + action.originalHeadingIds + action.originalTextIds + action.originalLineIds + action.originalShapeIds
                if (isUndo) {
                    dao.softDeleteById(action.linkId, now)
                    originalIds.forEach { dao.restoreById(it, now) }
                } else {
                    originalIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.linkId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LinkRemoved -> withContext(Dispatchers.IO) {
                val restoredIds = action.restoredStrokeIds + action.restoredHeadingIds + action.restoredTextIds + action.restoredLineIds + action.restoredShapeIds
                if (isUndo) {
                    restoredIds.forEach { dao.softDeleteById(it, now) }
                    dao.restoreById(action.linkId, now)
                } else {
                    dao.softDeleteById(action.linkId, now)
                    restoredIds.forEach { dao.restoreById(it, now) }
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.LinkEdited -> withContext(Dispatchers.IO) {
                val density = resources.displayMetrics.density
                val link = dao.loadLinkSubtree(action.linkId, density)
                if (link != null) {
                    val chrome = if (isUndo) action.oldChrome else action.newChrome
                    val target = if (isUndo) action.oldTarget else action.newTarget
                    db.withTransaction { dao.replaceLinkSubtree(link.copy(chrome = chrome, target = target), now, density) }
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.StickyNoteInserted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.noteId, now)
                } else {
                    dao.restoreById(action.noteId, now)
                }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.StickyNoteContentEdited -> withContext(Dispatchers.IO) {
                val density = resources.displayMetrics.density
                val target  = if (isUndo) action.before else action.after
                db.withTransaction { dao.replaceStickyNoteSubtree(target, now, density) }
                noteContentEdit(db, action.pageId)
            }

            is UndoRedoAction.ShapeInserted -> withContext(Dispatchers.IO) {
                if (isUndo) {
                    dao.softDeleteById(action.shapeId, now)
                } else {
                    dao.restoreById(action.shapeId, now)
                }
                noteContentEdit(db, action.pageId)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleSamePageAction(
        db: SoilDatabase,
        action: UndoRedoAction,
        isUndo: Boolean,
        now: Long,
        dao: com.notesprout.android.data.NotebookDao,
    ): Boolean {
        // ── Same-page StrokesMoved — optimised in-memory update ──────────────
        if (action is UndoRedoAction.StrokesMoved) {
            val targetStrokes      = if (isUndo) action.originalStrokes      else action.movedStrokes
            val targetHeadings     = if (isUndo) action.originalHeadings     else action.movedHeadings
            val targetTexts        = if (isUndo) action.originalTextObjects  else action.movedTextObjects
            val targetLines        = if (isUndo) action.originalLineObjects  else action.movedLineObjects
            val strokeById = targetStrokes.associateBy { it.id }
            val updatedStrokes = drawingView.getStrokes().map { strokeById[it.id] ?: it }
            val updatedHeadings = if (targetHeadings.isNotEmpty()) {
                val headingById = targetHeadings.associateBy { it.id }
                drawingView.getHeadings().map { headingById[it.id] ?: it }
            } else {
                drawingView.getHeadings()
            }
            val updatedTexts = if (targetTexts.isNotEmpty()) {
                val textById = targetTexts.associateBy { it.id }
                drawingView.getTextObjects().map { textById[it.id] ?: it }
            } else {
                drawingView.getTextObjects()
            }
            val updatedLines = if (targetLines.isNotEmpty()) {
                val lineById = targetLines.associateBy { it.id }
                drawingView.getLineObjects().map { lineById[it.id] ?: it }
            } else {
                drawingView.getLineObjects()
            }
            val targetLinks = if (isUndo) action.originalLinks else action.movedLinks
            val updatedLinks = if (targetLinks.isNotEmpty()) {
                val linkById = targetLinks.associateBy { it.id }
                drawingView.getLinks().map { linkById[it.id] ?: it }
            } else {
                drawingView.getLinks()
            }
            val targetStickyNotes = if (isUndo) action.originalStickyNotes else action.movedStickyNotes
            val updatedStickyNotes = if (targetStickyNotes.isNotEmpty()) {
                val stickyById = targetStickyNotes.associateBy { it.id }
                drawingView.getStickyNotes().map { stickyById[it.id] ?: it }
            } else {
                drawingView.getStickyNotes()
            }
            val targetShapes = if (isUndo) action.originalShapes else action.movedShapes
            val updatedShapes = if (targetShapes.isNotEmpty()) {
                val shapeById = targetShapes.associateBy { it.id }
                drawingView.getShapeObjects().map { shapeById[it.id] ?: it }
            } else {
                drawingView.getShapeObjects()
            }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            // If the moved objects are the active selection, update the selection box.
            val movedIds = (action.originalStrokes.map { it.id } +
                action.originalHeadings.map { it.id } +
                action.originalTextObjects.map { it.id } +
                action.originalLineObjects.map { it.id } +
                action.originalLinks.map { it.id } +
                action.originalStickyNotes.map { it.id } +
                action.originalShapes.map { it.id }).toSet()
            if (movedIds == selectedObjectIds) {
                val unionBounds = computeUnionBoundingBox(targetStrokes, targetHeadings)
                targetTexts.forEach { unionBounds.union(it.boundingBox) }
                targetLines.forEach { unionBounds.union(it.boundingBox) }
                targetLinks.forEach { unionBounds.union(it.boundingBox) }
                targetStickyNotes.forEach { unionBounds.union(it.boundingBox) }
                targetShapes.forEach { unionBounds.union(it.boundingBox) }
                val pad = 8f * resources.displayMetrics.density
                unionBounds.inset(-pad, -pad)
                drawingView.setLassoOverlay(null, unionBounds)
                drawingView.lassoSelectedIds = movedIds
                updateFloatingSelectionToolbar(unionBounds)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-batch: Same-page LassoErased — optimised in-memory update ─
        // strokeIds contains ALL erased IDs (strokes + headings + text objects);
        // headingIds, textIds, lineIds are the respective subsets.
        if (action is UndoRedoAction.LassoErased) {
            val idsSet           = action.strokeIds.toSet()
            val headingIdsSet    = action.headingIds.toSet()
            val textIdsSet       = action.textIds.toSet()
            val lineIdsSet       = action.lineIds.toSet()
            val linkIdsSet       = action.linkIds.toSet()
            val stickyIdsSet     = action.stickyNoteIds.toSet()
            val shapeIdsSet      = action.shapeIds.toSet()
            val pureStrokeIdsSet = idsSet - headingIdsSet - textIdsSet - lineIdsSet - linkIdsSet - stickyIdsSet - shapeIdsSet
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedLinks: List<LinkRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo: re-erase → remove from in-memory lists.
                updatedStrokes      = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings     = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                updatedTexts        = drawingView.getTextObjects().filter { it.id !in textIdsSet }
                updatedLines        = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
                updatedLinks        = drawingView.getLinks().filter { it.id !in linkIdsSet }
                updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in stickyIdsSet }
                updatedShapes       = drawingView.getShapeObjects().filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(pureStrokeIdsSet)
            } else {
                // Undo: restore strokes from DB; use action.headings/textObjects/lines (no DB fetch).
                val restoredStrokes = withContext(Dispatchers.IO) {
                    pureStrokeIdsSet.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoErased: failed to deserialize ${obj.id}", e); null
                            }
                        }
                    }
                }
                // If headings field is populated (new format), use it directly.
                // Otherwise fall back to DB fetch for backward compat with old undo stacks.
                val restoredHeadings: List<HeadingStroke> = if (action.headings.isNotEmpty()) {
                    action.headings
                } else if (headingIdsSet.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        headingIdsSet.mapNotNull { id ->
                            dao.getObjectById(id)?.let { dao.assembleHeadingResolved(it) }
                        }
                    }
                } else emptyList()
                updatedStrokes      = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings     = drawingView.getHeadings() + restoredHeadings
                updatedTexts        = drawingView.getTextObjects() + action.textObjects
                updatedLines        = drawingView.getLineObjects() + action.lines
                updatedLinks        = drawingView.getLinks() + action.links
                updatedStickyNotes  = drawingView.getStickyNotes() + action.stickyNotes
                updatedShapes       = drawingView.getShapeObjects() + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-scribble: Same-page ScribbleErased — optimised in-memory update ─
        if (action is UndoRedoAction.ScribbleErased) {
            val erasedIdsSet     = action.erasedObjectIds.toSet()
            val headingIdsSet    = action.headingIds.toSet()
            val textIdsSet       = action.textIds.toSet()
            val lineIdsSet       = action.lineIds.toSet()
            val linkIdsSet       = action.linkIds.toSet()
            val stickyIdsSet     = action.stickyNoteIds.toSet()
            val shapeIdsSet      = action.shapeIds.toSet()
            val pureStrokeIdsSet = erasedIdsSet - headingIdsSet - textIdsSet - lineIdsSet - linkIdsSet - stickyIdsSet - shapeIdsSet
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedLinks: List<LinkRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo: re-erase → remove erased objects from in-memory (scribble was already gone).
                updatedStrokes      = drawingView.getStrokes().filter { it.id !in erasedIdsSet }
                updatedHeadings     = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                updatedTexts        = drawingView.getTextObjects().filter { it.id !in textIdsSet }
                updatedLines        = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
                updatedLinks        = drawingView.getLinks().filter { it.id !in linkIdsSet }
                updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in stickyIdsSet }
                updatedShapes       = drawingView.getShapeObjects().filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(pureStrokeIdsSet)
            } else {
                // Undo: restore all erased objects from DB / action snapshot.
                val erasedStrokeRows = withContext(Dispatchers.IO) {
                    pureStrokeIdsSet.mapNotNull { id -> dao.getObjectById(id) }
                }
                val restoredErasedStrokes = erasedStrokeRows.mapNotNull { obj ->
                    try { LiveStroke.fromRow(obj) }
                    catch (e: Exception) {
                        Log.e(TAG, "executeAction ScribbleErased undo: failed to deserialize ${obj.id}", e); null
                    }
                }
                val restoredHeadings: List<HeadingStroke> = if (action.headings.isNotEmpty()) {
                    action.headings
                } else if (headingIdsSet.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        headingIdsSet.mapNotNull { id ->
                            dao.getObjectById(id)?.let { dao.assembleHeadingResolved(it) }
                        }
                    }
                } else emptyList()
                updatedStrokes  = buildList {
                    addAll(drawingView.getStrokes())
                    addAll(restoredErasedStrokes)
                }
                updatedHeadings     = drawingView.getHeadings() + restoredHeadings
                updatedTexts        = drawingView.getTextObjects() + action.textObjects
                updatedLines        = drawingView.getLineObjects() + action.lines
                updatedLinks        = drawingView.getLinks() + action.links
                updatedStickyNotes  = drawingView.getStickyNotes() + action.stickyNotes
                updatedShapes       = drawingView.getShapeObjects() + action.shapes
                restoredErasedStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-cut: Same-page LassoCut — optimised in-memory update ─────────
        if (action is UndoRedoAction.LassoCut) {
            val idsSet            = action.strokeIds.toSet()
            val headingIdsSet     = action.headingIds.toSet()
            val textIdsSet        = action.textIds.toSet()
            val lineIdsSet        = action.lineIds.toSet()
            val linkIdsSet        = action.linkIds.toSet()
            val stickyIdsSet      = action.stickyNoteIds.toSet()
            val shapeIdsSet       = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedLinks: List<LinkRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo cut: remove from in-memory lists and repopulate clipboard.
                updatedStrokes      = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings     = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                updatedTexts        = drawingView.getTextObjects().filter { it.id !in textIdsSet }
                updatedLines        = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
                updatedLinks        = drawingView.getLinks().filter { it.id !in linkIdsSet }
                updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in stickyIdsSet }
                updatedShapes       = drawingView.getShapeObjects().filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet)
                val clipBox = computeUnionBoundingBox(action.strokes, action.headings)
                action.textObjects.forEach { clipBox.union(it.boundingBox) }
                action.lines.forEach { clipBox.union(it.boundingBox) }
                action.links.forEach { clipBox.union(it.boundingBox) }
                action.stickyNotes.forEach { clipBox.union(it.boundingBox) }
                action.shapes.forEach { clipBox.union(it.boundingBox) }
                NotesproutClipboard.content = NotesproutClipboard.ClipboardContent(
                    strokes     = action.strokes.map { s -> s.deepCopy() },
                    headings    = action.headings.map { h -> HeadingStroke(h.id, RectF(h.boundingBox),
                        h.strokes.map { s -> s.deepCopy() },
                        recognizedText = h.recognizedText,
                        level = h.level) },
                    boundingBox = clipBox,
                    textObjects = action.textObjects.map { t -> TextRender(t.id, RectF(t.boundingBox), t.text, t.strokes) },
                    lineObjects = action.lines,
                    links       = action.links.map { it.translate(0f, 0f) },
                    stickyNotes = action.stickyNotes.map { it.translate(0f, 0f) },
                    shapeObjects = action.shapes.map { it.copy(boundingBox = RectF(it.boundingBox)) },
                )
                updateLassoButtonIcon()
            } else {
                // Undo cut: fetch restored strokes from DB; use action.headings/textObjects/lines/links/stickyNotes.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoCut: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes      = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings     = drawingView.getHeadings() + action.headings
                updatedTexts        = drawingView.getTextObjects() + action.textObjects
                updatedLines        = drawingView.getLineObjects() + action.lines
                updatedLinks        = drawingView.getLinks() + action.links
                updatedStickyNotes  = drawingView.getStickyNotes() + action.stickyNotes
                updatedShapes       = drawingView.getShapeObjects() + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, stickyNotes = updatedStickyNotes, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-delete: Same-page LassoDeleted — optimised in-memory update ─
        if (action is UndoRedoAction.LassoDeleted) {
            val idsSet        = action.strokeIds.toSet()
            val headingIdsSet = action.headingIds.toSet()
            val textIdsSet    = action.textIds.toSet()
            val lineIdsSet    = action.lineIds.toSet()
            val linkIdsSet    = action.linkIds.toSet()
            val stickyIdsSet  = action.stickyNoteIds.toSet()
            val shapeIdsSet   = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedLinks: List<LinkRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (!isUndo) {
                // Redo delete: remove from in-memory lists.
                updatedStrokes      = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings     = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                updatedTexts        = drawingView.getTextObjects().filter { it.id !in textIdsSet }
                updatedLines        = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
                updatedLinks        = drawingView.getLinks().filter { it.id !in linkIdsSet }
                updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in stickyIdsSet }
                updatedShapes       = drawingView.getShapeObjects().filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Undo delete: fetch restored strokes from DB; use action.headings/textObjects/lines/links.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoDeleted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes      = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings     = drawingView.getHeadings() + action.headings
                updatedTexts        = drawingView.getTextObjects() + action.textObjects
                updatedLines        = drawingView.getLineObjects() + action.lines
                updatedLinks        = drawingView.getLinks() + action.links
                updatedStickyNotes  = drawingView.getStickyNotes() + action.stickyNotes
                updatedShapes       = drawingView.getShapeObjects() + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-heading: Same-page HeadingCreated — optimised in-memory update ─
        if (action is UndoRedoAction.HeadingCreated) {
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            if (isUndo) {
                // Heading soft-deleted, original strokes restored.
                updatedHeadings = drawingView.getHeadings().filter { it.id != action.headingId }
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.originalStrokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction HeadingCreated: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            } else {
                // Original strokes soft-deleted, heading restored.
                val idsSet = action.originalStrokeIds.toSet()
                updatedStrokes = drawingView.getStrokes().filter { it.id !in idsSet }
                persistedStrokeIds.removeAll(idsSet)
                val headingBox = headingBoundingBox(action.embeddedStrokes, action.recognizedText)
                val newHeading = HeadingStroke(id = action.headingId, boundingBox = headingBox, strokes = action.embeddedStrokes, recognizedText = action.recognizedText, level = action.level)
                updatedHeadings = drawingView.getHeadings() + newHeading
            }
            drawingView.loadHeadings(updatedHeadings)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-paste: Same-page LassoPasted — optimised in-memory update ──
        if (action is UndoRedoAction.LassoPasted) {
            val idsSet            = action.strokeIds.toSet()
            val headingIdsSet     = action.headingIds.toSet()
            val textIdsSet        = action.textIds.toSet()
            val lineIdsSet        = action.lineIds.toSet()
            val linkIdsSet        = action.linkIds.toSet()
            val stickyIdsSet      = action.stickyNoteIds.toSet()
            val shapeIdsSet       = action.shapeIds.toSet()
            val updatedStrokes: List<LiveStroke>
            val updatedHeadings: List<HeadingStroke>
            val updatedTexts: List<TextRender>
            val updatedLines: List<LineRender>
            val updatedLinks: List<LinkRender>
            val updatedStickyNotes: List<StickyNoteRender>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (isUndo) {
                // Undo paste → remove pasted strokes, headings, text objects, lines, links, sticky notes, shapes.
                updatedStrokes      = drawingView.getStrokes().filter { it.id !in idsSet }
                updatedHeadings     = drawingView.getHeadings().filter { it.id !in headingIdsSet }
                updatedTexts        = drawingView.getTextObjects().filter { it.id !in textIdsSet }
                updatedLines        = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
                updatedLinks        = drawingView.getLinks().filter { it.id !in linkIdsSet }
                updatedStickyNotes  = drawingView.getStickyNotes().filter { it.id !in stickyIdsSet }
                updatedShapes       = drawingView.getShapeObjects().filter { it.id !in shapeIdsSet }
                persistedStrokeIds.removeAll(idsSet)
            } else {
                // Redo paste → fetch restored strokes/headings from DB; use action lists for the rest.
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.strokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction LassoPasted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                val restoredHeadings = withContext(Dispatchers.IO) {
                    action.headingIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { dao.assembleHeadingResolved(it) }
                    }
                }
                updatedStrokes      = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                updatedHeadings     = drawingView.getHeadings() + restoredHeadings
                updatedTexts        = drawingView.getTextObjects() + action.textObjects
                updatedLines        = drawingView.getLineObjects() + action.lines
                updatedLinks        = drawingView.getLinks() + action.links
                updatedStickyNotes  = drawingView.getStickyNotes() + action.stickyNotes
                updatedShapes       = drawingView.getShapeObjects() + action.shapes
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            }
            drawingView.loadHeadings(updatedHeadings)
            drawingView.loadTextObjects(updatedTexts)
            drawingView.loadLineObjects(updatedLines)
            drawingView.loadLinks(updatedLinks)
            drawingView.loadStickyNotes(updatedStickyNotes)
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, updatedHeadings, updatedTexts, updatedLines, stickyNotes = updatedStickyNotes, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-text-edit: Same-page HeadingTextEdited — in-memory update ─
        if (action is UndoRedoAction.HeadingTextEdited) {
            val updatedHeadings = withContext(Dispatchers.IO) {
                loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(updatedHeadings)
            val strokes = drawingView.getStrokes()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
            // Update selection box if the edited heading is still selected.
            val updatedHeading = updatedHeadings.find { it.id == action.headingId }
            if (updatedHeading != null && action.headingId in selectedObjectIds) {
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(updatedHeading.boundingBox).also { it.inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                    updateFloatingSelectionToolbar(selBox)
                }
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-level-change: Same-page HeadingLevelChanged — in-memory update ─
        if (action is UndoRedoAction.HeadingLevelChanged) {
            val updatedHeadings = withContext(Dispatchers.IO) {
                loadHeadingsFromDb(db, currentLayerId)
            }
            drawingView.loadHeadings(updatedHeadings)
            val strokes = drawingView.getStrokes()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(strokes, templateBmp, updatedHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(strokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(strokes)
            }
            // Update selection box if the changed heading is still selected.
            val updatedHeading = updatedHeadings.find { it.id == action.headingId }
            if (updatedHeading != null && action.headingId in selectedObjectIds) {
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(updatedHeading.boundingBox).also { it.inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                    updateFloatingSelectionToolbar(selBox)
                }
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-text-insert: Same-page TextInserted — in-memory update ─────
        if (action is UndoRedoAction.TextInserted) {
            val updatedTexts: List<TextRender>
            if (isUndo) {
                // Undo: remove the text object.
                updatedTexts = drawingView.getTextObjects().filter { it.id != action.textId }
            } else {
                // Redo: re-add the text object (it was already restored in DB branch above).
                updatedTexts = drawingView.getTextObjects() + action.textRender
            }
            drawingView.loadTextObjects(updatedTexts)
            val currentStrokes = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            // Undo clears selection; redo re-selects the inserted object.
            if (isUndo) {
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            } else {
                if (!isLassoMode) enterLassoMode()
                selectedObjectIds.clear()
                selectedObjectIds.add(action.textId)
                drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(action.textRender.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-text-edit: Same-page TextEdited — in-memory update ─────────
        if (action is UndoRedoAction.TextEdited) {
            val target = if (isUndo) action.oldTextRender else action.newTextRender
            val updatedTexts = drawingView.getTextObjects().map { t ->
                if (t.id == action.textId) target else t
            }
            drawingView.loadTextObjects(updatedTexts)
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            // Refresh selection highlight to match the target bounding box.
            if (action.textId in selectedObjectIds) {
                val pad    = 8f * resources.displayMetrics.density
                val selBox = RectF(target.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                if (binding.floatingSelectionToolbar.visibility == View.VISIBLE) {
                    updateFloatingSelectionToolbar(selBox)
                }
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-text-remove: Same-page TextRemoved — in-memory update ──────
        if (action is UndoRedoAction.TextRemoved) {
            val updatedTexts: List<TextRender>
            if (isUndo) {
                // Undo: restore the text object, re-select it.
                updatedTexts = drawingView.getTextObjects() + action.textRender
            } else {
                // Redo: remove the text object, clear selection.
                updatedTexts = drawingView.getTextObjects().filter { it.id != action.textId }
            }
            drawingView.loadTextObjects(updatedTexts)
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            if (isUndo) {
                if (!isLassoMode) enterLassoMode()
                selectedObjectIds.clear()
                selectedObjectIds.add(action.textId)
                drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                val pad    = 8f * resources.displayMetrics.density
                val selBox = RectF(action.textRender.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            } else {
                selectedObjectIds.remove(action.textId)
                drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                if (selectedObjectIds.isEmpty()) {
                    drawingView.setLassoOverlay(null, null)
                    hideFloatingSelectionToolbar()
                }
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-text-converted: Same-page TextConverted — in-memory update ─
        if (action is UndoRedoAction.TextConverted) {
            val updatedStrokes: List<LiveStroke>
            val updatedTexts: List<TextRender>
            if (isUndo) {
                // Undo: text row soft-deleted, original strokes restored.
                updatedTexts = drawingView.getTextObjects().filter { it.id != action.textId }
                val restoredStrokes = withContext(Dispatchers.IO) {
                    action.originalStrokeIds.mapNotNull { id ->
                        dao.getObjectById(id)?.let { obj ->
                            try { LiveStroke.fromRow(obj) }
                            catch (e: Exception) {
                                Log.e(TAG, "executeAction TextConverted: failed to deserialize $id", e); null
                            }
                        }
                    }
                }
                updatedStrokes = buildList { addAll(drawingView.getStrokes()); addAll(restoredStrokes) }
                restoredStrokes.forEach { persistedStrokeIds.add(it.id) }
            } else {
                // Redo: original strokes soft-deleted, text row restored.
                val idsSet = action.originalStrokeIds.toSet()
                updatedStrokes = drawingView.getStrokes().filter { it.id !in idsSet }
                persistedStrokeIds.removeAll(idsSet)
                updatedTexts = drawingView.getTextObjects() + action.textRender
            }
            drawingView.loadTextObjects(updatedTexts)
            val templateBmp = currentTemplateBitmap
            val currentHeadings = drawingView.getHeadings()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, currentHeadings, updatedTexts)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            // Undo clears selection; redo re-selects the converted text object.
            if (isUndo) {
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            } else {
                if (!isLassoMode) enterLassoMode()
                selectedObjectIds.clear()
                selectedObjectIds.add(action.textId)
                drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(action.textRender.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-shape-created: Same-page ShapeCreated — in-memory update ──
        if (action is UndoRedoAction.ShapeCreated) {
            val updatedStrokes: List<LiveStroke>
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (isUndo) {
                // Undo: shape row soft-deleted, original stroke back in memory
                updatedShapes  = drawingView.getShapeObjects().filter { it.id != action.shapeId }
                updatedStrokes = buildList { addAll(drawingView.getStrokes()); add(action.originalStroke) }
                persistedStrokeIds.add(action.originalStroke.id)
            } else {
                // Redo: stroke soft-deleted, shape back in memory
                updatedStrokes = drawingView.getStrokes().filter { it.id != action.originalStroke.id }
                persistedStrokeIds.remove(action.originalStroke.id)
                updatedShapes  = drawingView.getShapeObjects() + action.shape
            }
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp     = currentTemplateBitmap
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, currentHeadings, currentTexts, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            if (isUndo) {
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            } else {
                if (!isLassoMode) enterLassoMode()
                selectedObjectIds.clear()
                selectedObjectIds.add(action.shapeId)
                drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(action.shape.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-shape-transformed: Same-page ShapeTransformed — in-memory update ─
        if (action is UndoRedoAction.ShapeTransformed) {
            val target = if (isUndo) action.before else action.after
            val updatedShapes = drawingView.getShapeObjects().map { if (it.id == target.id) target else it }
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp     = currentTemplateBitmap
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, currentLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            if (!isLassoMode) enterLassoMode()
            selectedObjectIds.clear()
            selectedObjectIds.add(target.id)
            drawingView.lassoSelectedIds = selectedObjectIds.toSet()
            val pad = 8f * resources.displayMetrics.density
            val selBox = RectF(target.boundingBox).apply { inset(-pad, -pad) }
            drawingView.setLassoOverlay(null, selBox)
            updateFloatingSelectionToolbar(selBox)
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-lines-inserted: Same-page LinesInserted — in-memory update ─
        if (action is UndoRedoAction.LinesInserted) {
            val lineIdsSet = action.lineIds.toSet()
            val updatedLines: List<LineRender>
            if (isUndo) {
                updatedLines = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
            } else {
                updatedLines = drawingView.getLineObjects() + action.lines
            }
            drawingView.loadLineObjects(updatedLines)
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            if (isUndo) {
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            } else {
                if (!isLassoMode) enterLassoMode()
                val newIds = action.lineIds.toSet()
                selectedObjectIds.clear()
                selectedObjectIds.addAll(newIds)
                drawingView.lassoSelectedIds = newIds
                val unionBox = action.lines.fold(RectF()) { acc, l ->
                    if (acc.isEmpty) RectF(l.boundingBox) else acc.also { it.union(l.boundingBox) }
                }
                val pad = 8f * resources.displayMetrics.density
                unionBox.inset(-pad, -pad)
                drawingView.setLassoOverlay(null, unionBox)
                updateFloatingSelectionToolbar(unionBox)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-lines-removed: Same-page LinesRemoved — in-memory update ─
        if (action is UndoRedoAction.LinesRemoved) {
            val lineIdsSet = action.lineIds.toSet()
            val updatedLines: List<LineRender>
            if (isUndo) {
                updatedLines = drawingView.getLineObjects() + action.lines
            } else {
                updatedLines = drawingView.getLineObjects().filter { it.id !in lineIdsSet }
            }
            drawingView.loadLineObjects(updatedLines)
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val templateBmp = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, updatedLines)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            selectedObjectIds.clear()
            drawingView.lassoSelectedIds = emptySet()
            drawingView.setLassoOverlay(null, null)
            hideFloatingSelectionToolbar()
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-sticky-insert: Same-page StickyNoteInserted — in-memory update ─
        if (action is UndoRedoAction.StickyNoteInserted) {
            val updatedNotes: List<StickyNoteRender>
            if (isUndo) {
                updatedNotes = drawingView.getStickyNotes().filter { it.id != action.noteId }
            } else {
                updatedNotes = drawingView.getStickyNotes() + action.note
            }
            drawingView.loadStickyNotes(updatedNotes)
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, stickyNotes = updatedNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            if (isUndo) {
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-shape-inserted: Same-page ShapeInserted — in-memory update ─
        if (action is UndoRedoAction.ShapeInserted) {
            val updatedShapes: List<com.notesprout.android.data.ShapeRender>
            if (isUndo) {
                updatedShapes = drawingView.getShapeObjects().filter { it.id != action.shapeId }
            } else {
                updatedShapes = drawingView.getShapeObjects() + action.shape
            }
            drawingView.loadShapeObjects(updatedShapes)
            val templateBmp     = currentTemplateBitmap
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val currentTexts    = drawingView.getTextObjects()
            val currentLines    = drawingView.getLineObjects()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, currentTexts, currentLines, shapeObjects = updatedShapes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            if (isUndo) {
                selectedObjectIds.clear()
                drawingView.lassoSelectedIds = emptySet()
                drawingView.setLassoOverlay(null, null)
                hideFloatingSelectionToolbar()
            } else {
                if (!isLassoMode) enterLassoMode()
                selectedObjectIds.clear()
                selectedObjectIds.add(action.shapeId)
                drawingView.lassoSelectedIds = selectedObjectIds.toSet()
                val pad = 8f * resources.displayMetrics.density
                val selBox = RectF(action.shape.boundingBox).apply { inset(-pad, -pad) }
                drawingView.setLassoOverlay(null, selBox)
                updateFloatingSelectionToolbar(selBox)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a-sticky-content: Same-page StickyNoteContentEdited — in-memory update ─
        if (action is UndoRedoAction.StickyNoteContentEdited) {
            val target = if (isUndo) action.before else action.after
            val updatedNotes = drawingView.getStickyNotes().map {
                if (it.id == action.noteId) target else it
            }
            drawingView.loadStickyNotes(updatedNotes)
            val currentStrokes  = drawingView.getStrokes()
            val currentHeadings = drawingView.getHeadings()
            val templateBmp     = currentTemplateBitmap
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(currentStrokes, templateBmp, currentHeadings, stickyNotes = updatedNotes)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(currentStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(currentStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        // ── Step 3a: Same-page stroke — optimised in-memory update ────────────
        // No eraseAll(); one EPD handoff via loadStrokesWithBitmap.
        // The snapshot stale-check uses MAX(stroke.updatedAt) > page.updatedAt,
        // which the DB op above already guarantees — no explicit invalidation needed.
        if (action is UndoRedoAction.StrokeAdded || action is UndoRedoAction.StrokeErased) {
            val strokeId = when (action) {
                is UndoRedoAction.StrokeAdded  -> action.strokeId
                is UndoRedoAction.StrokeErased -> action.strokeId
                else -> error("unreachable")
            }
            val shouldRemove = (action is UndoRedoAction.StrokeAdded  && isUndo) ||
                               (action is UndoRedoAction.StrokeErased && !isUndo)

            val updatedStrokes: List<LiveStroke>
            if (shouldRemove) {
                updatedStrokes = drawingView.getStrokes().filter { it.id != strokeId }
                persistedStrokeIds.remove(strokeId)
            } else {
                val strokeObj = withContext(Dispatchers.IO) { dao.getObjectById(strokeId) }
                val restored = strokeObj?.let {
                    try { LiveStroke.fromRow(it) }
                    catch (e: Exception) {
                        Log.e(TAG, "executeAction: failed to deserialize stroke $strokeId", e); null
                    }
                }
                updatedStrokes = buildList {
                    addAll(drawingView.getStrokes())
                    restored?.let { add(it) }
                }
                if (restored != null) persistedStrokeIds.add(strokeId)
            }

            val templateBmp = currentTemplateBitmap
            val currentHeadings = drawingView.getHeadings()
            val bitmap = withContext(Dispatchers.IO) {
                drawingView.buildRenderBitmap(updatedStrokes, templateBmp, currentHeadings)
            }
            if (bitmap != null) {
                drawingView.loadStrokesWithBitmap(updatedStrokes, bitmap, templateBmp)
            } else {
                drawingView.loadStrokes(updatedStrokes)
            }
            updatePageIndicator()
            saveLastOpenedPage(currentPageId)
            return true
        }

        return false
    }

    /**
     * Common commit point for erases, deletes, moves, and object edits: schedules real-time
     * recognition (RTR) for [pageId] so the recognized-text cache tracks all mutations, not just
     * freshly drawn ink (which [saveStrokes] covers). No longer touches any page snapshot — per-page
     * snapshots are not stored. The [db] parameter is retained for call-site symmetry.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun noteContentEdit(db: SoilDatabase, pageId: String) {
        rtrScheduler?.noteEdit(pageId)
    }

    /**
     * Decode the full-resolution template bitmap for [templateId] directly from the
     * template row in [db].  Returns null if the template row is not found or the image
     * cannot be decoded.  Must be called on [Dispatchers.IO].
     */
    private suspend fun loadTemplateBitmapById(db: SoilDatabase, templateId: String): Bitmap? {
        val templateObj = db.notebookDao().getTemplateById(templateId) ?: return null
        return try {
            val b64 = templateObj.templateDataOrNull()?.image?.takeIf { it.isNotEmpty() } ?: return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            // Bounded decode (M-1): cap to the view size (fall back to MAX_DIMENSION before layout).
            val view = drawingView.asView()
            val reqW = view.width.takeIf  { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            val reqH = view.height.takeIf { it > 0 } ?: BitmapDecode.MAX_DIMENSION
            BitmapDecode.decodeSampled(bytes, reqW, reqH)
        } catch (e: Exception) {
            Log.e(TAG, "loadTemplateBitmapById: failed to decode template $templateId", e)
            null
        }
    }

    /**
     * Returns the app-private JSON file used to persist the undo/redo stacks while the
     * app is backgrounded.  The filename encodes the notebook path so each notebook has
     * its own independent history file.  Never accessible to the user.
     */
    private fun undoRedoPersistenceFile(notebookPath: String): java.io.File =
        java.io.File(filesDir, "undo_redo_${notebookPath.hashCode()}.json")

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Physical screen bounds in pixels — used for page and layer bounding boxes. */
    @Suppress("DEPRECATION")
    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val dm = resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }

}
