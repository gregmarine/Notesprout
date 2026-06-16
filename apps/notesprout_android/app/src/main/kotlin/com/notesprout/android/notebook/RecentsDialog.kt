package com.notesprout.android.notebook

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.DateFormat
import android.view.View
import android.view.ViewTreeObserver
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
import com.notesprout.android.data.recents.ResolvedRecent
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor

private const val WIDE_SCREEN_THRESHOLD_DP = 480
private const val SIDEBAR_WIDTH_FRACTION = 0.60f
private const val ROW_SEPARATOR_DP = 1f

/**
 * TOC-style paginated "Recent Notebooks" switch dialog, opened from the NotebookActivity toolbar.
 *
 * Modeled on [com.notesprout.android.toc.TocDialog] — same responsive chrome (full-screen on
 * narrow, left-anchored sidebar on wide), the same first/prev/next/last pagination, and the same
 * post-layout row measurement to compute items-per-page.
 *
 * [entries] is the resolved recents list already filtered to exclude the currently-open notebook.
 * [onRecentSelected] is invoked (after dismiss) when a row is tapped — stubbed by callers in
 * Session 4; the full switch flow lands in Session 5.
 */
class RecentsDialog(
    private val context: Context,
    private val entries: List<ResolvedRecent>,
    private val onRecentSelected: (notebookId: String) -> Unit,
) {
    private val dialog = Dialog(context)
    private var currentPage = 0
    private var itemsPerPage = 1

    private lateinit var flRoot: FrameLayout
    private lateinit var llPanel: LinearLayout
    private lateinit var btnClose: AppCompatImageButton
    private lateinit var llList: LinearLayout
    private lateinit var tvPageIndicator: AppCompatTextView
    private lateinit var btnFirst: AppCompatImageButton
    private lateinit var btnPrev: AppCompatImageButton
    private lateinit var btnNext: AppCompatImageButton
    private lateinit var btnLast: AppCompatImageButton

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_recents)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(LayoutParams.FLAG_DIM_BEHIND)
            // Apply immersive flags before show() so system bars never appear (matches TocDialog).
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

        flRoot = dialog.findViewById(R.id.flRecentsRoot)
        llPanel = dialog.findViewById(R.id.llRecentsPanel)
        btnClose = dialog.findViewById(R.id.btnRecentsClose)
        llList = dialog.findViewById(R.id.llRecentsList)
        tvPageIndicator = dialog.findViewById(R.id.tvRecentsPageIndicator)
        btnFirst = dialog.findViewById(R.id.btnRecentsFirst)
        btnPrev = dialog.findViewById(R.id.btnRecentsPrev)
        btnNext = dialog.findViewById(R.id.btnRecentsNext)
        btnLast = dialog.findViewById(R.id.btnRecentsLast)

        val screenWidthDp = context.resources.configuration.screenWidthDp
        val isFullScreen = screenWidthDp < WIDE_SCREEN_THRESHOLD_DP

        if (isFullScreen) {
            flRoot.setBackgroundColor(Color.WHITE)
            llPanel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            btnClose.visibility = View.VISIBLE
            btnClose.setOnClickListener { dialog.dismiss() }
        } else {
            flRoot.setBackgroundColor(Color.TRANSPARENT)
            val widthPx = (screenWidthDp * SIDEBAR_WIDTH_FRACTION * context.resources.displayMetrics.density).toInt()
            llPanel.layoutParams = FrameLayout.LayoutParams(
                widthPx,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            btnClose.visibility = View.GONE
            flRoot.setOnClickListener { dialog.dismiss() }
            llPanel.setOnClickListener { /* consume — don't bubble to scrim */ }
        }

        btnFirst.setOnClickListener { currentPage = 0; renderCurrentPage() }
        btnPrev.setOnClickListener { currentPage--; renderCurrentPage() }
        btnNext.setOnClickListener { currentPage++; renderCurrentPage() }
        btnLast.setOnClickListener {
            currentPage = ceil(entries.size.toDouble() / itemsPerPage).toInt() - 1
            renderCurrentPage()
        }

        // Measure the actual list height and a sample row's height after layout to compute
        // itemsPerPage accurately — the row has three text lines, so a hardcoded estimate is
        // fragile; measuring the inflated row keeps pagination exact on any device/font scale.
        llList.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                llList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val usableHeightPx = llList.height - llList.paddingTop - llList.paddingBottom
                val rowHeightPx = measureRowHeightPx()
                itemsPerPage = maxOf(1, floor(usableHeightPx / rowHeightPx).toDouble().toInt())
                currentPage = 0
                renderCurrentPage()
            }
        })

        dialog.show()

        // Restore immersive fullscreen — Dialog has its own window and resets system bar visibility.
        dialog.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** Inflate one row, measure it at the list's width, and return its full height in px. */
    private fun measureRowHeightPx(): Float {
        val inflater = android.view.LayoutInflater.from(context)
        val sample = inflater.inflate(R.layout.item_recent_entry, llList, false)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(llList.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        sample.measure(widthSpec, heightSpec)
        val separatorPx = ROW_SEPARATOR_DP * context.resources.displayMetrics.density
        return sample.measuredHeight + separatorPx
    }

    private fun renderCurrentPage() {
        llList.removeAllViews()

        if (entries.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No recent notebooks"
                setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            llList.addView(empty)
            tvPageIndicator.text = ""
            listOf(btnFirst, btnPrev, btnNext, btnLast).forEach { it.isEnabled = false }
            return
        }

        val totalPages = ceil(entries.size.toDouble() / itemsPerPage).toInt()
        currentPage = currentPage.coerceIn(0, totalPages - 1)

        val start = currentPage * itemsPerPage
        val end = minOf(start + itemsPerPage, entries.size)
        val pageEntries = entries.subList(start, end)

        val inflater = android.view.LayoutInflater.from(context)
        for (entry in pageEntries) {
            val row = inflater.inflate(R.layout.item_recent_entry, llList, false)
            val tvName = row.findViewById<TextView>(R.id.tvRecentName)
            val tvDateTime = row.findViewById<TextView>(R.id.tvRecentDateTime)
            val tvFolderPath = row.findViewById<TextView>(R.id.tvRecentFolderPath)

            tvName.text = entry.notebookName

            val opened = Date(entry.timestamp)
            val dateStr = DateFormat.getMediumDateFormat(context).format(opened)
            val timeStr = DateFormat.getTimeFormat(context).format(opened)
            tvDateTime.text = "$dateStr, $timeStr"

            tvFolderPath.text = entry.folderPath

            row.setOnClickListener {
                dialog.dismiss()
                onRecentSelected(entry.notebookId)
            }
            llList.addView(row)
        }

        tvPageIndicator.text = "${currentPage + 1} / $totalPages"
        btnFirst.isEnabled = currentPage > 0
        btnPrev.isEnabled = currentPage > 0
        btnNext.isEnabled = currentPage < totalPages - 1
        btnLast.isEnabled = currentPage < totalPages - 1
    }
}
