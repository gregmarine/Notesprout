package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

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
import com.symmetricalpalmtree.notesproutsn.notebook.SelectionAnchor

/**
 * The pad's floating selection bar (arc 11 / J4, grown in J5): **Delete**, and **Send** when the pad
 * was opened from a notebook — the pad has no headings, no links, no clipboard and no snap, so the
 * notebook's seven buttons come down to the one that always applied plus the one that pays for the
 * hop. Send is absent, never disabled, when there is no notebook behind us.
 *
 * Geometry is `:sn-screen`'s [SelectionAnchor], in the root's coordinates: [Bounds] arrive in paper
 * coordinates, are grown by [SELECTION_BOX_INFLATE_PX] so the gap is measured from the box g-paper
 * actually draws, then shifted by the paper view's offset inside the root. Placement is by margins
 * on the bar's own [FrameLayout.LayoutParams] — it floats over the paper, in neither chrome bar.
 *
 * The screen owns *when*: shown at `onSelectionCreated`, hidden at `onSelectionDragStarted`,
 * re-anchored after `onSelectionMoved`, hidden on dismissal and on every page swap. It also unions
 * [rects] into the exclusion rects and counts the bar as chrome for the finger paths.
 */
class ScratchSelectionToolbar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom bar's top. */
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val onDelete: () -> Unit,
    /** Send the selected strokes to the notebook (J5). Never called when [sendEnabled] is false. */
    private val onSend: () -> Unit = {},
    /** Whether a notebook is behind us — false from the library, and then Send is never built. */
    sendEnabled: Boolean = false,
) {

    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        // Send first, Delete last — the notebook's selection bar puts its one destructive verb on
        // the far edge and the pad's tools are the notebook's, so its bar reads the same way.
        if (sendEnabled) {
            bar.addView(button(R.drawable.ic_pencil_down, ctx.getString(R.string.cd_scratch_send_selection)) {
                releaseRender()
                onSend()
            })
        }
        bar.addView(button(R.drawable.ic_trash, ctx.getString(R.string.delete_selection_action)) {
            // Release before the row runs: the tap has to show its result, and the delete repaints
            // the page underneath.
            releaseRender()
            onDelete()
        })
    }

    /** One toolbar button, to the one recipe: dimen-driven size, no ripple, tooltip == description. */
    private fun button(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton {
        val ctx = bar.context
        val size = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val pad = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
        return AppCompatImageButton(ctx).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            contentDescription = hint
            TooltipCompat.setTooltipText(this, hint)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
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
    fun rects(): List<Rect> = listOfNotNull(rectOf(bar))

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    private fun rectOf(v: View): Rect? {
        if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

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
