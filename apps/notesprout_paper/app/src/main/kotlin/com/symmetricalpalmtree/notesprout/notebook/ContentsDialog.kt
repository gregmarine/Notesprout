package com.symmetricalpalmtree.notesprout.notebook

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.core.TopGuard

/**
 * The Contents screen (arc 5 / C1): a full-window `Dialog` over the notebook that draws the outline
 * tree [ContentsSource] gathered — the original Notesprout `TocDialog` on Paper's rules. One layout
 * (`dialog_contents.xml`), two forms by [ContentsLayout.fullScreen]: full screen (white, back arrow)
 * below 480 dp; a 60 % left sidebar with a 1 dp inkBlack right border over a transparent scrim
 * (tap = dismiss) at or above. Rows are plain views in a `LinearLayout`, paginated by the body height
 * measured once after the first layout (`itemsPerPage`); the pager is the library's (`INVISIBLE` with
 * one page; a tap at a bound is a no-op — never a disabled look). The tree opens collapsed to the
 * roots except the highlighted entry's ancestors (Q4): the highlight is [OutlineTree.highlight] for
 * [currentPageIndex] — its row takes `bg_contents_active_entry`, and the list opens on the page that
 * holds it. Toggles re-render the same list page in place (clamped); a row tap dismisses and hands
 * the page index to [onPageSelected]. Expansion state lives in memory only. Immersive flags go on
 * before **and** after `show()` — a Dialog's window resets bar visibility on show.
 */
class ContentsDialog(
    private val activity: Activity,
    private val result: ContentsSource.Result.Ok,
    private val currentPageIndex: Int,
    private val onDismissed: () -> Unit,
    private val onPageSelected: (pageIndex: Int) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_Notesprout)
    private val roots = result.roots
    private val all = OutlineTree.all(roots)
    private val expanded = HashSet<String>()
    private var visible: List<OutlineTree.Node> = emptyList()
    private var listPage = 0
    private var itemsPerPage = 1

    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_contents)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Before show(): decorView exists after setContentView, but WindowInsetsController needs an
            // attached view — legacy flags here, the modern API after show() (the original's note).
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }

        val root = dialog.findViewById<FrameLayout>(R.id.contentsRoot)
        TopGuard.applyRootPadding(root)
        val panel = dialog.findViewById<LinearLayout>(R.id.panel)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnBack)
        rows = dialog.findViewById(R.id.rows)
        empty = dialog.findViewById(R.id.empty)
        pager = dialog.findViewById(R.id.pager)
        pageLabel = dialog.findViewById(R.id.pageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnLast)
        listOf(btnBack, btnFirst, btnPrev, btnNext, btnLast).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }

        val metrics = activity.resources.displayMetrics
        val widthDp = activity.resources.configuration.screenWidthDp
        val fullScreen = ContentsLayout.fullScreen(widthDp)
        if (fullScreen) {
            root.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            panel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(ContentsLayout.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT)
            panel.background = ContextCompat.getDrawable(activity, R.drawable.shape_contents_sidebar)
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable and consumes its own taps
        }

        val truncated = dialog.findViewById<TextView>(R.id.truncated)
        if (result.truncated) {
            truncated.text = activity.getString(R.string.contents_truncated, result.count)
            truncated.visibility = View.VISIBLE
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(OutlineTree.pageCount(visible.size, itemsPerPage) - 1) }

        // Opening state (Q4): the highlighted entry's ancestors open, the list on the page that shows it.
        val highlightAll = OutlineTree.highlight(all, currentPageIndex, all.map { it.id }.toSet())
        highlightAll?.let { id -> OutlineTree.find(all, id)?.let { expanded += OutlineTree.ancestorsOf(it) } }
        visible = OutlineTree.visible(roots, expanded)

        // itemsPerPage from the real body height, measured once after the first layout — no estimate.
        rows.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rows.viewTreeObserver.removeOnGlobalLayoutListener(this)
                itemsPerPage = ContentsLayout.itemsPerPage(rows.height - rows.paddingTop - rows.paddingBottom, metrics.density)
                val hl = OutlineTree.highlight(all, currentPageIndex, expanded)
                val idx = visible.indexOfFirst { it.id == hl }
                listPage = OutlineTree.pageOf(idx, itemsPerPage)
                render()
                Slog.d(TAG) { "shown: fullScreen=$fullScreen widthDp=$widthDp panel=${panel.width}px rows/page=$itemsPerPage entries=${result.count} visible=${visible.size} highlight=${hl != null}" }
            }
        })

        dialog.show()

        dialog.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    private fun goToListPage(page: Int) {
        val clamped = page.coerceIn(0, OutlineTree.pageCount(visible.size, itemsPerPage) - 1)
        if (clamped == listPage) return   // a tap at a bound is a no-op (the e-ink rule: never a disabled look)
        listPage = clamped
        render()
    }

    private fun toggle(id: String) {
        if (!expanded.remove(id)) expanded += id
        visible = OutlineTree.visible(roots, expanded)
        listPage = listPage.coerceIn(0, OutlineTree.pageCount(visible.size, itemsPerPage) - 1)
        render()
    }

    private fun render() {
        rows.removeAllViews()
        if (visible.isEmpty()) {
            empty.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        empty.visibility = View.GONE
        val pageCount = OutlineTree.pageCount(visible.size, itemsPerPage)
        listPage = listPage.coerceIn(0, pageCount - 1)
        val start = listPage * itemsPerPage
        val end = minOf(start + itemsPerPage, visible.size)
        val highlightId = OutlineTree.highlight(all, currentPageIndex, expanded)
        val inflater = LayoutInflater.from(activity)
        val density = activity.resources.displayMetrics.density
        for (node in visible.subList(start, end)) {
            val row = inflater.inflate(R.layout.item_contents_entry, rows, false)
            val content = row.findViewById<LinearLayout>(R.id.rowContent)
            val btnToggle = row.findViewById<AppCompatImageButton>(R.id.btnToggle)
            row.findViewById<TextView>(R.id.pageNumber).text = (node.pageIndex + 1).toString()
            row.findViewById<TextView>(R.id.label).text = node.label
            if (node.children.isNotEmpty()) {
                val open = node.id in expanded
                btnToggle.visibility = View.VISIBLE
                btnToggle.setImageResource(if (open) R.drawable.ic_minus else R.drawable.ic_plus)
                btnToggle.contentDescription = activity.getString(if (open) R.string.cd_collapse else R.string.cd_expand)
                TooltipCompat.setTooltipText(btnToggle, btnToggle.contentDescription)
                btnToggle.setOnClickListener { toggle(node.id) }
            } else {
                btnToggle.visibility = View.INVISIBLE   // keeps the columns aligned; no listener
            }
            content.setPaddingRelative(
                content.paddingStart + ContentsLayout.indentPx(node.level, density),
                content.paddingTop, content.paddingEnd, content.paddingBottom,
            )
            row.background = if (node.id == highlightId) ContextCompat.getDrawable(activity, R.drawable.bg_contents_active_entry) else null
            row.setOnClickListener {
                dialog.dismiss()
                onPageSelected(node.pageIndex)
            }
            rows.addView(row)
        }
        pageLabel.text = "${listPage + 1} / $pageCount"
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val TAG = "ContentsDialog"
    }
}
