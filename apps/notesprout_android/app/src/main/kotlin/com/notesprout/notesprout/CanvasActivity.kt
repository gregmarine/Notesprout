package com.notesprout.notesprout

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.notesprout.notesprout.data.PageModel
import com.notesprout.notesprout.data.SoilDatabase
import com.notesprout.notesprout.data.StrokeModel
import com.notesprout.notesprout.data.StrokePoint
import com.notesprout.notesprout.undo.UndoAction
import com.notesprout.notesprout.undo.UndoStack
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class CanvasActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Canvas"
        private const val SOIL_FILE = "notebook.soil"
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        private const val ERASER_THRESHOLD_DP = 20f
        private const val SWIPE_MIN_DISTANCE_DP = 80f
        private const val SWIPE_MAX_DURATION_MS = 300L
    }

    private enum class ToolMode { PEN, ERASER }

    private lateinit var surfaceView: SurfaceView
    private lateinit var touchHelper: TouchHelper
    private var touchHelperInitialized = false
    private var surfaceReady = false

    private var folderPath: String = ""
    private var currentMode = ToolMode.PEN
    private var eraserThresholdPx = 0f
    private var swipeMinDistancePx = 0f

    @Volatile private var layerId: String? = null
    @Volatile private var db: SoilDatabase? = null

    private var committedBitmap: Bitmap? = null
    private var committedCanvas: Canvas? = null

    // In-memory stroke list — all mutations on the main thread
    private val strokes = mutableListOf<StrokeModel>()

    // Page state
    private val pages = mutableListOf<PageModel>()
    private var currentPageIndex = 0

    // Two-finger swipe detection
    private var trackingTwoFingerSwipe = false
    private var twoFingerSwipeStartX = 0f
    private var twoFingerSwipeStartTime = 0L

    private val strokePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val activePoints = mutableListOf<StrokePoint>()

    // Collects all strokes erased during a single eraser drag gesture
    private val pendingErasedStrokes = mutableListOf<StrokeModel>()

    private val undoStack = UndoStack()

    private lateinit var penBtn: ImageButton
    private lateinit var eraserBtn: ImageButton
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private lateinit var pageIndicator: TextView

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint) {
            activePoints.clear()
            activePoints.add(StrokePoint(
                x = p1.x.toDouble(), y = p1.y.toDouble(),
                pressure = p1.pressure.toDouble(), tilt = p1.tiltX.toDouble(),
                timestamp = System.currentTimeMillis()
            ))
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint) {}

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint) {
            activePoints.add(StrokePoint(
                x = p0.x.toDouble(), y = p0.y.toDouble(),
                pressure = p0.pressure.toDouble(), tilt = p0.tiltX.toDouble(),
                timestamp = System.currentTimeMillis()
            ))
        }

        override fun onRawDrawingTouchPointListReceived(p0: TouchPointList) {
            Log.i(TAG, "onRawDrawingTouchPointListReceived: ${p0.size()} points, active=${activePoints.size}")
            val pts = if (activePoints.size >= 2) {
                activePoints.toList()
            } else {
                (0 until p0.size()).map { i ->
                    val tp = p0.get(i)
                    StrokePoint(
                        x = tp.x.toDouble(), y = tp.y.toDouble(),
                        pressure = tp.pressure.toDouble(), tilt = tp.tiltX.toDouble(),
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
            activePoints.clear()
            if (pts.size < 2) return
            val lid = layerId ?: return

            val now = System.currentTimeMillis()
            val stroke = StrokeModel(
                id = UUID.randomUUID().toString(),
                parentId = lid,
                createdAt = now,
                updatedAt = now,
                points = pts,
                color = Color.BLACK,
                width = 3.0
            )

            runOnUiThread {
                strokes.add(stroke)
                drawStrokeToBitmap(stroke)
                undoStack.push(UndoAction.DrawStroke(stroke))
                updateUndoRedoButtons()
                blitBitmapToSurface()
                // Cycle raw drawing so the e-ink compositor refreshes the full screen
                // (including toolbar) — without this, the ONYX SDK's direct framebuffer
                // writes suppress the Android view hierarchy refresh entirely.
                resetRawDrawing()
            }

            Thread {
                try {
                    db?.addStroke(stroke)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save stroke", e)
                }
            }.start()
        }

        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint) {}
        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(p0: TouchPointList) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH) ?: run {
            Log.e(TAG, "No folder path in intent — finishing")
            finish()
            return
        }
        Log.i(TAG, "folderPath=$folderPath")

        val density = resources.displayMetrics.density
        eraserThresholdPx = ERASER_THRESHOLD_DP * density
        swipeMinDistancePx = SWIPE_MIN_DISTANCE_DP * density

        supportActionBar?.hide()

        val root = FrameLayout(this)

        surfaceView = SurfaceView(this).also { sv ->
            sv.setZOrderOnTop(false)
            sv.setZOrderMediaOverlay(false)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val toolbar = buildToolbar(density)
        val toolbarMargin = (16 * density).toInt()
        val toolbarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(toolbarMargin, toolbarMargin, 0, 0)
        }
        root.addView(toolbar, toolbarParams)

        pageIndicator = buildPageIndicator(density)
        val indicatorMargin = (16 * density).toInt()
        val indicatorParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, indicatorMargin, indicatorMargin)
        }
        root.addView(pageIndicator, indicatorParams)

        setContentView(root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tbParams = toolbar.layoutParams as FrameLayout.LayoutParams
            tbParams.setMargins(bars.left + toolbarMargin, bars.top + toolbarMargin, 0, 0)
            toolbar.layoutParams = tbParams
            val indParams = pageIndicator.layoutParams as FrameLayout.LayoutParams
            indParams.setMargins(0, 0, bars.right + indicatorMargin, bars.bottom + indicatorMargin)
            pageIndicator.layoutParams = indParams
            insets
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                surfaceReady = true
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "surfaceChanged: ${width}x${height}")
                if (width == 0 || height == 0) return

                if (!touchHelperInitialized) {
                    touchHelperInitialized = true
                    initBitmap(width, height)
                    loadStrokesFromDb()
                    initTouchHelper(width, height)
                } else if (::touchHelper.isInitialized) {
                    updateLimitRect()
                    if (currentMode == ToolMode.PEN) {
                        EpdController.enterScribbleMode(surfaceView)
                        touchHelper.setRawDrawingEnabled(false)
                        touchHelper.setRawDrawingEnabled(true)
                        touchHelper.setRawInputReaderEnable(true)
                    }
                    blitBitmapToSurface()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
                surfaceReady = false
                if (::touchHelper.isInitialized) {
                    touchHelper.setRawInputReaderEnable(false)
                    touchHelper.setRawDrawingEnabled(false)
                    EpdController.leaveScribbleMode(surfaceView)
                }
            }
        })

        setToolMode(ToolMode.PEN)
    }

    private fun buildToolbar(density: Float): LinearLayout {
        val btnSize = (40 * density).toInt()
        val btnPadding = (8 * density).toInt()
        val cornerRadius = 12 * density

        val toolbarBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadius)
            setColor(Color.WHITE)
        }

        fun makeBtn(iconRes: Int, desc: String): ImageButton =
            ImageButton(this).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
                setColorFilter(Color.BLACK)
            }

        undoBtn = makeBtn(R.drawable.ic_undo, "Undo")
        redoBtn = makeBtn(R.drawable.ic_redo, "Redo")
        penBtn = makeBtn(R.drawable.ic_pen, "Pen")
        eraserBtn = makeBtn(R.drawable.ic_eraser, "Eraser")
        val addPageBtn = makeBtn(R.drawable.ic_add_page, "Add Page")
        val deletePageBtn = makeBtn(R.drawable.ic_delete_page, "Delete Page")
        val closeBtn = makeBtn(android.R.drawable.ic_menu_close_clear_cancel, "Close")

        undoBtn.setOnClickListener { performUndo() }
        redoBtn.setOnClickListener { performRedo() }
        penBtn.setOnClickListener { setToolMode(ToolMode.PEN) }
        eraserBtn.setOnClickListener { setToolMode(ToolMode.ERASER) }
        addPageBtn.setOnClickListener { addPageAction() }
        deletePageBtn.setOnClickListener { deletePageAction() }
        closeBtn.setOnClickListener { finish() }

        // Initially dim undo/redo since stack is empty
        undoBtn.alpha = 0.4f
        redoBtn.alpha = 0.4f

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = toolbarBg
            elevation = 8 * density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
            setPadding(
                (4 * density).toInt(), (4 * density).toInt(),
                (4 * density).toInt(), (4 * density).toInt()
            )
            addView(undoBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(redoBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(penBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(eraserBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(addPageBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(deletePageBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(closeBtn, LinearLayout.LayoutParams(btnSize, btnSize))
        }
    }

    private fun updateUndoRedoButtons() {
        undoBtn.alpha = if (undoStack.canUndo()) 1.0f else 0.4f
        redoBtn.alpha = if (undoStack.canRedo()) 1.0f else 0.4f
    }

    private fun buildPageIndicator(density: Float): TextView {
        return TextView(this).apply {
            textSize = 12f
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(8 * density)
                setColor(Color.WHITE)
            }
            elevation = 8 * density
            setPadding(
                (8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt()
            )
            text = "Page 1 / 1"
        }
    }

    private fun updatePageIndicator() {
        pageIndicator.text = "Page ${currentPageIndex + 1} / ${pages.size}"
    }

    private fun makeActiveBg(): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(8 * density)
            setColor(0xFFD0E8FF.toInt())
        }
    }

    private fun setToolMode(mode: ToolMode) {
        currentMode = mode

        val density = resources.displayMetrics.density
        penBtn.background = if (mode == ToolMode.PEN) makeActiveBg() else null
        penBtn.setPadding(
            (8 * density).toInt(), (8 * density).toInt(),
            (8 * density).toInt(), (8 * density).toInt()
        )
        eraserBtn.background = if (mode == ToolMode.ERASER) makeActiveBg() else null
        eraserBtn.setPadding(
            (8 * density).toInt(), (8 * density).toInt(),
            (8 * density).toInt(), (8 * density).toInt()
        )

        if (mode == ToolMode.PEN) {
            pendingErasedStrokes.clear()
        }

        if (::touchHelper.isInitialized) {
            when (mode) {
                ToolMode.PEN -> {
                    activePoints.clear()
                    EpdController.enterScribbleMode(surfaceView)
                    touchHelper.setRawDrawingEnabled(true)
                    touchHelper.setRawInputReaderEnable(true)
                }
                ToolMode.ERASER -> {
                    activePoints.clear()
                    touchHelper.setRawDrawingEnabled(false)
                    EpdController.leaveScribbleMode(surfaceView)
                }
            }
        }
    }

    private fun handleEraserTouch(ev: MotionEvent) {
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val sx = ev.x.toDouble()
                val sy = ev.y.toDouble()
                val threshold = eraserThresholdPx.toDouble()
                val toDelete = mutableListOf<StrokeModel>()

                for (stroke in strokes) {
                    for (pt in stroke.points) {
                        val dx = pt.x - sx
                        val dy = pt.y - sy
                        if (sqrt(dx * dx + dy * dy) < threshold) {
                            toDelete.add(stroke)
                            break
                        }
                    }
                }

                if (toDelete.isNotEmpty()) {
                    strokes.removeAll(toDelete.toSet())
                    pendingErasedStrokes.addAll(toDelete)
                    val snapshot = toDelete.toList()
                    val dbRef = db
                    Thread {
                        snapshot.forEach { stroke ->
                            try {
                                dbRef?.deleteStroke(stroke.id)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to delete stroke ${stroke.id}", e)
                            }
                        }
                    }.start()
                    rebuildBitmap()
                    blitBitmapToSurface()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pendingErasedStrokes.isNotEmpty()) {
                    undoStack.push(UndoAction.EraseStrokes(pendingErasedStrokes.toList()))
                    pendingErasedStrokes.clear()
                    updateUndoRedoButtons()
                }
            }
        }
    }

    // Returns true if a two-finger swipe was detected and consumed.
    private fun detectTwoFingerSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    twoFingerSwipeStartX = (ev.getX(0) + ev.getX(1)) / 2f
                    twoFingerSwipeStartTime = System.currentTimeMillis()
                    trackingTwoFingerSwipe = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount == 2 && trackingTwoFingerSwipe) {
                    val elapsed = System.currentTimeMillis() - twoFingerSwipeStartTime
                    if (elapsed <= SWIPE_MAX_DURATION_MS) {
                        val liftIdx = ev.actionIndex
                        val remainIdx = if (liftIdx == 0) 1 else 0
                        val endAvgX = (ev.getX(liftIdx) + ev.getX(remainIdx)) / 2f
                        val dx = endAvgX - twoFingerSwipeStartX
                        if (abs(dx) >= swipeMinDistancePx) {
                            trackingTwoFingerSwipe = false
                            activePoints.clear()
                            if (dx > 0) navigateToPreviousPage() else navigateToNextPage()
                            return true
                        }
                    }
                    trackingTwoFingerSwipe = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trackingTwoFingerSwipe = false
            }
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (detectTwoFingerSwipe(ev)) return true
        if (::touchHelper.isInitialized && currentMode == ToolMode.PEN) {
            touchHelper.onTouchEvent(ev)
        } else if (currentMode == ToolMode.ERASER) {
            handleEraserTouch(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun getUsableLimitRect(): Rect {
        val frame = Rect()
        surfaceView.getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        surfaceView.getLocationOnScreen(loc)
        val left = maxOf(0, frame.left - loc[0])
        val top = maxOf(0, frame.top - loc[1])
        val right = frame.right - loc[0]
        val bottom = frame.bottom - loc[1]
        Log.i(TAG, "usableLimitRect: [$left,$top,$right,$bottom]")
        return Rect(left, top, right, bottom)
    }

    private fun updateLimitRect() {
        if (::touchHelper.isInitialized) {
            touchHelper.setLimitRect(listOf(getUsableLimitRect()), emptyList())
        }
    }

    private fun initBitmap(width: Int, height: Int) {
        committedBitmap?.recycle()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        committedBitmap = bmp
        committedCanvas = c
    }

    private fun loadStrokesFromDb() {
        Thread {
            val soilPath = "$folderPath/$SOIL_FILE"
            Log.i(TAG, "loadStrokesFromDb: $soilPath")
            val database = SoilDatabase(soilPath)
            try {
                database.open()
                val allPages = database.getAllPages()
                if (allPages.isEmpty()) {
                    Log.w(TAG, "No pages found in $soilPath")
                    database.close()
                    return@Thread
                }
                val firstPage = allPages[0]
                val layer = database.getFirstLayer(firstPage.id) ?: run {
                    Log.w(TAG, "No layer found for pageId=${firstPage.id}")
                    database.close()
                    return@Thread
                }
                layerId = layer.id
                val loaded = database.getStrokes(layer.id)
                db = database
                Log.i(TAG, "Loaded ${loaded.size} strokes for page 1, layerId=${layer.id}")
                runOnUiThread {
                    pages.clear()
                    pages.addAll(allPages)
                    currentPageIndex = 0
                    strokes.addAll(loaded)
                    loaded.forEach { drawStrokeToBitmap(it) }
                    blitBitmapToSurface()
                    updatePageIndicator()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadStrokesFromDb failed", e)
                database.close()
            }
        }.start()
    }

    private fun loadPage(page: PageModel) {
        val dbRef = db ?: return
        Thread {
            val layer = dbRef.getFirstLayer(page.id) ?: run {
                Log.w(TAG, "No layer found for pageId=${page.id}")
                return@Thread
            }
            val loaded = dbRef.getStrokes(layer.id)
            runOnUiThread {
                layerId = layer.id
                strokes.clear()
                strokes.addAll(loaded)
                rebuildBitmap()
                blitBitmapToSurface()
                updatePageIndicator()
                if (::touchHelper.isInitialized && currentMode == ToolMode.PEN) {
                    touchHelper.setRawDrawingEnabled(false)
                    touchHelper.setRawDrawingEnabled(true)
                    touchHelper.setRawInputReaderEnable(true)
                }
            }
        }.start()
    }

    private fun navigateToNextPage() {
        if (currentPageIndex >= pages.size - 1) return
        currentPageIndex++
        loadPage(pages[currentPageIndex])
    }

    private fun navigateToPreviousPage() {
        if (currentPageIndex <= 0) return
        currentPageIndex--
        loadPage(pages[currentPageIndex])
    }

    private fun addPageAction() {
        val dbRef = db ?: return
        val meta = dbRef.getNotebookMeta() ?: return
        Thread {
            try {
                val newPage = dbRef.addPage(meta.pageWidth, meta.pageHeight)
                val newLayer = dbRef.getFirstLayer(newPage.id)
                val allPages = dbRef.getAllPages()
                runOnUiThread {
                    pages.clear()
                    pages.addAll(allPages)
                    currentPageIndex = pages.indexOfFirst { it.id == newPage.id }.coerceAtLeast(0)
                    loadPage(pages[currentPageIndex])
                    if (newLayer != null) {
                        undoStack.push(UndoAction.AddPage(newPage, newLayer))
                        updateUndoRedoButtons()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "addPageAction failed", e)
            }
        }.start()
    }

    private fun deletePageAction() {
        if (pages.size <= 1) {
            Toast.makeText(this, "Cannot delete the only page", Toast.LENGTH_SHORT).show()
            return
        }
        val page = pages[currentPageIndex]
        val pageNum = currentPageIndex + 1
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete Page")
            .setMessage("Are you sure you want to delete page $pageNum?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                performDeletePage(page)
            }
            .create()
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(Color.RED)
    }

    private fun performDeletePage(page: PageModel) {
        val dbRef = db ?: return
        val targetIndex = if (currentPageIndex > 0) currentPageIndex - 1 else 1
        Thread {
            try {
                val layer = dbRef.getFirstLayer(page.id)
                val strokesAlive = if (layer != null) dbRef.getNonDeletedStrokesForLayer(layer.id) else emptyList()
                dbRef.softDeleteLayersForPage(page.id)
                dbRef.deletePage(page.id)
                val allPages = dbRef.getAllPages()
                runOnUiThread {
                    if (layer != null) {
                        undoStack.push(UndoAction.DeletePage(page, layer, strokesAlive))
                        updateUndoRedoButtons()
                    }
                    pages.clear()
                    pages.addAll(allPages)
                    currentPageIndex = targetIndex.coerceIn(0, pages.size - 1)
                    loadPage(pages[currentPageIndex])
                }
            } catch (e: Exception) {
                Log.e(TAG, "performDeletePage failed", e)
            }
        }.start()
    }

    // Clears the ONYX SDK stroke overlay so the committed bitmap becomes the ground truth.
    // Must be called on the main thread after rebuildBitmap()/blitBitmapToSurface() whenever
    // strokes are added or removed outside of a normal page load (i.e. undo/redo).
    private fun resetRawDrawing() {
        if (::touchHelper.isInitialized && currentMode == ToolMode.PEN) {
            touchHelper.setRawDrawingEnabled(false)
            touchHelper.setRawDrawingEnabled(true)
            touchHelper.setRawInputReaderEnable(true)
        }
    }

    private fun performUndo() {
        val action = undoStack.undo() ?: return
        updateUndoRedoButtons()
        val dbRef = db ?: return

        when (action) {
            is UndoAction.DrawStroke -> {
                val stroke = action.stroke
                Thread {
                    try { dbRef.deleteStroke(stroke.id) } catch (e: Exception) {
                        Log.e(TAG, "Undo DrawStroke failed", e)
                    }
                }.start()
                strokes.remove(stroke)
                rebuildBitmap()
                blitBitmapToSurface()
                resetRawDrawing()
            }

            is UndoAction.EraseStrokes -> {
                val toRestore = action.strokes
                Thread {
                    toRestore.forEach { stroke ->
                        try { dbRef.restoreStroke(stroke.id) } catch (e: Exception) {
                            Log.e(TAG, "Undo EraseStrokes failed for ${stroke.id}", e)
                        }
                    }
                }.start()
                val currentLayerId = layerId
                val onCurrentPage = toRestore.filter { it.parentId == currentLayerId }
                strokes.addAll(onCurrentPage)
                rebuildBitmap()
                blitBitmapToSurface()
                resetRawDrawing()
            }

            is UndoAction.AddPage -> {
                val page = action.page
                val wasOnThisPage = pages.getOrNull(currentPageIndex)?.id == page.id
                val fallbackIndex = if (wasOnThisPage) (currentPageIndex - 1).coerceAtLeast(0) else currentPageIndex
                Thread {
                    try {
                        dbRef.softDeleteLayersForPage(page.id)
                        dbRef.deletePage(page.id)
                        val allPages = dbRef.getAllPages()
                        runOnUiThread {
                            pages.clear()
                            pages.addAll(allPages)
                            currentPageIndex = fallbackIndex.coerceIn(0, pages.size - 1)
                            loadPage(pages[currentPageIndex])
                            updatePageIndicator()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Undo AddPage failed", e)
                    }
                }.start()
            }

            is UndoAction.DeletePage -> {
                val page = action.page
                val layer = action.layer
                val strokesAlive = action.strokesAliveAtDeletion
                Thread {
                    try {
                        dbRef.restorePage(page.id)
                        dbRef.restoreLayer(layer.id)
                        strokesAlive.forEach { stroke ->
                            try { dbRef.restoreStroke(stroke.id) } catch (e: Exception) {
                                Log.e(TAG, "Undo DeletePage restoreStroke failed for ${stroke.id}", e)
                            }
                        }
                        val allPages = dbRef.getAllPages()
                        runOnUiThread {
                            pages.clear()
                            pages.addAll(allPages)
                            currentPageIndex = pages.indexOfFirst { it.id == page.id }.coerceAtLeast(0)
                            loadPage(pages[currentPageIndex])
                            updatePageIndicator()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Undo DeletePage failed", e)
                    }
                }.start()
            }
        }
    }

    private fun performRedo() {
        val action = undoStack.redo() ?: return
        updateUndoRedoButtons()
        val dbRef = db ?: return

        when (action) {
            is UndoAction.DrawStroke -> {
                val stroke = action.stroke
                Thread {
                    try { dbRef.restoreStroke(stroke.id) } catch (e: Exception) {
                        Log.e(TAG, "Redo DrawStroke failed", e)
                    }
                }.start()
                val currentLayerId = layerId
                if (stroke.parentId == currentLayerId) {
                    strokes.add(stroke)
                }
                rebuildBitmap()
                blitBitmapToSurface()
                resetRawDrawing()
            }

            is UndoAction.EraseStrokes -> {
                val toErase = action.strokes
                Thread {
                    toErase.forEach { stroke ->
                        try { dbRef.deleteStroke(stroke.id) } catch (e: Exception) {
                            Log.e(TAG, "Redo EraseStrokes failed for ${stroke.id}", e)
                        }
                    }
                }.start()
                strokes.removeAll(toErase.toSet())
                rebuildBitmap()
                blitBitmapToSurface()
                resetRawDrawing()
            }

            is UndoAction.AddPage -> {
                val page = action.page
                val layer = action.layer
                Thread {
                    try {
                        dbRef.restorePage(page.id)
                        dbRef.restoreLayer(layer.id)
                        val allPages = dbRef.getAllPages()
                        runOnUiThread {
                            pages.clear()
                            pages.addAll(allPages)
                            currentPageIndex = pages.indexOfFirst { it.id == page.id }.coerceAtLeast(0)
                            loadPage(pages[currentPageIndex])
                            updatePageIndicator()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Redo AddPage failed", e)
                    }
                }.start()
            }

            is UndoAction.DeletePage -> {
                val page = action.page
                val layer = action.layer
                val strokesAlive = action.strokesAliveAtDeletion
                val wasOnThisPage = pages.getOrNull(currentPageIndex)?.id == page.id
                val fallbackIndex = if (wasOnThisPage) (currentPageIndex - 1).coerceAtLeast(0) else currentPageIndex
                Thread {
                    try {
                        strokesAlive.forEach { stroke ->
                            try { dbRef.deleteStroke(stroke.id) } catch (e: Exception) {
                                Log.e(TAG, "Redo DeletePage deleteStroke failed for ${stroke.id}", e)
                            }
                        }
                        dbRef.deleteLayer(layer.id)
                        dbRef.deletePage(page.id)
                        val allPages = dbRef.getAllPages()
                        runOnUiThread {
                            pages.clear()
                            pages.addAll(allPages)
                            currentPageIndex = fallbackIndex.coerceIn(0, pages.size - 1)
                            loadPage(pages[currentPageIndex])
                            updatePageIndicator()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Redo DeletePage failed", e)
                    }
                }.start()
            }
        }
    }

    private fun drawStrokeToBitmap(stroke: StrokeModel) {
        val canvas = committedCanvas ?: return
        if (stroke.points.size < 2) return
        val path = Path().apply {
            moveTo(stroke.points[0].x.toFloat(), stroke.points[0].y.toFloat())
            for (i in 1 until stroke.points.size) {
                lineTo(stroke.points[i].x.toFloat(), stroke.points[i].y.toFloat())
            }
        }
        canvas.drawPath(path, strokePaint)
    }

    private fun rebuildBitmap() {
        val canvas = committedCanvas ?: return
        canvas.drawColor(Color.WHITE)
        strokes.forEach { drawStrokeToBitmap(it) }
    }

    private fun blitBitmapToSurface() {
        val bmp = committedBitmap ?: return
        if (!surfaceReady) return
        val canvas = surfaceView.holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            surfaceView.holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun initTouchHelper(width: Int, height: Int) {
        Log.i(TAG, "initTouchHelper: surface ${width}x${height}")
        val limitRect = getUsableLimitRect()
        touchHelper = TouchHelper.create(surfaceView, TouchHelper.FEATURE_SF_TOUCH_RENDER, callback)
            .setStrokeWidth(3.0f)
            .setStrokeColor(Color.BLACK)
            .setLimitRect(listOf(limitRect), emptyList())
            .openRawDrawing()

        Log.i(TAG, "openRawDrawing done — isRawDrawingCreated=${touchHelper.isRawDrawingCreated()}")
        EpdController.enterScribbleMode(surfaceView)
        touchHelper.setRawDrawingEnabled(true)
        Log.i(TAG, "setRawDrawingEnabled(true) — inputEnabled=${touchHelper.isRawDrawingInputEnabled()} renderEnabled=${touchHelper.isRawDrawingRenderEnabled()}")
        touchHelper.setRawInputReaderEnable(true)
        Log.i(TAG, "TouchHelper init complete")
    }

    override fun onResume() {
        super.onResume()
        if (::touchHelper.isInitialized && surfaceReady && currentMode == ToolMode.PEN) {
            Log.i(TAG, "onResume — re-entering scribble mode")
            EpdController.enterScribbleMode(surfaceView)
            touchHelper.setRawDrawingEnabled(true)
            touchHelper.setRawInputReaderEnable(true)
            blitBitmapToSurface()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::touchHelper.isInitialized) {
            Log.i(TAG, "onPause — leaving scribble mode")
            touchHelper.setRawInputReaderEnable(false)
            touchHelper.setRawDrawingEnabled(false)
            EpdController.leaveScribbleMode(surfaceView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        undoStack.clear()
        if (::touchHelper.isInitialized) touchHelper.closeRawDrawing()
        db?.close()
        committedBitmap?.recycle()
    }
}
