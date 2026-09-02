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
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * An extension screen's floating selection bar (arc 11 / J4 as the pad's own; shared here since
 * arc 23 / Y1, so the calendar's bar is not a sibling copy): a row of icon buttons that floats over
 * the paper next to a lasso selection, in neither chrome bar. Which buttons is the consumer's —
 * the pad has Delete and Send, the calendar the same two — and every one is built here to the one
 * recipe (dimen-driven size, no ripple, tooltip == description).
 *
 * Geometry is [SelectionAnchor], in the root's coordinates: [Bounds] arrive in paper coordinates,
 * are grown by [SELECTION_BOX_INFLATE_PX] so the gap is measured from the box g-paper actually
 * draws, then shifted by the paper view's offset inside the root. Placement is by margins on the
 * bar's own [FrameLayout.LayoutParams].
 *
 * The screen owns *when*: shown at `onSelectionCreated`, hidden at `onSelectionDragStarted`,
 * re-anchored after `onSelectionMoved`, hidden on dismissal and on every page swap. It also unions
 * [rects] into the exclusion rects and counts the bar as chrome for the finger paths.
 */
class FloatingSelectionBar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom bar's top. */
    private val band: () -> IntRange?,
    buttons: List<Button>,
) {

    /** One button: its Tabler glyph, its hint (tooltip + content description) and what it does. */
    class Button(val iconRes: Int, val hint: String, val onClick: () -> Unit)

    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        for (b in buttons) bar.addView(button(b))
    }

    private fun button(spec: Button): AppCompatImageButton {
        val ctx = bar.context
        val size = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val pad = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
        return AppCompatImageButton(ctx).apply {
            setImageResource(spec.iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            contentDescription = spec.hint
            TooltipCompat.setTooltipText(this, spec.hint)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { spec.onClick() }
        }
    }

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

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(bar).takeIf { bar.visibility == View.VISIBLE })

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    companion object {
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
