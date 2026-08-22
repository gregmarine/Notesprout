package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The selection's context toolbar (P1): a small bordered bar that floats over the paper for as long
 * as a lasso selection is up. One button today — **Delete strokes** — but it is the shape the
 * selection's actions live in from here on, which is why it is a bar and not a button.
 *
 * It replaces R5's tap-inside-the-box action sheet. A sheet was a second deliberate act on top of
 * the lasso the user had *just* drawn, and on e-ink a dialog is a full repaint; the bar is already
 * there when the selection appears, and its rect is chrome the pen cannot ink through.
 *
 * Geometry is [SelectionAnchor]'s, in the notebook root's coordinates: [Bounds] arrive in **paper**
 * coordinates (paper-view pixels), get grown by [SELECTION_BOX_INFLATE_PX] so the gap is measured
 * from the box g-paper actually draws rather than the tight rect, and are then shifted by the
 * paper view's offset inside the root. Placement is by margins on the bar's
 * [FrameLayout.LayoutParams] — the bar is a floating child of the root, not part of either chrome
 * strip.
 *
 * The screen owns *when*: it shows on `onSelectionCreated`, hides on `onSelectionDragStarted`,
 * re-anchors after `onSelectionMoved`, and hides on dismissal and on every page swap. It also
 * unions [rect] into the exclusion rects and counts it as chrome for the finger paths.
 */
class SelectionToolbar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom strip's top; null before layout. */
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val onDelete: () -> Unit,
) {

    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        val size = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val pad = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
        val hint = ctx.getString(R.string.delete_selection_action)
        val delete = AppCompatImageButton(ctx).apply {
            setImageResource(R.drawable.ic_trash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            contentDescription = hint
            TooltipCompat.setTooltipText(this, hint)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener {
                // Release before the row runs, for the same reason the R5 sheet did: the tap has to
                // show its result, and the delete repaints the page underneath. Ungated — see the
                // frame-silence note in docs/notebook.md.
                releaseRender()
                onDelete()
            }
        }
        bar.addView(delete)
    }

    /** Show (or re-place) the bar for [bounds]. A no-op before the root has been laid out. */
    fun show(bounds: Bounds) {
        val band = band() ?: return
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val dx = paperLoc[0] - rootLoc[0]
        val dy = paperLoc[1] - rootLoc[1]
        val box = bounds.inflated(SELECTION_BOX_INFLATE_PX)

        // Measure before placing: the anchor centres and flips on the bar's real size, and a bar
        // that has never been visible has none.
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = SelectionAnchor.place(
            selLeft = (box.left + dx).toInt(),
            selTop = (box.top + dy).toInt(),
            selRight = (box.right + dx).toInt(),
            selBottom = (box.bottom + dy).toInt(),
            toolbarW = bar.measuredWidth,
            toolbarH = bar.measuredHeight,
            gap = (GAP_DP * density).toInt(),
            rootWidth = root.width,
            bandTop = band.first,
            bandBottom = band.last,
        )
        val lp = (bar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = p.x
        lp.topMargin = p.y
        bar.layoutParams = lp
    }

    /** Idempotent — every hide path (drag, dismiss, page swap, close) calls it without checking. */
    fun hide() {
        bar.visibility = View.GONE
    }

    /** The bar's rect in **window** coordinates, or null while hidden — for exclusions / `overChrome`. */
    fun rect(): Rect? {
        if (!isShowing || bar.width == 0 || bar.height == 0) return null
        val loc = IntArray(2)
        bar.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height)
    }

    fun contains(x: Int, y: Int): Boolean = rect()?.contains(x, y) == true

    private companion object {
        /** Gap between the drawn selection box and the bar. */
        const val GAP_DP = 8f

        /**
         * g-paper draws the selection box this far outside the tight `Selection.bounds`
         * (`CanvasPaperView.SELECTION_BOX_INFLATE_PX` — its companion is private, so the value is
         * mirrored here rather than referenced; keep the two in step across engine bumps).
         */
        const val SELECTION_BOX_INFLATE_PX = 12f
    }
}
