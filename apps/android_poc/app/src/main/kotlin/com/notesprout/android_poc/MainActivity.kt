package com.notesprout.android_poc

import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OnyxPOC"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var touchHelper: TouchHelper

    // Guard so TouchHelper is only created once — the first surfaceChanged with real dimensions.
    private var touchHelperInitialized = false

    // Tracks whether the surface is valid so lifecycle methods don't act on a dead surface.
    private var surfaceReady = false

    private val callback = object : RawInputCallback() {

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint) {
            Log.i(TAG, "onBeginRawDrawing: screenMoved=$p0 x=${p1.x} y=${p1.y}")
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint) {
            Log.i(TAG, "onEndRawDrawing: screenMoved=$p0 x=${p1.x} y=${p1.y}")
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint) {
            Log.i(TAG, "onRawDrawingTouchPointMoveReceived: x=${p0.x} y=${p0.y} pressure=${p0.pressure}")
        }

        override fun onRawDrawingTouchPointListReceived(p0: TouchPointList) {
            Log.i(TAG, "onRawDrawingTouchPointListReceived: ${p0.size()} points")
        }

        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint) {
            Log.i(TAG, "onBeginRawErasing: screenMoved=$p0 x=${p1.x} y=${p1.y}")
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint) {
            Log.i(TAG, "onEndRawErasing: screenMoved=$p0 x=${p1.x} y=${p1.y}")
        }

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint) {
            Log.i(TAG, "onRawErasingTouchPointMoveReceived: x=${p0.x} y=${p0.y}")
        }

        override fun onRawErasingTouchPointListReceived(p0: TouchPointList) {
            Log.i(TAG, "onRawErasingTouchPointListReceived: ${p0.size()} points")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        supportActionBar?.hide()

        surfaceView = SurfaceView(this).also { sv ->
            // Keep the surface behind the window so the Onyx kernel driver sees it
            // at the expected Z-level for raw input interception.
            sv.setZOrderOnTop(false)
            sv.setZOrderMediaOverlay(false)
        }
        setContentView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated — waiting for surfaceChanged for real dimensions")
                surfaceReady = true
                fillWhite(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "surfaceChanged: ${width}x${height}")
                fillWhite(holder)

                if (width == 0 || height == 0) {
                    Log.i(TAG, "surfaceChanged: ignoring zero dimensions")
                    return
                }

                if (!touchHelperInitialized) {
                    // First call with real dimensions — create TouchHelper now so the limit
                    // rect is never set to 0x0 (surfaceView.width/height is 0 at surfaceCreated time).
                    touchHelperInitialized = true
                    initTouchHelper(width, height)
                } else if (::touchHelper.isInitialized) {
                    // Surface resized — update limit rect and re-register with the kernel.
                    applyLimitRect(width, height)
                    Log.i(TAG, "re-entering scribble mode after surfaceChanged resize")
                    EpdController.enterScribbleMode(surfaceView)
                    touchHelper.setRawDrawingEnabled(false)
                    touchHelper.setRawDrawingEnabled(true)
                    touchHelper.setRawInputReaderEnable(true)
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

    // Forward every MotionEvent to TouchHelper. Required when TouchHelper selects the
    // AppTouchRender path (e.g. if DeviceFeatureUtil.hasStylus() returns false on Android 15).
    // No-ops on the SFTouchRender (kernel) path, so safe to always call.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::touchHelper.isInitialized) {
            touchHelper.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun fillWhite(holder: SurfaceHolder) {
        val canvas = holder.lockCanvas() ?: return
        canvas.drawColor(Color.WHITE)
        holder.unlockCanvasAndPost(canvas)
    }

    private fun applyLimitRect(width: Int, height: Int) {
        val rect = Rect(0, 0, width, height)
        Log.i(TAG, "setLimitRect: ${rect.width()}x${rect.height()}")
        touchHelper.setLimitRect(listOf(rect), emptyList())
    }

    private fun initTouchHelper(width: Int, height: Int) {
        Log.i(TAG, "initTouchHelper: surface ${width}x${height}")

        // FEATURE_SF_TOUCH_RENDER forces the kernel-level raw input path, bypassing the
        // DeviceFeatureUtil.hasStylus() auto-detection that can silently fall back to
        // the MotionEvent path (AppTouchRender) on Android 15.
        touchHelper = TouchHelper.create(surfaceView, TouchHelper.FEATURE_SF_TOUCH_RENDER, callback)
            .setStrokeWidth(3.0f)
            .setStrokeColor(Color.BLACK)
            .setLimitRect(
                listOf(Rect(0, 0, width, height)),
                emptyList()
            )
            .openRawDrawing()

        Log.i(TAG, "openRawDrawing done — isRawDrawingCreated=${touchHelper.isRawDrawingCreated()}")

        // Tell the BOOX kernel driver to activate raw stylus interception for this surface.
        // The pen SDK only calls leaveScribbleMode internally; enterScribbleMode is the
        // app's responsibility and is the missing link when callbacks don't fire.
        Log.i(TAG, "enterScribbleMode")
        EpdController.enterScribbleMode(surfaceView)

        touchHelper.setRawDrawingEnabled(true)
        Log.i(TAG, "setRawDrawingEnabled(true) — isRawDrawingInputEnabled=${touchHelper.isRawDrawingInputEnabled()} isRawDrawingRenderEnabled=${touchHelper.isRawDrawingRenderEnabled()}")

        touchHelper.setRawInputReaderEnable(true)
        Log.i(TAG, "setRawInputReaderEnable(true)")

        Log.i(TAG, "TouchHelper init complete")
    }

    override fun onResume() {
        super.onResume()
        if (::touchHelper.isInitialized && surfaceReady) {
            Log.i(TAG, "onResume — re-entering scribble mode")
            EpdController.enterScribbleMode(surfaceView)
            touchHelper.setRawDrawingEnabled(true)
            touchHelper.setRawInputReaderEnable(true)
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
        if (::touchHelper.isInitialized) {
            touchHelper.closeRawDrawing()
            Log.i(TAG, "closeRawDrawing — onDestroy")
        }
    }
}
