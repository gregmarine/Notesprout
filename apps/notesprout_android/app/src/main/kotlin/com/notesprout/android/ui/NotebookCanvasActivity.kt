package com.notesprout.android.ui

import android.graphics.Rect
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.NoteSproutApp
import com.notesprout.android.R
import com.notesprout.android.canvas.CanvasRenderer
import com.notesprout.android.canvas.DeviceDetector
import com.notesprout.android.canvas.DeviceType
import com.notesprout.android.canvas.DrawingEngine
import com.notesprout.android.canvas.GenericDrawingEngine
import com.notesprout.android.canvas.OnyxDrawingEngine
import com.notesprout.android.canvas.StrokeCallback
import com.notesprout.android.canvas.StrokePoint
import com.notesprout.android.canvas.ToolType
import com.notesprout.android.data.BaseObject
import com.notesprout.android.plugins.structural.NotebookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class NotebookCanvasActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        const val EXTRA_SOIL_PATH = "soil_path"
        private const val GEL_PEN_PLUGIN = "com.notesprout.tools.gel_pen"
        private const val ERASER_PLUGIN = "com.notesprout.tools.eraser"
        private val GEL_PEN_STYLE_JSON = """{"color":"#000000","baseWidth":3,"maxWidth":6}"""
    }

    private lateinit var notebookManager: NotebookManager
    private lateinit var pageIndicator: TextView
    private lateinit var prevPage: TextView
    private lateinit var nextPage: TextView
    private lateinit var canvasRenderer: CanvasRenderer
    private lateinit var drawingEngine: DrawingEngine

    private var pages: List<BaseObject> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentLayerId: String = ""
    private var activeToolPluginId: String = GEL_PEN_PLUGIN
    private var currentSaveJob: Job? = null

    // Tracks the last stylus position for incremental live-ink segments
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notebook_canvas)

        val app = application as NoteSproutApp
        notebookManager = NotebookManager(app.databaseManager, app.pluginEngine)

        pageIndicator = findViewById(R.id.pageIndicator)
        prevPage = findViewById(R.id.prevPage)
        nextPage = findViewById(R.id.nextPage)

        setupCanvas(app)
        setupToolbar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                drawingEngine.detach()
                runBlocking { currentSaveJob?.join() }
                app.pluginEngine.setCanvasDelegate(null)
                app.pluginEngine.setDatabase(null)
                app.databaseManager.closeCurrentDatabase()
                finish()
            }
        })

        findViewById<TextView>(R.id.backButton).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        prevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                currentPageIndex--
                updatePageIndicator()
                loadCurrentPageLayer(app)
            }
        }

        nextPage.setOnClickListener {
            if (currentPageIndex < pages.size - 1) {
                currentPageIndex++
                updatePageIndicator()
                loadCurrentPageLayer(app)
            }
        }

        val soilPath = intent.getStringExtra(EXTRA_SOIL_PATH) ?: ""
        if (soilPath.isNotEmpty()) {
            loadPages(soilPath, app)
        }
    }

    override fun onResume() {
        super.onResume()
        drawingEngine.onResume()
    }

    override fun onPause() {
        super.onPause()
        drawingEngine.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ImmersiveMode.enter(window)
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingEngine.detach()
        val app = application as NoteSproutApp
        app.pluginEngine.setCanvasDelegate(null)
        app.pluginEngine.setDatabase(null)
    }

    private fun setupCanvas(app: NoteSproutApp) {
        canvasRenderer = CanvasRenderer(this)
        val container = findViewById<FrameLayout>(R.id.canvasContainer)
        container.addView(canvasRenderer, 0)

        val deviceType = DeviceDetector.detect(this)
        drawingEngine = if (deviceType == DeviceType.BOOX) OnyxDrawingEngine() else GenericDrawingEngine()

        drawingEngine.attach(canvasRenderer, object : StrokeCallback {
            override fun onStrokeBegin(x: Float, y: Float, pressure: Float) {
                lastX = x
                lastY = y
                lastPressure = pressure
            }

            override fun onStrokeMove(x: Float, y: Float, pressure: Float) {
                if (activeToolPluginId == GEL_PEN_PLUGIN) {
                    val seg = buildSegmentJson(lastX, lastY, x, y, pressure)
                    canvasRenderer.drawStroke(seg, GEL_PEN_STYLE_JSON)
                    canvasRenderer.refresh()
                }
                lastX = x
                lastY = y
                lastPressure = pressure
            }

            override fun onStrokeEnd(points: List<StrokePoint>) {
                val pluginId = activeToolPluginId
                currentSaveJob = lifecycleScope.launch(Dispatchers.IO) {
                    val pointsJson = serializePoints(points)
                    app.pluginEngine.callFunction(pluginId, "onStrokeEnd", pointsJson)
                }
            }
        })

        // Exclude the toolbar from TouchHelper's capture area so its buttons remain tappable.
        val toolbar = findViewById<LinearLayout>(R.id.toolbar)
        toolbar.doOnLayout {
            val svLoc = IntArray(2).also { canvasRenderer.getLocationOnScreen(it) }
            val tbLoc = IntArray(2).also { toolbar.getLocationOnScreen(it) }
            val excludeRect = Rect(
                tbLoc[0] - svLoc[0],
                tbLoc[1] - svLoc[1],
                tbLoc[0] + toolbar.width - svLoc[0],
                tbLoc[1] + toolbar.height - svLoc[1]
            )
            drawingEngine.setExcludeRects(listOf(excludeRect))
        }

        app.pluginEngine.setCanvasDelegate(canvasRenderer)
    }

    private fun setupToolbar() {
        val gelPenBtn = findViewById<TextView>(R.id.toolGelPen)
        val eraserBtn = findViewById<TextView>(R.id.toolEraser)

        gelPenBtn.setOnClickListener {
            activeToolPluginId = GEL_PEN_PLUGIN
            drawingEngine.setToolType(ToolType.GEL_PEN)
            gelPenBtn.setTextColor(getColor(R.color.black))
            gelPenBtn.setBackgroundColor(getColor(R.color.white))
            eraserBtn.setTextColor(getColor(R.color.white))
            eraserBtn.setBackgroundColor(getColor(R.color.black))
        }

        eraserBtn.setOnClickListener {
            activeToolPluginId = ERASER_PLUGIN
            drawingEngine.setToolType(ToolType.ERASER)
            eraserBtn.setTextColor(getColor(R.color.black))
            eraserBtn.setBackgroundColor(getColor(R.color.white))
            gelPenBtn.setTextColor(getColor(R.color.white))
            gelPenBtn.setBackgroundColor(getColor(R.color.black))
        }
    }

    private fun loadPages(soilPath: String, app: NoteSproutApp) {
        lifecycleScope.launch(Dispatchers.IO) {
            val notebookObj = notebookManager.openNotebook(soilPath)
            // Wire the now-open database into the plugin engine so data.save/query work
            app.pluginEngine.setDatabase(app.databaseManager.getCurrentDatabase())

            val notebookId = notebookObj?.id
                ?: intent.getStringExtra(EXTRA_NOTEBOOK_ID)
                ?: ""
            val notebookName = notebookObj?.let { notebookManager.getNotebookName(it) }
                ?: soilPath.substringAfterLast("/").removeSuffix(".soil")
            val pageList = if (notebookId.isNotEmpty()) {
                notebookManager.getPages(notebookId)
            } else emptyList()

            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.notebookTitle).text = notebookName
                pages = pageList
                currentPageIndex = 0
                updatePageIndicator()
            }
            if (pageList.isNotEmpty()) {
                loadPageLayer(pageList[0], app)
            }
        }
    }

    private fun loadCurrentPageLayer(app: NoteSproutApp) {
        if (pages.isEmpty()) return
        val page = pages[currentPageIndex]
        lifecycleScope.launch(Dispatchers.IO) { loadPageLayer(page, app) }
    }

    private suspend fun loadPageLayer(page: BaseObject, app: NoteSproutApp) {
        val layers = notebookManager.getLayers(page.id)
        val layer = layers.firstOrNull() ?: return
        currentLayerId = layer.id
        app.pluginEngine.setPageContext(page.id, layer.id)

        val strokes = notebookManager.getStrokes(layer.id)
        val drawableStrokes = buildDrawableStrokesJson(strokes)
        withContext(Dispatchers.Main) {
            canvasRenderer.clear()
            if (strokes.isNotEmpty()) canvasRenderer.redrawAll(drawableStrokes)
            canvasRenderer.refresh()
        }
    }

    private fun buildDrawableStrokesJson(strokes: List<BaseObject>): String {
        val arr = JSONArray()
        for (stroke in strokes) {
            try {
                val d = JSONObject(stroke.data)
                val points = d.optJSONArray("points")
                val style = d.optJSONObject("style")
                if (points != null && style != null) {
                    arr.put(JSONObject().apply {
                        put("points", points)
                        put("style", style)
                    })
                }
            } catch (_: Exception) {}
        }
        return arr.toString()
    }

    private fun buildSegmentJson(x1: Float, y1: Float, x2: Float, y2: Float, pressure: Float): String =
        JSONArray().apply {
            put(JSONObject().apply {
                put("x", x1.toDouble()); put("y", y1.toDouble())
                put("pressure", pressure.toDouble()); put("tilt", 0); put("timestamp", 0)
            })
            put(JSONObject().apply {
                put("x", x2.toDouble()); put("y", y2.toDouble())
                put("pressure", pressure.toDouble()); put("tilt", 0); put("timestamp", 0)
            })
        }.toString()

    private fun serializePoints(points: List<StrokePoint>): String {
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONObject().apply {
                put("x", p.x.toDouble())
                put("y", p.y.toDouble())
                put("pressure", p.pressure.toDouble())
                put("tilt", p.tilt.toDouble())
                put("timestamp", p.timestamp)
            })
        }
        return arr.toString()
    }

    private fun updatePageIndicator() {
        if (pages.isEmpty()) {
            pageIndicator.text = "0 / 0"
            prevPage.setTextColor(getColor(R.color.disabled_gray))
            nextPage.setTextColor(getColor(R.color.disabled_gray))
            return
        }
        val total = pages.size
        val current = currentPageIndex + 1
        pageIndicator.text = "$current / $total"
        prevPage.setTextColor(
            if (currentPageIndex == 0) getColor(R.color.disabled_gray) else getColor(R.color.white)
        )
        nextPage.setTextColor(
            if (currentPageIndex >= total - 1) getColor(R.color.disabled_gray) else getColor(R.color.white)
        )
    }
}
