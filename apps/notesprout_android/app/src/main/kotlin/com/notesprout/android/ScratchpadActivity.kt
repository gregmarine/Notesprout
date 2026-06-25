package com.notesprout.android

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.isBooxDevice
import com.notesprout.android.data.PageData
import com.notesprout.android.data.ScratchpadRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.ScratchpadEntity
import com.notesprout.android.databinding.ActivityScratchpadBinding
import com.notesprout.android.notebook.ActiveTool
import com.notesprout.android.notebook.GenericNotebookView
import com.notesprout.android.notebook.NotebookView
import com.notesprout.android.notebook.OnyxNotebookView
import com.notesprout.android.notebook.ScratchpadPreferences
import com.notesprout.android.notebook.ToolPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScratchpadActivity : AppCompatActivity() {

    companion object {
        /** Nullable — set when launched from a notebook; null when launched from MainActivity. */
        const val EXTRA_FROM_NOTEBOOK_ID        = "from_notebook_id"
        const val EXTRA_FROM_NOTEBOOK_NAME      = "from_notebook_name"
        const val EXTRA_FROM_NOTEBOOK_ENCRYPTED = "from_notebook_encrypted"

        private const val TAG = "Notesprout"
    }

    private lateinit var binding: ActivityScratchpadBinding
    private lateinit var drawingView: NotebookView
    private lateinit var repository: ScratchpadRepository

    private var pages: List<ScratchpadEntity> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentPageId: String = ""
    private var currentLayerId: String = ""
    private val persistedStrokeIds = mutableSetOf<String>()
    private var isEraserActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScratchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ScratchpadRepository(NotesproutIndex.db(), NotesproutIndex.scratchpadDao())

        val fromNotebookId = intent.getStringExtra(EXTRA_FROM_NOTEBOOK_ID)
        binding.btnSendToNotebook.visibility =
            if (fromNotebookId != null) View.VISIBLE else View.GONE

        // On large screens constrain to 75% × 75%, centered.
        if (resources.getBoolean(R.bool.is_large_screen)) {
            val dm = resources.displayMetrics
            val lp = binding.scratchpadWindow.layoutParams as FrameLayout.LayoutParams
            lp.width   = (dm.widthPixels  * 0.75f).toInt()
            lp.height  = (dm.heightPixels * 0.75f).toInt()
            lp.gravity = Gravity.CENTER
            binding.scratchpadWindow.layoutParams = lp
        }

        // Tapping outside the bordered window dismisses; inside consumes the event.
        binding.root.setOnClickListener { finish() }
        binding.scratchpadWindow.setOnClickListener { }

        // ── Drawing view ──────────────────────────────────────────────────────
        drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        wireDrawingCallbacks()
        wireToolButtons()

        // Restore last-used tool state.
        when (ToolPreferencesManager.load(this)) {
            ActiveTool.ERASER -> {
                isEraserActive = true
                drawingView.setEraserMode(true)
                binding.btnScratchPen.isSelected    = false
                binding.btnScratchEraser.isSelected = true
            }
            else -> {
                binding.btnScratchPen.isSelected    = true
                binding.btnScratchEraser.isSelected = false
            }
        }

        // S4–S7: wired in later sessions.
        binding.btnScratchLasso.setOnClickListener { }
        binding.btnScratchAddPage.setOnClickListener { }
        binding.btnScratchDeletePage.setOnClickListener { }
        binding.btnSendToNotebook.setOnClickListener { }
        binding.btnScratchpadPrev.setOnClickListener { }
        binding.btnScratchpadNext.setOnClickListener { }

        // Bootstrap and load on first open.
        lifecycleScope.launch {
            repository.ensureBootstrap()
            pages = repository.getPages()
            val savedIndex = ScratchpadPreferences.loadCurrentPageIndex(this@ScratchpadActivity)
            currentPageIndex = savedIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

            val page = pages.getOrNull(currentPageIndex) ?: return@launch
            currentPageId = page.id
            currentLayerId = repository.getLayerForPage(currentPageId)?.id ?: ""
            updatePageIndicator()

            if (binding.drawingContainer.width > 0) {
                loadCurrentPage()
            } else {
                binding.drawingContainer.doOnLayout { lifecycleScope.launch { loadCurrentPage() } }
            }
        }
    }

    // ── Drawing callbacks ─────────────────────────────────────────────────────

    private fun wireDrawingCallbacks() {
        drawingView.onStrokeErased = { strokeId ->
            persistedStrokeIds.remove(strokeId)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    NotesproutIndex.scratchpadDao().softDelete(strokeId, System.currentTimeMillis())
                }
            }
        }

        drawingView.onHeadingErased = { heading ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(heading.id, deletedAt)
                withContext(Dispatchers.Main) { rebuildBitmapAfterErase() }
            }
        }

        drawingView.onTextErased = { textObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(textObject.id, deletedAt)
                withContext(Dispatchers.Main) { rebuildBitmapAfterErase() }
            }
        }

        drawingView.onLineErased = { lineObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(lineObject.id, deletedAt)
                withContext(Dispatchers.Main) { rebuildBitmapAfterErase() }
            }
        }

        drawingView.onLinkErased = { linkObject ->
            val deletedAt = System.currentTimeMillis()
            lifecycleScope.launch(Dispatchers.IO) {
                NotesproutIndex.scratchpadDao().softDelete(linkObject.id, deletedAt)
                withContext(Dispatchers.Main) { rebuildBitmapAfterErase() }
            }
        }

        drawingView.onPenLifted = {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { saveStrokes() }
            }
        }

        drawingView.onSnapshotReady = { snapshot ->
            val pageId = currentPageId
            if (pageId.isNotEmpty()) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { persistSnapshot(pageId, snapshot) }
                }
            }
        }
    }

    // ── Tool buttons ──────────────────────────────────────────────────────────

    private fun wireToolButtons() {
        binding.btnScratchPen.setOnClickListener {
            isEraserActive = false
            drawingView.setEraserMode(false)
            binding.btnScratchPen.isSelected    = true
            binding.btnScratchEraser.isSelected = false
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, ActiveTool.PEN)
        }

        binding.btnScratchEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            drawingView.setEraserMode(isEraserActive)
            binding.btnScratchEraser.isSelected = isEraserActive
            binding.btnScratchPen.isSelected    = !isEraserActive
            drawingView.releaseRender()
            ToolPreferencesManager.save(this, if (isEraserActive) ActiveTool.ERASER else ActiveTool.PEN)
        }
    }

    // ── Page load ─────────────────────────────────────────────────────────────

    private suspend fun loadCurrentPage() {
        val pageId = currentPageId.takeIf { it.isNotEmpty() } ?: return
        val density = resources.displayMetrics.density

        // Read container dimensions on the main thread before dispatching IO.
        val containerW = binding.drawingContainer.width.toFloat()
        val containerH = binding.drawingContainer.height.toFloat()

        val content = withContext(Dispatchers.IO) {
            // Record real page size on first layout if still at 0×0.
            if (containerW > 0 && containerH > 0) {
                val row = NotesproutIndex.scratchpadDao().getObjectById(pageId)
                val pd  = row?.let { PageData.fromJson(it.data) }
                if (pd != null && (pd.width == 0f || pd.height == 0f)) {
                    repository.setPageSize(pageId, containerW, containerH)
                }
            }
            repository.loadPage(pageId, density)
        }

        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(
                content.strokes,
                null, // scratch pad has no template
                content.headings,
                content.textObjects,
                content.lineObjects,
                content.links,
            )
        }

        // Apply on main thread.
        drawingView.loadHeadings(content.headings)
        drawingView.loadTextObjects(content.textObjects)
        drawingView.loadLineObjects(content.lineObjects)
        drawingView.loadLinks(content.links)
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(content.strokes, bitmap, null)
        } else {
            drawingView.setTemplate(null)
            drawingView.loadStrokes(content.strokes)
        }

        persistedStrokeIds.clear()
        persistedStrokeIds.addAll(content.strokes.map { it.id })
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    // Must be called on Dispatchers.IO.
    private suspend fun saveStrokes() {
        val layerId = currentLayerId.takeIf { it.isNotEmpty() } ?: return
        val currentStrokes = drawingView.getStrokes()
        val alreadyPersisted = persistedStrokeIds.toHashSet()
        val newStrokes = currentStrokes.filter { it.id !in alreadyPersisted }
        if (newStrokes.isEmpty()) return
        repository.saveStrokes(layerId, newStrokes)
        persistedStrokeIds.addAll(newStrokes.map { it.id })
    }

    private suspend fun persistSnapshot(pageId: String, snapshot: String) {
        val dao = NotesproutIndex.scratchpadDao()
        val row = dao.getObjectById(pageId) ?: return
        val updated = PageData.fromJson(row.data).copy(snapshot = snapshot).toJson()
        dao.updateData(pageId, updated, System.currentTimeMillis())
    }

    // Must be called on the main thread; rebuilds bitmap after an eraser-tool deletion.
    private suspend fun rebuildBitmapAfterErase() {
        val strokes = drawingView.getStrokes()
        val bitmap = withContext(Dispatchers.IO) {
            drawingView.buildRenderBitmap(strokes, null, drawingView.getHeadings())
        }
        if (bitmap != null) {
            drawingView.loadStrokesWithBitmap(strokes, bitmap, null)
        } else {
            drawingView.loadStrokes(strokes)
        }
    }

    // ── Chrome helpers ────────────────────────────────────────────────────────

    private fun updatePageIndicator() {
        binding.tvScratchpadPageIndicator.text = "${currentPageIndex + 1} / ${pages.size}"
    }

    // ── Touch dispatch ────────────────────────────────────────────────────────

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Release the EPD writing overlay when the user touches the chrome bar or
        // the toolbar so button state changes are visible immediately on e-ink.
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
            if (isTouchInView(event, binding.chromeBar) ||
                isTouchInView(event, binding.scratchpadToolbar)) {
                drawingView.releaseRender()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchInView(event: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX; val y = event.rawY
        return x >= loc[0] && x < loc[0] + view.width && y >= loc[1] && y < loc[1] + view.height
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        drawingView.enableDrawing()
    }

    override fun onPause() {
        super.onPause()
        ScratchpadPreferences.saveCurrentPageIndex(this, currentPageIndex)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveStrokes() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
    }
}
