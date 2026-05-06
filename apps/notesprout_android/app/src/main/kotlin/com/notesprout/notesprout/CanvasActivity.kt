package com.notesprout.notesprout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.notesprout.notesprout.data.SoilDatabase
import com.notesprout.notesprout.data.StrokeModel
import com.notesprout.notesprout.data.StrokePoint
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.UUID

class CanvasActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Canvas"
        private const val SOIL_FILE = "notebook.soil"
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var touchHelper: TouchHelper
    private var touchHelperInitialized = false
    private var surfaceReady = false

    private var folderPath: String = ""

    @Volatile private var layerId: String? = null
    @Volatile private var db: SoilDatabase? = null

    private var committedBitmap: Bitmap? = null
    private var committedCanvas: Canvas? = null

    private val strokePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val activePoints = mutableListOf<StrokePoint>()

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint) {
            Log.i(TAG, "onBeginRawDrawing x=${p1.x} y=${p1.y}")
            activePoints.clear()
            activePoints.add(StrokePoint(
                x = p1.x.toDouble(), y = p1.y.toDouble(),
                pressure = p1.pressure.toDouble(), tilt = p1.tiltX.toDouble(),
                timestamp = System.currentTimeMillis()
            ))
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint) {
            Log.i(TAG, "onEndRawDrawing")
        }

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
                drawStrokeToBitmap(stroke)
                blitBitmapToSurface()
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

        val closeFab = FloatingActionButton(this).apply {
            setImageDrawable(resources.getDrawable(android.R.drawable.ic_menu_close_clear_cancel, theme))
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        val fabMargin = (16 * resources.displayMetrics.density).toInt()
        val fabParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, fabMargin, fabMargin)
        }
        root.addView(closeFab, fabParams)

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
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = closeFab.layoutParams as FrameLayout.LayoutParams
            params.setMargins(0, 0, systemBars.right + fabMargin, systemBars.bottom + fabMargin)
            closeFab.layoutParams = params
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
                    EpdController.enterScribbleMode(surfaceView)
                    touchHelper.setRawDrawingEnabled(false)
                    touchHelper.setRawDrawingEnabled(true)
                    touchHelper.setRawInputReaderEnable(true)
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
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::touchHelper.isInitialized) touchHelper.onTouchEvent(ev)
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
        Log.i(TAG, "usableLimitRect: [$left,$top,$right,$bottom] screenFrame=$frame svOffset=${loc[0]},${loc[1]}")
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
                val page = database.getFirstPage() ?: run {
                    Log.w(TAG, "No page found in $soilPath")
                    database.close()
                    return@Thread
                }
                val layer = database.getFirstLayer(page.id) ?: run {
                    Log.w(TAG, "No layer found for pageId=${page.id}")
                    database.close()
                    return@Thread
                }
                layerId = layer.id
                val strokes = database.getStrokes(layer.id)
                db = database
                Log.i(TAG, "Loaded ${strokes.size} strokes, layerId=${layer.id}")
                runOnUiThread {
                    strokes.forEach { drawStrokeToBitmap(it) }
                    blitBitmapToSurface()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadStrokesFromDb failed", e)
                database.close()
            }
        }.start()
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
        if (::touchHelper.isInitialized && surfaceReady) {
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
        if (::touchHelper.isInitialized) touchHelper.closeRawDrawing()
        db?.close()
        committedBitmap?.recycle()
    }
}
