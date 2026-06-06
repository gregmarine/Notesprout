package com.notesprout.android.toc

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.notesprout.android.R
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil

private const val ITEMS_PER_PAGE = 6
private const val HEADING_MAX_HEIGHT_DP = 52
private const val WIDE_SCREEN_THRESHOLD_DP = 480
private const val SIDEBAR_WIDTH_FRACTION = 0.60f
private const val EXTRA_NOTEBOOK_PATH = "extra_notebook_path"
private const val EXTRA_CURRENT_PAGE_ID = "extra_current_page_id"
private const val RESULT_PAGE_ID = "result_page_id"

class TocActivity : AppCompatActivity() {

    companion object {
        fun createIntent(context: Context, notebookPath: String, currentPageId: String): Intent {
            return Intent(context, TocActivity::class.java).apply {
                putExtra(EXTRA_NOTEBOOK_PATH, notebookPath)
                putExtra(EXTRA_CURRENT_PAGE_ID, currentPageId)
            }
        }
    }

    private lateinit var tocEntries: List<TocEntry>
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toc)

        flTocRoot = findViewById(R.id.flTocRoot)
        llTocPanel = findViewById(R.id.llTocPanel)
        btnTocClose = findViewById(R.id.btnTocClose)
        llTocList = findViewById(R.id.llTocList)
        tvTocPageIndicator = findViewById(R.id.tvTocPageIndicator)
        btnTocFirst = findViewById(R.id.btnTocFirst)
        btnTocPrev = findViewById(R.id.btnTocPrev)
        btnTocNext = findViewById(R.id.btnTocNext)
        btnTocLast = findViewById(R.id.btnTocLast)

        val screenWidthDp = resources.configuration.screenWidthDp
        val isFullScreen = screenWidthDp < WIDE_SCREEN_THRESHOLD_DP

        if (isFullScreen) {
            llTocPanel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            btnTocClose.visibility = View.VISIBLE
            btnTocClose.setOnClickListener {
                finish()
                overridePendingTransition(0, 0)
            }
            window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        } else {
            val widthPx = (screenWidthDp * SIDEBAR_WIDTH_FRACTION * resources.displayMetrics.density).toInt()
            llTocPanel.layoutParams = FrameLayout.LayoutParams(
                widthPx,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            btnTocClose.visibility = View.GONE
            flTocRoot.setOnClickListener {
                finish()
                overridePendingTransition(0, 0)
            }
            llTocPanel.setOnClickListener { /* consume, don't bubble to root */ }
            window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#66000000")))
        }

        btnTocFirst.setOnClickListener { currentTocPage = 0; renderCurrentTocPage() }
        btnTocPrev.setOnClickListener { currentTocPage--; renderCurrentTocPage() }
        btnTocNext.setOnClickListener { currentTocPage++; renderCurrentTocPage() }
        btnTocLast.setOnClickListener {
            currentTocPage = ceil(tocEntries.size.toDouble() / ITEMS_PER_PAGE).toInt() - 1
            renderCurrentTocPage()
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
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

        showLoading()

        val notebookPath = intent.getStringExtra(EXTRA_NOTEBOOK_PATH) ?: run { finish(); return }

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val db = Room.databaseBuilder(applicationContext, SoilDatabase::class.java, notebookPath)
                    .build()
                try {
                    TocRepository(db.notebookDao()).buildTocEntries()
                } finally {
                    db.close()
                }
            }
            tocEntries = entries
            renderCurrentTocPage()
        }
    }

    private fun showLoading() {
        llTocList.removeAllViews()
        val loading = TextView(this).apply {
            text = "Loading…"
            setTextColor(ContextCompat.getColor(this@TocActivity, R.color.inkBlack))
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        llTocList.addView(loading)
    }

    private fun renderCurrentTocPage() {
        llTocList.removeAllViews()

        if (tocEntries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No headings available"
                setTextColor(ContextCompat.getColor(this@TocActivity, R.color.inkBlack))
                textSize = 15f
                gravity = Gravity.CENTER
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

        val maxHeightPx = (HEADING_MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()

        for (entry in pageEntries) {
            val row = layoutInflater.inflate(R.layout.item_toc_entry, llTocList, false)
            val tvPageNumber = row.findViewById<TextView>(R.id.tvTocPageNumber)
            val flContainer = row.findViewById<FrameLayout>(R.id.flTocHeadingContainer)

            tvPageNumber.text = entry.pageNumber.toString()

            val thumbnail = HeadingThumbnailView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    maxHeightPx
                )
                setHeading(entry.heading, maxHeightPx)
            }
            flContainer.addView(thumbnail)

            row.setOnClickListener {
                val result = Intent().apply {
                    putExtra(RESULT_PAGE_ID, entry.pageId)
                }
                setResult(RESULT_OK, result)
                finish()
                overridePendingTransition(0, 0)
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
