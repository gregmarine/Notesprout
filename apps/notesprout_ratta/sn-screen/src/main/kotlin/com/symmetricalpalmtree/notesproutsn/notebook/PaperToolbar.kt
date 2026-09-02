package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool

/**
 * Back plus the three tool buttons of a paper-hosting screen (arc 11 / J1), **binding-free**: it
 * takes the views themselves rather than a generated binding, which is the whole reason the
 * notebook's own [NotebookToolbar] could not simply move here — that one is hard-bound to
 * `ActivityNotebookBinding` and carries the notebook's clipboard-loaded icon swap and lasso re-tap.
 * This is the spartan version the Scratch Pad extension wants: arm a tool, keep the buttons honest.
 *
 * Two rules, the same two [NotebookToolbar] documents:
 *  - **Release the render first — but pen-gated.** While the EPD writing overlay is armed the bar
 *    will not show a new pressed state and the tap reads as broken. The gate is
 *    [PaperView.releaseRender]'s own contract: an ungated release inside the pen-active window can
 *    cost a live stroke.
 *  - **[sync] is the truth, not our taps.** g-paper changes tools by itself (smart lasso arms
 *    LASSO and restores PEN when the selection goes), so button state is driven from
 *    `PaperListener.onToolChanged` — never assumed from the tap that started it.
 *
 * Selected = the bordered `state_selected` look of `bg_toolbar_button`. No colour anywhere.
 */
class PaperToolbar(
    private val bar: View,
    private val btnBack: ImageButton,
    private val btnPen: ImageButton,
    private val btnEraser: ImageButton,
    private val btnLasso: ImageButton,
    private val paper: PaperView,
    private val onBack: () -> Unit,
) {
    init {
        listOf(btnBack, btnPen, btnEraser, btnLasso).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnBack.setOnClickListener { releaseRenderIfIdle(); onBack() }
        btnPen.setOnClickListener { select(Tool.PEN) }
        btnEraser.setOnClickListener { select(Tool.ERASER) }
        btnLasso.setOnClickListener { select(Tool.LASSO) }
        sync(paper.tool)
    }

    /**
     * Tapping a tool arms it. A second tap on the armed one changes nothing — a button that
     * disarmed itself would leave the pen doing something the bar isn't showing.
     */
    private fun select(tool: Tool) {
        releaseRenderIfIdle()
        if (paper.tool != tool) paper.tool = tool
        sync(tool)
    }

    /**
     * Make the buttons honest about [tool]. Called from `PaperListener.onToolChanged` — the
     * component arms and restores tools on its own (smart lasso), so this runs for changes the
     * screen never initiated.
     */
    fun sync(tool: Tool) {
        btnPen.isSelected = tool == Tool.PEN
        btnEraser.isSelected = tool == Tool.ERASER
        btnLasso.isSelected = tool == Tool.LASSO
    }

    /** The bar's rect in window coordinates (for `setExclusionRects`), or null before layout. */
    fun rectInWindow(): Rect? = rectOf(bar)

    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        /** A laid-out view's rect in window coordinates, or null before layout has run. */
        fun rectOf(v: View): Rect? {
            if (v.width == 0 || v.height == 0) return null
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
    }
}
