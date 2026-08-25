package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.InkColorCodec
import com.symmetricalpalmtree.notesproutsn.notebook.PaperToolbar

/**
 * The pad's chrome (arc 11 / J4): Back and the title on the top bar, the three tools and the page
 * arrows on the bottom one. The tool half is `:sn-screen`'s [PaperToolbar] — the whole reason that
 * module exists — and this adds what is the pad's own: the fixed tool values, the page arrows, and
 * the page indicator behind the frame-silence gate.
 *
 * **The tools are fixed, and they are the notebook's.** PEN · black · [PEN_WIDTH_PX], eraser
 * [ERASER_RADIUS_PX] — no panels, no colour, nothing remembered. Smart lasso and scribble erase are
 * armed by the screen before the listener attaches: a pad one tap from the notebook that lassoed
 * differently would read as a bug.
 *
 * **The arrows no-op at a bound, never disable.** A greyed control is invisible on e-ink (the
 * standing rule), so the buttons always look the same and simply do nothing at page 1 or page N.
 *
 * **The indicator waits for the pen.** Never present an app frame while [PaperView.isPenActive] —
 * the rule is SN-wide, and this bar is the pad's only text that changes.
 */
class ScratchToolbar(
    private val paper: PaperView,
    bottomBar: View,
    btnBack: ImageButton,
    btnPen: ImageButton,
    btnEraser: ImageButton,
    btnLasso: ImageButton,
    private val btnPrevPage: ImageButton,
    private val btnNextPage: ImageButton,
    private val pageIndicator: TextView,
    onBack: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
) {

    private val tools: PaperToolbar

    init {
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX

        tools = PaperToolbar(
            bar = bottomBar,
            btnBack = btnBack,
            btnPen = btnPen,
            btnEraser = btnEraser,
            btnLasso = btnLasso,
            paper = paper,
            onBack = onBack,
        )

        listOf(btnPrevPage, btnNextPage).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnPrevPage.setOnClickListener { releaseRenderIfIdle(); onPrevPage() }
        btnNextPage.setOnClickListener { releaseRenderIfIdle(); onNextPage() }
        pageIndicator.text = ""
    }

    /** Make the tool buttons honest — driven from `PaperListener.onToolChanged`, never from a tap:
     *  smart lasso arms LASSO and restores PEN on its own. */
    fun sync(tool: Tool) = tools.sync(tool)

    /** `n / N`, presented only once the pen is idle (the frame-silence rule). */
    fun setPage(number: Int, total: Int) {
        val text = pageIndicator.context.getString(R.string.scratch_page_indicator, number, total)
        whenPenIdle { pageIndicator.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        pageIndicator.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
    }

    /** The [PaperView.releaseRender] contract: pen-gated, or a tap inside the pen-up tail can cost
     *  a live stroke. While the pen is active nobody is looking at a pressed state anyway. */
    private fun releaseRenderIfIdle() {
        if (!paper.isPenActive) paper.releaseRender()
    }

    companion object {
        /** The one pen width, in px — the notebook's, so the two surfaces write identically. */
        const val PEN_WIDTH_PX = 3f

        /** The one eraser hit radius, in px — g-paper's default, and the notebook's. */
        const val ERASER_RADIUS_PX = 15f
    }
}
