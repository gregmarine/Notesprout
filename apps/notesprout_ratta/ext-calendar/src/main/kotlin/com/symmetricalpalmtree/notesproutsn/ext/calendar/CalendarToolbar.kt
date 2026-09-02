package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.core.InkColorCodec
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.notebook.PaperToolbar

/**
 * The calendar's chrome (arc 23): Back and the three tools on the top bar, then Today and the three
 * view toggles at its far end with Send beyond them when a notebook is behind us; the pager — prev,
 * the period's title, next — alone on the bottom bar. The tool half is `:sn-screen`'s
 * [PaperToolbar]; this adds what is the calendar's own: the fixed tool values, the navigation
 * controls, Send, the pager, and the title behind the frame-silence gate.
 *
 * **The three toggles are words, not glyphs** — there is no icon for "week" worth learning, and
 * words read better on e-ink. The armed one is a `Widget.Notesprout.LatchButton` with `isSelected`
 * set, which reads as a thicker border. It is set from [setView] on every page shown, **never from
 * the tap that asked for it**: what is latched is what is on the paper, so a navigation that failed
 * cannot leave a lie in the bar.
 *
 * **The pager's title is itself a tap target** — it opens the day picker. A title that says where
 * you are is the natural place to ask to be somewhere else, and it costs no width on the narrowest
 * bar we have.
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
    private val btnToday: Button,
    private val btnMonth: Button,
    private val btnWeek: Button,
    private val btnDay: Button,
    private val btnPrev: ImageButton,
    private val btnNext: ImageButton,
    private val title: TextView,
    onBack: () -> Unit,
    /** Send this whole page to the notebook. Never called when [sendEnabled] is false — the button is GONE. */
    onSend: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    /** Today, in whatever view is showing. */
    onToday: () -> Unit,
    /** Show a [CalendarTarget.KIND_MONTH] / `_WEEK` / `_DAY` page. Called for the showing view too —
     *  the screen decides that a toggle to where we already are does nothing. */
    onView: (kind: Int) -> Unit,
    /** The pager's title was tapped: open the day picker. */
    onTitle: () -> Unit,
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

        // Every button carries a hint naming it — the word buttons included: their tooltip is their
        // own text, which is what a long press on a truncated latch is for.
        listOf(btnPrev, btnNext, btnSend, btnToday, btnMonth, btnWeek, btnDay, title).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnPrev.setOnClickListener { releaseRenderIfIdle(); onPrev() }
        btnNext.setOnClickListener { releaseRenderIfIdle(); onNext() }
        btnSend.visibility = if (sendEnabled) View.VISIBLE else View.GONE
        btnSend.setOnClickListener { releaseRenderIfIdle(); onSend() }
        btnToday.setOnClickListener { releaseRenderIfIdle(); onToday() }
        btnMonth.setOnClickListener { releaseRenderIfIdle(); onView(CalendarTarget.KIND_MONTH) }
        btnWeek.setOnClickListener { releaseRenderIfIdle(); onView(CalendarTarget.KIND_WEEK) }
        btnDay.setOnClickListener { releaseRenderIfIdle(); onView(CalendarTarget.KIND_DAY) }
        title.setOnClickListener { releaseRenderIfIdle(); onTitle() }
        title.text = ""
    }

    /** Latch the toggle for the page that is **showing** — driven from the screen's `showPage`, the
     *  way [sync] is driven from `onToolChanged` rather than from a tap. The change rides the
     *  navigation's own frame; it is never a frame of its own. */
    fun setView(kind: Int) {
        btnMonth.isSelected = kind == CalendarTarget.KIND_MONTH
        btnWeek.isSelected = kind == CalendarTarget.KIND_WEEK
        btnDay.isSelected = kind == CalendarTarget.KIND_DAY
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
