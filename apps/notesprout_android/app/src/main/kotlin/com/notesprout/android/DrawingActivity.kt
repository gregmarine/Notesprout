package com.notesprout.android

import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import com.notesprout.android.databinding.ActivityDrawingBinding
import com.notesprout.android.drawing.DrawingView
import com.notesprout.android.drawing.GenericDrawingView
import com.notesprout.android.drawing.OnyxDrawingView
import java.util.Locale

class DrawingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDrawingBinding
    private lateinit var drawingView: DrawingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive — equivalent to Flutter's SystemUiMode.immersiveSticky.
        // Canvas sits at physical screen (0,0), so Onyx touch coordinates align exactly.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityDrawingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        drawingView = if (isBooxDevice()) OnyxDrawingView(this) else GenericDrawingView(this)
        binding.drawingContainer.addView(
            drawingView.asView(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Pass the toolbar's pixel height to the drawing view after layout so
        // BOOX can set the correct setLimitRect exclusion zone.
        binding.drawingToolbar.doOnLayout { toolbar ->
            drawingView.setToolbarHeight(toolbar.height)
        }

        binding.btnClear.setOnClickListener { drawingView.clearCanvas() }
    }

    override fun onResume() {
        super.onResume()
        drawingView.enableDrawing()
    }

    override fun onPause() {
        super.onPause()
        drawingView.disableDrawing()
    }

    override fun onDestroy() {
        super.onDestroy()
        drawingView.releaseResources()
    }

    private fun isBooxDevice() =
        Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")
}
