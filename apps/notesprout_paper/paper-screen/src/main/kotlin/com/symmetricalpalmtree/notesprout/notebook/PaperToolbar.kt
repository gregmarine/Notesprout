package com.symmetricalpalmtree.notesprout.notebook

import android.graphics.Rect
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool

/**
 * The three tool buttons + back of a paper-hosting screen (arc 6 / S0: `NotebookToolbar` moved to
 * `:paper-screen` and renamed — the notebook and the Scratch Pad extension both wire their top bars
 * through it). Selected tool = the bordered `state_selected` look of
 * `bg_toolbar_button` (no colour). [sync] is what `PaperListener.onToolChanged` calls so the
 * buttons stay honest when the component changes tools itself. Every tap calls
 * `releaseRender()` first so an EPD panel actually shows the new state.
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
        btnBack.setOnClickListener { paper.releaseRender(); onBack() }
        btnPen.setOnClickListener { select(Tool.PEN) }
        btnEraser.setOnClickListener { select(Tool.ERASER) }
        btnLasso.setOnClickListener { select(Tool.LASSO) }
        sync(paper.tool)
    }

    private fun select(tool: Tool) {
        paper.releaseRender()
        if (paper.tool != tool) paper.tool = tool
        sync(tool)
    }

    fun sync(tool: Tool) {
        btnPen.isSelected = tool == Tool.PEN
        btnEraser.isSelected = tool == Tool.ERASER
        btnLasso.isSelected = tool == Tool.LASSO
    }

    /** The bar's rect in window coordinates (for `setExclusionRects`), or null before layout. */
    fun rectInWindow(): Rect? = rectOf(bar)

    companion object {
        fun rectOf(v: View): Rect? {
            if (v.width == 0 || v.height == 0) return null
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
    }
}
