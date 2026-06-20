package com.notesprout.android.toc

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Row height = thumbnail max height (52dp) + paddingTop (8dp) + paddingBottom (8dp) from item_toc_entry.xml
private const val ROW_HEIGHT_DP = 68f
private const val ROW_SEPARATOR_DP = 1f
private const val HEADING_MAX_HEIGHT_DP = 52
private const val WIDE_SCREEN_THRESHOLD_DP = 480
private const val SIDEBAR_WIDTH_FRACTION = 0.60f

// Extra paddingStart added per heading level above 1 (16dp × (level - 1))
private const val LEVEL_INDENT_DP = 16f

class TocDialog(
    private val context: Context,
    private val nodes: List<TocNode>,
    private val currentPageIndex: Int = 0,
    private val onPageSelected: (pageId: String) -> Unit,
) {
    private val dialog = Dialog(context)

    /** Pre-order flattened list of all nodes — this is what pagination iterates. */
    private val flatNodes: List<TocNode> = buildFlatList(nodes)

    private var currentTocPage = 0
    private var activeNode: TocNode? = null
    private var itemsPerPage = 1

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
            currentTocPage = ceil(flatNodes.size.toDouble() / itemsPerPage).toInt() - 1
            renderCurrentTocPage()
        }

        val density = context.resources.displayMetrics.density

        // Measure the actual list height after layout to compute itemsPerPage accurately.
        // This accounts for the real available space on any device without relying on density
        // estimates or hardcoded overhead values.
        llTocList.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                llTocList.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val usableHeightPx = llTocList.height - llTocList.paddingTop - llTocList.paddingBottom
                val rowHeightPx = (ROW_HEIGHT_DP + ROW_SEPARATOR_DP) * density
                itemsPerPage = maxOf(1, floor(usableHeightPx / rowHeightPx).toInt())

                // Determine the active node: the last flat node whose pageIndex <= currentPageIndex.
                activeNode = flatNodes
                    .filter { it.pageIndex <= currentPageIndex }
                    .maxByOrNull { it.pageIndex }
                val activeIndex = activeNode?.let { flatNodes.indexOf(it) } ?: -1
                currentTocPage = if (activeIndex >= 0) activeIndex / itemsPerPage else 0

                renderCurrentTocPage()
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

    private fun renderCurrentTocPage() {
        llTocList.removeAllViews()

        if (flatNodes.isEmpty()) {
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

        val totalTocPages = ceil(flatNodes.size.toDouble() / itemsPerPage).toInt()
        currentTocPage = currentTocPage.coerceIn(0, totalTocPages - 1)

        val start = currentTocPage * itemsPerPage
        val end = minOf(start + itemsPerPage, flatNodes.size)
        val pageNodes = flatNodes.subList(start, end)

        val density = context.resources.displayMetrics.density
        val maxHeightPx = (HEADING_MAX_HEIGHT_DP * density).toInt()
        val indentPerLevelPx = (LEVEL_INDENT_DP * density).roundToInt()
        val inflater = android.view.LayoutInflater.from(context)

        for (node in pageNodes) {
            val row = inflater.inflate(R.layout.item_toc_entry, llTocList, false)
            val tvPageNumber = row.findViewById<TextView>(R.id.tvTocPageNumber)
            val flContainer = row.findViewById<FrameLayout>(R.id.flTocHeadingContainer)
            val tvHeadingText = row.findViewById<TextView>(R.id.tvHeadingText)

            tvPageNumber.text = node.pageNumber.toString()

            // Apply level-based indent: H1 = no extra indent, H2 = +16dp, H3 = +32dp.
            // Indent is added to the FrameLayout container so both text and thumbnail shift.
            val extraIndentPx = (node.level - 1) * indentPerLevelPx
            val existingStart = flContainer.paddingStart
            flContainer.setPaddingRelative(
                existingStart + extraIndentPx,
                flContainer.paddingTop,
                flContainer.paddingEnd,
                flContainer.paddingBottom,
            )

            if (node.title.isNotEmpty()) {
                tvHeadingText.text = node.title  // already prefix-stripped
                tvHeadingText.visibility = View.VISIBLE
            } else {
                // Unrecognized heading — render stroke thumbnail
                val thumbnail = HeadingThumbnailView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        maxHeightPx
                    )
                    setHeading(node.heading, maxHeightPx)
                }
                flContainer.addView(thumbnail)
            }

            if (node.pageIndex == activeNode?.pageIndex) {
                row.background = ContextCompat.getDrawable(context, R.drawable.bg_toc_active_entry)
            } else {
                row.background = null
            }

            row.setOnClickListener {
                dialog.dismiss()
                onPageSelected(node.pageId)
            }
            llTocList.addView(row)
        }

        tvTocPageIndicator.text = "${currentTocPage + 1} / $totalTocPages"
        btnTocFirst.isEnabled = currentTocPage > 0
        btnTocPrev.isEnabled = currentTocPage > 0
        btnTocNext.isEnabled = currentTocPage < totalTocPages - 1
        btnTocLast.isEnabled = currentTocPage < totalTocPages - 1
    }

    companion object {
        /**
         * Flattens [roots] into a pre-order list (parent then its children recursively).
         * This is the display order for the paginated flat-indented TOC.
         */
        private fun buildFlatList(roots: List<TocNode>): List<TocNode> {
            val result = mutableListOf<TocNode>()
            fun visit(node: TocNode) {
                result += node
                node.children.forEach { visit(it) }
            }
            roots.forEach { visit(it) }
            return result
        }
    }
}
