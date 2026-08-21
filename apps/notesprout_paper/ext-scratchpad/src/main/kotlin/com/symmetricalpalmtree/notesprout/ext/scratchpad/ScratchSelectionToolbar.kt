package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesprout.ext.scratchpad.databinding.ActivityScratchPadBinding
import com.symmetricalpalmtree.notesprout.notebook.PaperToolbar
import com.symmetricalpalmtree.notesprout.notebook.ToolbarAnchor

/**
 * The pad's floating selection toolbar (arc 6 / S1): **Delete · [Send]** over a lasso selection —
 * two fixed buttons in the layout (`selectionBar`), not the core's description-drawn
 * `SelectionToolbar` (a shared one waits for a third consumer — Deferred). Placed by the shared
 * [ToolbarAnchor] (8 dp below the drawn selection box, centred, flipped above when it would cross
 * the bottom strip, clamped to the band between the chrome bars); its rect joins the exclusion
 * rects ([rects]) and the over-chrome test ([contains]). Send is present only when the pad was
 * opened from a notebook — the lasso's strokes to the notebook (S2).
 */
class ScratchSelectionToolbar(
    private val binding: ActivityScratchPadBinding,
    private val paperView: View,
    sendEnabled: Boolean,
    private val releaseRender: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
) {
    private val bar = binding.selectionBar
    private var selection: Bounds? = null

    init {
        binding.btnSelSend.visibility = if (sendEnabled) View.VISIBLE else View.GONE
        listOf(binding.btnSelDelete, binding.btnSelSend).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
        binding.btnSelDelete.setOnClickListener { releaseRender(); onDelete() }
        binding.btnSelSend.setOnClickListener { releaseRender(); onSend() }
    }

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    fun show(selectionBounds: Bounds) {
        selection = selectionBounds
        anchor()
    }

    fun hide() {
        selection = null
        bar.visibility = View.GONE
    }

    /** The bar's rect in window coordinates while visible (for `setExclusionRects` / `overChrome`). */
    fun rects(): List<Rect> = listOfNotNull(if (isShowing) PaperToolbar.rectOf(bar) else null)

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    /** The free band between the chrome bars, in window coordinates; null before layout. */
    private fun band(): IntRange? {
        val top = PaperToolbar.rectOf(binding.topBar)?.bottom
        val bottom = PaperToolbar.rectOf(binding.bottomStrip)?.top
        return if (top != null && bottom != null && bottom > top) top..bottom else null
    }

    private fun anchor() {
        val sel = selection ?: return
        val band = band() ?: return
        val root = binding.root
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val gap = (GAP_DP * root.resources.displayMetrics.density).toInt()
        val box = sel.inflated(SELECTION_BOX_INFLATE_PX)
        val l = (box.left + paperLoc[0]).toInt(); val t = (box.top + paperLoc[1]).toInt()
        val r = (box.right + paperLoc[0]).toInt(); val b = (box.bottom + paperLoc[1]).toInt()
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = ToolbarAnchor.place(l, t, r, b, bar.measuredWidth, bar.measuredHeight, gap, rootLoc[0] + root.width, band.first, band.last)
        val lp = (bar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = p.x - rootLoc[0]
        lp.topMargin = p.y - rootLoc[1]
        bar.layoutParams = lp
    }

    private companion object {
        const val GAP_DP = 8f
        /** g-paper draws the selection box this far outside the tight bounds (kept in step with the core's `SelectionToolbar`). */
        const val SELECTION_BOX_INFLATE_PX = 12f
    }
}
