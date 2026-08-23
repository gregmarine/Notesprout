package com.symmetricalpalmtree.notesproutsn.notebook

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
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard

/**
 * The Contents screen (arc 4 / C1): a full-window `Dialog` over the notebook drawing the outline
 * [ContentsSource] gathered from the page's own `heading` rows. **One layout, two forms** —
 * `dialog_contents.xml` branches in code on [ContentsLayout.fullScreen]: below 480 dp the panel
 * fills the screen in plain paperWhite behind a back arrow; at or above it is a 60 % left sidebar
 * (its 1 dp inkBlack right border) over a transparent scrim whose tap dismisses. Rows are plain
 * inflated views in a `LinearLayout` and the list **paginates, it never scrolls** (the e-ink rule):
 * the body height is measured once after the first layout and `itemsPerPage` follows from it, with
 * the library's pager footer below (`INVISIBLE` at one page; a tap at a bound is a no-op, never a
 * disabled look). The tree opens collapsed to its roots except the highlighted entry's ancestors —
 * the highlight is [OutlineTree.highlight] for [currentPageIndex], its row takes
 * `bg_contents_active_entry`, and the list opens on the page holding it. **Expansion lives in
 * memory only** and the whole dialog is a modal snapshot: nothing is cached, nothing invalidates,
 * a row tap dismisses and hands its page index to [onPageSelected]. Immersive flags go on **before
 * and after** `show()` — a Dialog's window resets bar visibility as it shows, so the legacy flags
 * set on the decor view are re-asserted through the insets controller once it is attached.
 */
class ContentsDialog(
    private val activity: Activity,
    private val outline: ContentsSource.Outline,
    private val currentPageIndex: Int,
    private val onDismissed: () -> Unit,
    private val onPageSelected: (pageIndex: Int) -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_Notesprout)
    private val roots = outline.roots
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

        val root = dialog.findViewById<FrameLayout>(R.id.contentsRoot)
        TopGuard.applyRootPadding(root)
        val panel = dialog.findViewById<LinearLayout>(R.id.contentsPanel)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsBack)
        rows = dialog.findViewById(R.id.contentsRows)
        empty = dialog.findViewById(R.id.contentsEmpty)
        pager = dialog.findViewById(R.id.contentsPager)
        pageLabel = dialog.findViewById(R.id.contentsPageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsLast)
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
            // Plain paper, not the sidebar shape — its right border would be a stray line down the
            // screen edge.
            panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(
                ContentsLayout.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            )
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        val truncated = dialog.findViewById<TextView>(R.id.contentsTruncated)
        if (outline.truncated) {
            truncated.text = activity.getString(R.string.contents_truncated, outline.count)
            truncated.visibility = View.VISIBLE
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(OutlineTree.pageCount(visible.size, itemsPerPage) - 1) }

        // Opening state: the highlight is taken over the fully-expanded tree (so the real entry is
        // found, not a visible stand-in) and its ancestors open.
        val target = OutlineTree.highlight(all, currentPageIndex, all.map { it.id }.toSet())
        target?.let { id -> OutlineTree.find(all, id)?.let { expanded += OutlineTree.ancestorsOf(it) } }
        visible = OutlineTree.visible(roots, expanded)

        // itemsPerPage from the real body height, measured once after the first layout — no estimate.
        rows.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rows.viewTreeObserver.removeOnGlobalLayoutListener(this)
                itemsPerPage = ContentsLayout.itemsPerPage(
                    rows.height - rows.paddingTop - rows.paddingBottom, metrics.density,
                )
                val hl = OutlineTree.highlight(all, currentPageIndex, expanded)
                listPage = OutlineTree.pageOf(visible.indexOfFirst { it.id == hl }, itemsPerPage)
                render()
                Slog.d(TAG) {
                    "shown: fullScreen=$fullScreen widthDp=$widthDp panel=${panel.width}px " +
                        "rows/page=$itemsPerPage entries=${outline.count} visible=${visible.size} " +
                        "highlight=${hl != null}"
                }
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

    /** Safe when nothing is showing — the host's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    private fun goToListPage(page: Int) {
        val clamped = page.coerceIn(0, OutlineTree.pageCount(visible.size, itemsPerPage) - 1)
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
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
            row.findViewById<TextView>(R.id.entryPage).text = (node.pageIndex + 1).toString()
            row.findViewById<TextView>(R.id.entryLabel).text = node.label
            if (node.children.isNotEmpty()) {
                val open = node.id in expanded
                btnToggle.visibility = View.VISIBLE
                btnToggle.setImageResource(if (open) R.drawable.ic_minus else R.drawable.ic_plus)
                btnToggle.contentDescription =
                    activity.getString(if (open) R.string.cd_collapse else R.string.cd_expand)
                TooltipCompat.setTooltipText(btnToggle, btnToggle.contentDescription)
                btnToggle.setOnClickListener { toggle(node.id) }
            } else {
                btnToggle.visibility = View.INVISIBLE   // keeps the columns aligned; no listener
            }
            // Fresh inflation every render, so the indent is applied to the XML padding once and
            // never compounds.
            content.setPaddingRelative(
                content.paddingStart + ContentsLayout.indentPx(node.level, density),
                content.paddingTop, content.paddingEnd, content.paddingBottom,
            )
            row.background = if (node.id == highlightId) {
                ContextCompat.getDrawable(activity, R.drawable.bg_contents_active_entry)
            } else {
                null
            }
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
