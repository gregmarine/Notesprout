package com.symmetricalpalmtree.notesproutsn.notebook

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Immersive
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.library.GridMath
import java.util.Date

/**
 * The Recents screen (arc 10 / T1): a full-window `Dialog` over the notebook listing the notebooks
 * [RecentsSource] resolved from `sn_recents` — **the ToC's twin, mirrored**. One layout, two forms
 * (`dialog_recents.xml` branches on [ContentsLayout.fullScreen] — the 480 dp breakpoint is shared, so
 * "a sidebar doesn't fit here" is decided once): below 480 dp it fills the screen in plain paperWhite
 * behind a back arrow, at or above it is a **right** sidebar (its 2 dp inkBlack *left* border) over a
 * transparent scrim whose tap dismisses. Its width is [RecentRows.SIDEBAR_WIDTH_FRACTION] — **50 %,
 * narrower than the Contents' 60 %**, because a row is a name, a time and a path.
 *
 * Rows are plain inflated views in a `LinearLayout` and the list **paginates, it never scrolls** (the
 * e-ink rule): one row is inflated and measured at the real panel width after the first layout, and
 * `itemsPerPage` follows from that — three lines at two text sizes is not a height worth guessing.
 * The pager footer is `INVISIBLE` at one page and a tap at a bound is a no-op, never a disabled look.
 *
 * A modal snapshot: nothing is cached and nothing invalidates. A row tap dismisses and hands its
 * **notebook id** to [onNotebookSelected] — the host re-checks and seals before it launches, so this
 * screen never has to be right about a notebook that died while it was up.
 */
class RecentsDialog(
    private val activity: Activity,
    private val rows: List<RecentsSource.Row>,
    private val onDismissed: () -> Unit,
    private val onNotebookSelected: (notebookId: String) -> Unit,
) {
    /** The list's one-finger page flip. A `Dialog` owns its own window, so the sequence is taken
     *  from the dialog's `dispatchTouchEvent` rather than the Activity's — the panel is a different
     *  window and the notebook behind it never sees these events. */
    private val listSwipe = ListSwipe(
        region = { body },
        onFlipNext = { goToListPage(listPage + 1) },
        onFlipPrevious = { goToListPage(listPage - 1) },
    )

    private val dialog = object : Dialog(activity, R.style.Theme_Notesprout) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            listSwipe.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }

    /** The body band the rows paginate inside — the region the flip arms on; null until shown. */
    private var body: View? = null
    private var listPage = 0
    private var itemsPerPage = 1

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_recents)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Before show(): the decor view exists after setContentView but is not attached yet, and
            // WindowInsetsController needs an attached view — legacy flags here, the modern API once
            // the window is up (a Dialog's window resets bar visibility as it shows).
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }

        val root = dialog.findViewById<FrameLayout>(R.id.recentsRoot)
        TopGuard.applyRootPadding(root)
        val panel = dialog.findViewById<LinearLayout>(R.id.recentsPanel)
        body = dialog.findViewById(R.id.recentsBody)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsBack)
        list = dialog.findViewById(R.id.recentsRows)
        empty = dialog.findViewById(R.id.recentsEmpty)
        pager = dialog.findViewById(R.id.recentsPager)
        pageLabel = dialog.findViewById(R.id.recentsPageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsLast)
        listOf(btnBack, btnFirst, btnPrev, btnNext, btnLast).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }

        val metrics = activity.resources.displayMetrics
        val widthDp = activity.resources.configuration.screenWidthDp
        val fullScreen = ContentsLayout.fullScreen(widthDp)
        if (fullScreen) {
            root.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            panel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // Plain paper, not the sidebar shape — its left border would be a stray line down the
            // screen edge.
            panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(
                RecentRows.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            ).also { it.gravity = android.view.Gravity.END }
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(GridMath.pageCount(rows.size, itemsPerPage) - 1) }

        // itemsPerPage from the real body height and a really-measured row, once after the first
        // layout — a three-line row's height depends on the font scale, so nothing is estimated.
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val body = list.height - list.paddingTop - list.paddingBottom
                itemsPerPage = RecentRows.itemsPerPage(body, measureRowHeightPx())
                listPage = 0
                render()
                Slog.d(TAG) {
                    "shown: fullScreen=$fullScreen widthDp=$widthDp panel=${panel.width}px " +
                        "rows/page=$itemsPerPage entries=${rows.size}"
                }
            }
        })

        dialog.show()

        dialog.window?.let { w -> Immersive.apply(w, w.decorView) }
    }

    /** Safe when nothing is showing — the host's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** Inflate one row, measure it at the list's real width, and return its full height in px. */
    private fun measureRowHeightPx(): Int {
        val sample = LayoutInflater.from(activity).inflate(R.layout.item_recent_entry, list, false)
        sample.measure(
            View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return sample.measuredHeight
    }

    private fun goToListPage(page: Int) {
        val clamped = GridMath.clampPage(page, GridMath.pageCount(rows.size, itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
        listPage = clamped
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (rows.isEmpty()) {
            empty.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        empty.visibility = View.GONE
        val pageCount = GridMath.pageCount(rows.size, itemsPerPage)
        listPage = GridMath.clampPage(listPage, pageCount)
        val range = GridMath.pageRange(listPage, itemsPerPage, rows.size)
        val inflater = LayoutInflater.from(activity)
        val dateFormat = DateFormat.getMediumDateFormat(activity)
        val timeFormat = DateFormat.getTimeFormat(activity)
        for (i in range) {
            val entry = rows[i]
            val row = inflater.inflate(R.layout.item_recent_entry, list, false)
            row.findViewById<TextView>(R.id.recentName).text = entry.name
            val at = Date(entry.timestamp)
            row.findViewById<TextView>(R.id.recentWhen).text =
                activity.getString(R.string.recents_when, dateFormat.format(at), timeFormat.format(at))
            row.findViewById<TextView>(R.id.recentPath).text = entry.folderPath
            row.setOnClickListener {
                dialog.dismiss()
                onNotebookSelected(entry.notebookId)
            }
            list.addView(row)
        }
        pageLabel.text = activity.getString(R.string.page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val TAG = "RecentsDialog"
    }
}
