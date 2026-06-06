package com.notesprout.android.toc

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.notesprout.android.R
import kotlin.math.abs
import kotlin.math.ceil

private const val ITEMS_PER_PAGE = 6
private const val HEADING_MAX_HEIGHT_DP = 52
private const val WIDE_SCREEN_THRESHOLD_DP = 480
private const val SIDEBAR_WIDTH_FRACTION = 0.60f

class TocDialog(
    private val context: Context,
    private val entries: List<TocEntry>,
    private val onPageSelected: (pageId: String) -> Unit,
) {
    private val dialog = Dialog(context)
    private var tocEntries: List<TocEntry> = entries
    private var currentTocPage = 0

    private lateinit var flTocRoot: FrameLayout
    private lateinit var llTocPanel: LinearLayout
    private lateinit var btnTocClose: AppCompatImageButton
    private lateinit var llTocList: LinearLayout
    private lateinit var tvTocPageIndicator: AppCompatTextView
    private lateinit var btnTocFirst: AppCompatImageButton
    private lateinit var btnTocPrev: AppCompatImageButton
    private lateinit var btnTocNext: AppCompatImageButton
    private lateinit var btnTocLast: AppCompatImageButton

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.activity_toc)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(LayoutParams.FLAG_DIM_BEHIND)
            // Apply immersive flags before show() so system bars never appear.
            // decorView exists after setContentView; WindowInsetsController needs an attached view,
            // so we use the legacy flags here and reinforce with the modern API after show().
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        flTocRoot = dialog.findViewById(R.id.flTocRoot)
        llTocPanel = dialog.findViewById(R.id.llTocPanel)
        btnTocClose = dialog.findViewById(R.id.btnTocClose)
        llTocList = dialog.findViewById(R.id.llTocList)
        tvTocPageIndicator = dialog.findViewById(R.id.tvTocPageIndicator)
        btnTocFirst = dialog.findViewById(R.id.btnTocFirst)
        btnTocPrev = dialog.findViewById(R.id.btnTocPrev)
        btnTocNext = dialog.findViewById(R.id.btnTocNext)
        btnTocLast = dialog.findViewById(R.id.btnTocLast)

        val screenWidthDp = context.resources.configuration.screenWidthDp
        val isFullScreen = screenWidthDp < WIDE_SCREEN_THRESHOLD_DP

        if (isFullScreen) {
            flTocRoot.setBackgroundColor(Color.WHITE)
            llTocPanel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            btnTocClose.visibility = View.VISIBLE
            btnTocClose.setOnClickListener { dialog.dismiss() }
        } else {
            flTocRoot.setBackgroundColor(Color.TRANSPARENT)
            val widthPx = (screenWidthDp * SIDEBAR_WIDTH_FRACTION * context.resources.displayMetrics.density).toInt()
            llTocPanel.layoutParams = FrameLayout.LayoutParams(
                widthPx,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            btnTocClose.visibility = View.GONE
            flTocRoot.setOnClickListener { dialog.dismiss() }
            llTocPanel.setOnClickListener { /* consume — don't bubble to scrim */ }
        }

        btnTocFirst.setOnClickListener { currentTocPage = 0; renderCurrentTocPage() }
        btnTocPrev.setOnClickListener { currentTocPage--; renderCurrentTocPage() }
        btnTocNext.setOnClickListener { currentTocPage++; renderCurrentTocPage() }
        btnTocLast.setOnClickListener {
            currentTocPage = ceil(tocEntries.size.toDouble() / ITEMS_PER_PAGE).toInt() - 1
            renderCurrentTocPage()
        }

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val absX = abs(velocityX)
                val absY = abs(velocityY)
                if (absX > absY && absX > 100f) {
                    val totalTocPages = ceil(tocEntries.size.toDouble() / ITEMS_PER_PAGE).toInt()
                    if (velocityX < 0 && currentTocPage < totalTocPages - 1) {
                        currentTocPage++
                        renderCurrentTocPage()
                    } else if (velocityX > 0 && currentTocPage > 0) {
                        currentTocPage--
                        renderCurrentTocPage()
                    }
                    return true
                }
                return false
            }
        })
        llTocList.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        dialog.show()

        // Restore immersive fullscreen — Dialog has its own window and resets system bar visibility.
        dialog.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        renderCurrentTocPage()
    }

    private fun renderCurrentTocPage() {
        llTocList.removeAllViews()

        if (tocEntries.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No headings available"
                setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            llTocList.addView(empty)
            tvTocPageIndicator.text = ""
            listOf(btnTocFirst, btnTocPrev, btnTocNext, btnTocLast).forEach { it.isEnabled = false }
            return
        }

        val totalTocPages = ceil(tocEntries.size.toDouble() / ITEMS_PER_PAGE).toInt()
        currentTocPage = currentTocPage.coerceIn(0, totalTocPages - 1)

        val start = currentTocPage * ITEMS_PER_PAGE
        val end = minOf(start + ITEMS_PER_PAGE, tocEntries.size)
        val pageEntries = tocEntries.subList(start, end)

        val maxHeightPx = (HEADING_MAX_HEIGHT_DP * context.resources.displayMetrics.density).toInt()
        val inflater = android.view.LayoutInflater.from(context)

        for (entry in pageEntries) {
            val row = inflater.inflate(R.layout.item_toc_entry, llTocList, false)
            val tvPageNumber = row.findViewById<TextView>(R.id.tvTocPageNumber)
            val flContainer = row.findViewById<FrameLayout>(R.id.flTocHeadingContainer)

            tvPageNumber.text = entry.pageNumber.toString()

            val thumbnail = HeadingThumbnailView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    maxHeightPx
                )
                setHeading(entry.heading, maxHeightPx)
            }
            flContainer.addView(thumbnail)

            row.setOnClickListener {
                dialog.dismiss()
                onPageSelected(entry.pageId)
            }
            llTocList.addView(row)
        }

        tvTocPageIndicator.text = "${currentTocPage + 1} / $totalTocPages"
        btnTocFirst.isEnabled = currentTocPage > 0
        btnTocPrev.isEnabled = currentTocPage > 0
        btnTocNext.isEnabled = currentTocPage < totalTocPages - 1
        btnTocLast.isEnabled = currentTocPage < totalTocPages - 1
    }
}
