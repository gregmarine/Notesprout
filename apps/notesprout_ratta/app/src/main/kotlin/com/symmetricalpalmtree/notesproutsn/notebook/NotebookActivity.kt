package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.prefs.BrowseState
import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentsPrefs
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notebook screen: a full-bleed g-paper surface with the toolbar and the name strip overlaying
 * it. Lifecycle, wiring, chrome and exclusion rects live here; the data lives in [NotebookSession]
 * / [StrokeStore]; the cover in [CoverSnapshot]; the buttons and panels in [NotebookToolbar].
 * Page gestures and undo/redo land in R4.
 *
 * Immersive (system bars hidden, transient by swipe); chrome sits flush at the top edge — the top
 * guard is 0 on Ratta hardware (`core/TopGuard.kt` holds that decision).
 */
class NotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var paper: PaperView
    private lateinit var toolbar: NotebookToolbar
    private lateinit var session: NotebookSession
    private val repo by lazy { IndexRepository() }

    private var notebookId: String = ""
    private var opened = false
    private var closing = false

    /** True while a lasso selection is up — R4's gesture detector stands down on it. */
    private var selectionActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        notebookId = intent.getStringExtra(EXTRA_NOTEBOOK_ID) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NOTEBOOK_NAME) ?: ""

        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        paper.smartLassoEnabled = false
        paper.scribbleEraseEnabled = false
        paper.setPaperListener(listener)

        // The toolbar owns all pen/eraser configuration (defaults + persistence via ToolPrefs).
        toolbar = NotebookToolbar(binding, paper, ToolPrefs(this)) { close() }
        binding.notebookName.text = name
        binding.pageIndicator.text = ""

        // Chrome moved/appeared/disappeared (incl. panel toggles — they change topBar's height):
        // re-push the exclusion rects once the pass settles.
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { close() }
        })

        BrowseState(this).lastOpenNotebookId = notebookId
        RecentsPrefs(this).record(notebookId)

        session = NotebookSession(this, notebookId, repo)
        lifecycleScope.launch { openSession() }
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private suspend fun openSession() {
        val alive = withContext(Dispatchers.IO) { repo.alive(notebookId) }
        if (alive == null) { failOpen("not in the library"); return }
        when (val r = session.open()) {
            is NotebookSession.OpenResult.Failed -> { failOpen(r.reason); return }
            NotebookSession.OpenResult.Ok -> Unit
        }
        if (isFinishing) { session.seal(); return }
        val page = session.currentPage
        val strokes = session.store.loadPage(page.id)
        paper.setPageSize(page.width, page.height)
        paper.setTemplate(session.template)
        paper.loadStrokes(strokes)
        opened = true
        setPageIndicator(session.currentIndex + 1, session.pages.size)
        Slog.d(TAG) { "page ${page.id} loaded: ${strokes.size} strokes, ${page.width}x${page.height}" }
    }

    /** A tap that opened nothing must be explained, not toasted (e-ink rule) — dialog, then leave. */
    private fun failOpen(reason: String) {
        Log.w(TAG, "open failed: $reason")
        BrowseState(this).lastOpenNotebookId = null
        if (isFinishing || isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.notebook_open_failed_title)
                .setMessage(reason)
                .setPositiveButton(R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
        ).show()
    }

    // ── g-paper → store ──────────────────────────────────────────────────────

    private val listener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) {
            if (!opened) return
            session.store.commit(session.currentPage.id, stroke)
        }
        override fun onStrokesErased(strokeIds: List<String>) {
            if (!opened) return
            session.store.erase(strokeIds)
        }
        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened) return
            session.store.move(move.strokeIds.toList(), move.dx, move.dy)
        }
        override fun onSelectionCreated(selection: Selection) { selectionActive = true }
        override fun onSelectionDismissed() { selectionActive = false }
        override fun onToolChanged(tool: Tool) { toolbar.sync(tool) }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Both bars (the top bar's rect grows over any open panel — panels are its children),
     *  translated into the paper view's coordinates, so the stylus can never ink under chrome. */
    private fun pushExclusions() {
        if (!::paper.isInitialized) return
        val paperLoc = IntArray(2).also { paper.asView().getLocationInWindow(it) }
        val rects = listOfNotNull(rectOf(binding.topBar), rectOf(binding.bottomStrip))
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects)
    }

    /**
     * Frame-silence rule: never present an app frame while the pen is active (Ratta overlay ink
     * lags for every frame it must mask). Chrome text changes wait for the gate to open.
     */
    private fun setPageIndicator(n: Int, total: Int) {
        val text = getString(R.string.page_indicator, n, total)
        whenPenIdle { binding.pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        binding.root.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** True while a stylus contact is on the glass (Ratta delivers stylus MotionEvents alongside
     *  the firmware ink). Not hover — contact. */
    private var stylusContactDown = false

    /** A stylus landed on the page with a panel open — close it at that contact's UP. */
    private var stylusDismissArmed = false

    /** EPD chrome-release: a finger landing on chrome must release the overlay so the tap's visual
     *  result shows. Done here because the buttons consume the touch. Palm-gated. Anything landing
     *  on the *page* while a tool panel is open dismisses the panel — the paper is the "anywhere
     *  else" of that panel (a tap on the panel itself is over chrome: the panel is a child of the
     *  top bar, so [overChrome] already covers it). A finger dismisses immediately. A stylus
     *  dismisses at its **pen-up**: the stroke is committed synchronously by then, and waiting for
     *  full pen-idle instead would hold the panel for as long as the pen *hovers* (`isPenActive`
     *  counts proximity + a 350 ms tail — an eye-check finding: the panel felt stuck). The close
     *  is posted so the engine's commit for this event runs first; this is the one deliberate
     *  frame-silence exception — a single chrome frame at a stroke boundary, once per dismissal. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::paper.isInitialized) {
            val tool = ev.getToolType(0)
            val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!stylus && !paper.isPenActive) {
                        if (overChrome(ev)) {
                            paper.releaseRender()
                        } else if (::toolbar.isInitialized && toolbar.panelOpen) {
                            paper.releaseRender()
                            toolbar.closePanels()
                        }
                    } else if (stylus) {
                        stylusContactDown = true
                        if (::toolbar.isInitialized && toolbar.panelOpen && !overChrome(ev)) {
                            stylusDismissArmed = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (stylus) {
                    stylusContactDown = false
                    if (stylusDismissArmed) {
                        stylusDismissArmed = false
                        binding.root.post {
                            // A new contact may have landed before the post ran — never repaint
                            // chrome under a live stroke; that contact's own UP re-arms nothing,
                            // so fall back to the idle gate.
                            if (::toolbar.isInitialized && toolbar.panelOpen) {
                                if (stylusContactDown) {
                                    whenPenIdle {
                                        if (toolbar.panelOpen) { paper.releaseRender(); toolbar.closePanels() }
                                    }
                                } else {
                                    paper.releaseRender()
                                    toolbar.closePanels()
                                }
                            }
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun overChrome(ev: MotionEvent): Boolean {
        val top = rectOf(binding.topBar)
        val bottom = rectOf(binding.bottomStrip)
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) || (bottom?.contains(x, y) == true)
    }

    private fun rectOf(v: View): Rect? {
        if (v.width == 0 || v.height == 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::paper.isInitialized) paper.resumeDrawing()
    }

    override fun onStop() {
        super.onStop()
        if (!opened || closing || !session.isOpen) return
        // Cheap durability point while backgrounded: cover + last-open page. Ink is already in rows.
        val p = paper; val s = session; val id = notebookId
        appScope.launch {
            try {
                if (!closing) CoverSnapshot.capture(p, id, repo)
                if (!closing) s.saveLastOpened()
            } catch (e: Exception) { Log.w(TAG, "onStop persist failed", e) }
        }
    }

    /**
     * Normal close: cover → last-open page → meta → drain writes + seal, on an application-scoped
     * NonCancellable coroutine (each step guarded), then finish. Idempotent.
     */
    private fun close() {
        if (closing) return
        closing = true
        BrowseState(this).lastOpenNotebookId = null
        if (!::session.isInitialized || !session.isOpen) { finish(); return }
        val p = paper; val s = session; val id = notebookId
        val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
        appScope.launch {
            withContext(NonCancellable) {
                if (opened) try { CoverSnapshot.capture(p, id, repo) } catch (e: Exception) { Log.w(TAG, "cover failed", e) }
                try { s.saveLastOpened() } catch (e: Exception) { Log.w(TAG, "saveLastOpened failed", e) }
                try { s.refreshMeta(versionCode) } catch (e: Exception) { Log.w(TAG, "refreshMeta failed", e) }
                try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) }
            }
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onDestroy() {
        if (IndexGuard.bounced(this)) { super.onDestroy(); return }
        if (::paper.isInitialized) paper.release()
        // A destroy that isn't a normal close (e.g. finish() out of failOpen) still seals.
        if (::session.isInitialized && session.isOpen && !closing) {
            closing = true
            val s = session
            appScope.launch { withContext(NonCancellable) { try { s.seal() } catch (e: Exception) { Log.w(TAG, "seal failed", e) } } }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NotebookActivity"
        const val EXTRA_NOTEBOOK_ID = "notebookId"
        const val EXTRA_NOTEBOOK_NAME = "notebookName"

        /** Outlives the Activity so a close in flight always completes its seal. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(context: Context, notebookId: String, notebookName: String): Intent =
            Intent(context, NotebookActivity::class.java)
                .putExtra(EXTRA_NOTEBOOK_ID, notebookId)
                .putExtra(EXTRA_NOTEBOOK_NAME, notebookName)
    }
}
