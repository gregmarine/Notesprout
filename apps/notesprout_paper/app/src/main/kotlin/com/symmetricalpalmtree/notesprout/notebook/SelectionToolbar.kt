package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesprout.R

/**
 * The floating selection toolbar (arc 4 / H2) — a bordered row of buttons over the paper while a
 * lasso selection is active, plus a second row (the **sub-toolbar**) for a parent action's leaves.
 * Core-drawn from descriptions: every button is either an icon from the core catalog or the action's
 * label as text (same `toolbar_button_size` square, `bg_toolbar_button` — `state_selected` bordered
 * for an active sub-action), with the long-press hint = the action's hint. Every tap calls
 * `releaseRender()` first (EPD chrome release), like [NotebookToolbar].
 *
 * Geometry is [ToolbarAnchor]'s: the toolbar sits [GAP_DP] below the selection, centred, flips
 * above when it would cross the bottom strip and is clamped between the top bar and the bottom
 * strip; the sub-toolbar hangs off the toolbar (not the selection). Both rows are FrameLayout
 * children of the notebook root, placed by margins. The rows' rects ([rects]) join the exclusion
 * rects and `overChrome`; the screen owns *when* to show (pen-idle) and hides on drag / dismiss /
 * page change. Contents come from `SelectionActions.merge`; the core Delete
 * ([SelectionActions.CORE_DELETE_ID]) routes to [Listener.onDelete], everything else to
 * [Listener.onAction] with its provider key.
 */
class SelectionToolbar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    private val subBar: LinearLayout,
    /** The free band between chrome, in window coordinates: top bar's bottom .. bottom strip's top; null before layout. */
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        fun onDelete()
        fun onAction(providerKey: String?, action: ToolbarAction)
    }

    private var items: List<ToolbarItem> = emptyList()
    private var activeIds: Set<String> = emptySet()
    private var openParent: ToolbarItem? = null
    private var selection: Bounds? = null

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    /** Fill and anchor the toolbar for a selection; any open sub-toolbar closes (selection changed). */
    fun show(items: List<ToolbarItem>, activeIds: Set<String>, selectionBounds: Bounds) {
        this.items = items
        this.activeIds = activeIds
        this.selection = selectionBounds
        openParent = null
        buildBar()
        subBar.removeAllViews()
        subBar.visibility = View.GONE
        anchor()
    }

    fun hide() {
        openParent = null
        items = emptyList()
        selection = null
        bar.visibility = View.GONE
        subBar.visibility = View.GONE
        bar.removeAllViews()
        subBar.removeAllViews()
    }

    /** The visible rows' rects in window coordinates (for `setExclusionRects` / `overChrome`). */
    fun rects(): List<Rect> = listOfNotNull(
        if (bar.visibility == View.VISIBLE) NotebookToolbar.rectOf(bar) else null,
        if (subBar.visibility == View.VISIBLE) NotebookToolbar.rectOf(subBar) else null,
    )

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    // ── Building ─────────────────────────────────────────────────────────────

    private fun buildBar() {
        bar.removeAllViews()
        for (item in items) {
            bar.addView(button(item.action, selected = false) { tapped(item, item.action) })
        }
    }

    private fun buildSub(parent: ToolbarItem) {
        subBar.removeAllViews()
        for (leaf in parent.action.subActions) {
            subBar.addView(button(leaf, selected = leaf.id in activeIds) { tapped(parent, leaf) })
        }
    }

    private fun tapped(item: ToolbarItem, action: ToolbarAction) {
        releaseRender()
        if (action.isParent) {
            // Toggle this parent's sub-toolbar (a second tap closes it; another parent replaces it).
            openParent = if (openParent?.action?.id == action.id) null else item
            openParent?.let { buildSub(it) }
            anchor()
            return
        }
        openParent = null
        subBar.visibility = View.GONE
        subBar.removeAllViews()
        if (item.providerKey == null && action.id == SelectionActions.CORE_DELETE_ID) listener.onDelete()
        else listener.onAction(item.providerKey, action)
    }

    private fun button(action: ToolbarAction, selected: Boolean, onTap: () -> Unit): View {
        val ctx = bar.context
        val res = ctx.resources
        val size = res.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val pad = res.getDimensionPixelSize(R.dimen.toolbar_button_padding)
        val v: View = if (action.iconRes != null) {
            AppCompatImageButton(ctx).apply {
                setImageResource(action.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
            }
        } else {
            // Text fallback: the label centred in the same square, sized relative to the button (the
            // tablet tier's 62 dp square reads as big as its icons) and by length so ≤ 6 chars fit.
            AppCompatButton(ctx).apply {
                text = action.label
                setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size * when (action.label.length) { 1, 2 -> 0.5f; 3, 4 -> 0.34f; else -> 0.26f })
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                maxLines = 1
                gravity = Gravity.CENTER
                minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
            }
        }
        v.setBackgroundResource(R.drawable.bg_toolbar_button)
        v.stateListAnimator = null
        v.layoutParams = LinearLayout.LayoutParams(size, size)
        v.contentDescription = action.hint
        TooltipCompat.setTooltipText(v, action.hint)
        v.isSelected = selected
        v.setOnClickListener { onTap() }
        return v
    }

    // ── Anchoring ────────────────────────────────────────────────────────────

    /** Place (and show) the rows for the current selection; a no-op before layout (re-run on the next show). */
    private fun anchor() {
        val sel = selection ?: return
        val band = band() ?: return
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val rootWidth = root.width
        val gap = (GAP_DP * root.resources.displayMetrics.density).toInt()
        // Selection bounds (paper coordinates == paper-view pixels) → window coordinates, grown to the
        // box g-paper actually draws so the gap is measured from the visible chrome, not the tight rect.
        val box = sel.inflated(SELECTION_BOX_INFLATE_PX)
        val l = (box.left + paperLoc[0]).toInt(); val t = (box.top + paperLoc[1]).toInt()
        val r = (box.right + paperLoc[0]).toInt(); val b = (box.bottom + paperLoc[1]).toInt()

        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = bar.measuredWidth; val h = bar.measuredHeight
        val p = ToolbarAnchor.place(l, t, r, b, w, h, gap, rootLoc[0] + rootWidth, band.first, band.last)
        place(bar, p.x - rootLoc[0], p.y - rootLoc[1])

        if (openParent != null) {
            subBar.visibility = View.VISIBLE
            subBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val sw = subBar.measuredWidth; val sh = subBar.measuredHeight
            val q = ToolbarAnchor.placeSub(p, w, h, sw, sh, gap, rootLoc[0] + rootWidth, band.first, band.last)
            place(subBar, q.x - rootLoc[0], q.y - rootLoc[1])
        } else {
            subBar.visibility = View.GONE
        }
    }

    private fun place(v: View, x: Int, y: Int) {
        val lp = (v.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x
        lp.topMargin = y
        v.layoutParams = lp
    }

    private companion object {
        /** Gap between the drawn selection box and the toolbar, and between the toolbar and its sub-toolbar. */
        const val GAP_DP = 8f
        /** g-paper draws the selection box this far outside the tight `Selection.bounds`
         *  (`CanvasPaperView.SELECTION_BOX_INFLATE_PX`, not part of the public API — keep in step). */
        const val SELECTION_BOX_INFLATE_PX = 12f
    }
}
