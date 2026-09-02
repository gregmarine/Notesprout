package com.symmetricalpalmtree.notesproutsn.ext.calendar

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
 * The calendar's chrome (arc 23 / Y1): Back and the three tools on the top bar, Send at its far end
 * when a notebook is behind us; the pager — prev, the period's title, next — alone on the bottom
 * bar. The tool half is `:sn-screen`'s [PaperToolbar]; this adds what is the calendar's own: the
 * fixed tool values, Send, the pager, and the title behind the frame-silence gate.
 *
 * **The tools are fixed, and they are the notebook's** — the pad's rule, for the pad's reason: PEN ·
 * black · [PEN_WIDTH_PX], eraser [ERASER_RADIUS_PX], no panels, no colour, nothing remembered. Smart
 * lasso and scribble erase are armed by the screen before the listener attaches.
 *
 * **Send exists only when there is somewhere to send to**: opened from the library there is no
 * notebook behind us, so the button is absent rather than present-and-failing — GONE, never disabled.
 *
 * **The title waits for the pen.** Never present an app frame while [PaperView.isPenActive] — the
 * rule is SN-wide, and this bar is the screen's only text that changes.
 */
class CalendarToolbar(
    private val paper: PaperView,
    topBar: View,
    btnBack: ImageButton,
    btnPen: ImageButton,
    btnEraser: ImageButton,
    btnLasso: ImageButton,
    private val btnSend: ImageButton,
    private val btnPrev: ImageButton,
    private val btnNext: ImageButton,
    private val title: TextView,
    onBack: () -> Unit,
    /** Send this whole page to the notebook. Never called when [sendEnabled] is false — the button is GONE. */
    onSend: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    sendEnabled: Boolean,
) {

    private val tools: PaperToolbar

    init {
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX

        tools = PaperToolbar(
            bar = topBar,
            btnBack = btnBack,
            btnPen = btnPen,
            btnEraser = btnEraser,
            btnLasso = btnLasso,
            paper = paper,
            onBack = onBack,
        )

        listOf(btnPrev, btnNext, btnSend).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnPrev.setOnClickListener { releaseRenderIfIdle(); onPrev() }
        btnNext.setOnClickListener { releaseRenderIfIdle(); onNext() }
        btnSend.visibility = if (sendEnabled) View.VISIBLE else View.GONE
        btnSend.setOnClickListener { releaseRenderIfIdle(); onSend() }
        title.text = ""
    }

    /** Make the tool buttons honest — driven from `PaperListener.onToolChanged`, never from a tap:
     *  smart lasso arms LASSO and restores PEN on its own. */
    fun sync(tool: Tool) = tools.sync(tool)

    /** The period's title, presented only once the pen is idle (the frame-silence rule). */
    fun setTitle(text: String) {
        whenPenIdle { title.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) {
        if (!paper.isPenActive) { action(); return }
        title.postDelayed({ whenPenIdle(action) }, PaperView.PEN_ACTIVE_TAIL_MS)
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
