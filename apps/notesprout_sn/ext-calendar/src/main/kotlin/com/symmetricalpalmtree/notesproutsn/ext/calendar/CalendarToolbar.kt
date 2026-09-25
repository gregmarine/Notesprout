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
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle
import com.symmetricalpalmtree.notesproutsn.notebook.PenShadeGlyph

/**
 * The calendar's chrome (arc 23): Back and the three tools on the top bar, then Today and the three
 * view toggles at its far end, then Send (only when a notebook is behind us), Events (arc 24 — always)
 * and the Scratch Pad door last; the pager — prev, the period's title, next — alone on the bottom bar. The tool half is `:sn-screen`'s
 * [PaperToolbar]; this adds what is the calendar's own: the fixed tool values, the navigation
 * controls, Send, the pager, and the title behind the frame-silence gate.
 *
 * **The three toggles are words, not glyphs** — there is no icon for "week" worth learning, and
 * words read better on e-ink — but Today and the three view latches became Tabler icons at Y4 on
 * the user's calls (calendar-star · calendar-month · calendar-week · calendar with two ruled lines). The armed latch
 * has `isSelected` set, which reads as the ToolbarButton's border. It is set from [setView] on every page shown, **never from
 * the tap that asked for it**: what is latched is what is on the paper, so a navigation that failed
 * cannot leave a lie in the bar.
 *
 * **The pager's title is itself a tap target** — it opens the day picker. A title that says where
 * you are is the natural place to ask to be somewhere else, and it costs no width on the narrowest
 * bar we have.
 *
 * **The tools are fixed, and they are the notebook's** — the pad's rule, for the pad's reason: PEN ·
 * [PEN_WIDTH_PX], eraser [ERASER_RADIUS_PX], no width or style panels. Smart
 * lasso and scribble erase are armed by the screen before the listener attaches. Since arc 29 / LE3
 * the eraser has two kinds, reached the notebook's way: a second tap on the armed eraser opens the
 * shared `EraserBar` (Point · Lasso) — the screen owns the bar, this just forwards the re-tap.
 * **Since arc 49 / P4 the pen has a shade** — the pad's arrangement exactly: a second tap on the
 * armed pen opens the shared `PaletteBar` (sixteen greys, one device-wide level the host carries in
 * on the launch Intent and reads back off the result), the screen owns that bar, this forwards the
 * re-tap ([onPenReTap]) and wears the tone on the pen button ([reportPenShade], [PenShadeGlyph]).
 *
 * **Send exists only when there is somewhere to send to**: opened from the library there is no
 * notebook behind us, so the button is absent rather than present-and-failing — GONE, never disabled.
 * Since arc 31 / HV4 the same button is the **out-door**: Send page, Export (the host has an
 * exporter — `EXTRA_CALENDAR_EXPORT_ENABLED`), or a sheet offering both; the icon says which.
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
    btnEvents: ImageButton,
    private val btnScratchPad: ImageButton,
    private val btnToday: View,
    private val btnMonth: View,
    private val btnWeek: View,
    private val btnDay: View,
    private val btnPrev: ImageButton,
    private val btnNext: ImageButton,
    private val title: TextView,
    onBack: () -> Unit,
    /** Send this whole page to the notebook. Never called when [sendEnabled] is false. */
    onSend: () -> Unit,
    /** Export this page as a file — the host's Export screen (arc 31 / HV4). Never called when
     *  [exportEnabled] is false. */
    onExport: () -> Unit,
    /** Both doors are open: the screen raises its Send page · Export… sheet. Never called unless
     *  [sendEnabled] and [exportEnabled] are both true. */
    onSendOrExport: () -> Unit,
    /** The Events door (arc 24 / Z2): the calendar's own day list, in this same process.
     *  Always available — every day has events, or has room for them. */
    onEvents: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    /** Today, in whatever view is showing. */
    onToday: () -> Unit,
    /** Show a [CalendarTarget.KIND_MONTH] / `_WEEK` / `_DAY` page. Called for the showing view too —
     *  the screen decides that a toggle to where we already are does nothing. */
    onView: (kind: Int) -> Unit,
    /** The pager's title was tapped: open the day picker. */
    onTitle: () -> Unit,
    /** The Scratch Pad door (Y4): leave for the pad — the host opens it and brings us back. Never
     *  called when [scratchPadAvailable] is false — the button is GONE. */
    onScratchPad: () -> Unit,
    /** A tap on the already-armed eraser (arc 29 / LE3): the screen toggles the eraser sub-bar. */
    onEraserReTap: () -> Unit,
    /** Any actual tool change — the screen closes the sub-bar that belonged to the old tool. */
    onToolTapped: () -> Unit,
    /** A tap on the already-armed pen (arc 49 / P4): the screen toggles the shade panel. */
    onPenReTap: () -> Unit = {},
    sendEnabled: Boolean,
    scratchPadAvailable: Boolean,
    exportEnabled: Boolean,
    /** After every sync (arc 36) — the collapsed chrome's corner button repaints from here. */
    onSynced: () -> Unit = {},
) {

    private val tools: PaperToolbar

    /** The pen button wearing its shade (arc 49 / P4) — black until the screen arms the level the
     *  host launched it in (`InkScreenActivity.initPenShade`). */
    private val penGlyph = PenShadeGlyph(btnPen, InkColorCodec.BLACK)

    init {
        paper.tool = Tool.PEN
        // Black until the screen applies the device's shade — the same first answer as before P4.
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
            onEraserReTap = onEraserReTap,
            onToolTapped = onToolTapped,
            onSynced = onSynced,
            onPenReTap = { onPenReTap() },
        )

        // Every button carries a hint naming it — the word buttons included: their tooltip is their
        // own text, which is what a long press on a truncated latch is for.
        listOf(btnPrev, btnNext, btnSend, btnEvents, btnScratchPad, btnToday, btnMonth, btnWeek, btnDay, title).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnPrev.setOnClickListener { releaseRenderIfIdle(); onPrev() }
        btnNext.setOnClickListener { releaseRenderIfIdle(); onNext() }
        // ONE out-door button (arc 31 / HV4, the user's call — a twelfth button overflows the
        // Nomad's bar on the notebook door): Send alone, Export alone (the download icon), or both
        // behind a two-row sheet. GONE when neither door is open — GONE, never disabled.
        when {
            sendEnabled && exportEnabled -> {
                btnSend.contentDescription = btnSend.context.getString(R.string.cd_calendar_send_or_export)
                btnSend.setOnClickListener { releaseRenderIfIdle(); onSendOrExport() }
            }
            sendEnabled -> btnSend.setOnClickListener { releaseRenderIfIdle(); onSend() }
            exportEnabled -> {
                btnSend.setImageResource(R.drawable.ic_download)
                btnSend.contentDescription = btnSend.context.getString(R.string.cd_calendar_export_page)
                btnSend.setOnClickListener { releaseRenderIfIdle(); onExport() }
            }
        }
        btnSend.visibility = if (sendEnabled || exportEnabled) View.VISIBLE else View.GONE
        // Always visible, unlike Send and the pad: it needs nothing behind us and opens nothing
        // outside this APK.
        btnEvents.setOnClickListener { releaseRenderIfIdle(); onEvents() }
        TooltipCompat.setTooltipText(btnSend, btnSend.contentDescription)   // it may have changed above
        btnScratchPad.visibility = if (scratchPadAvailable) View.VISIBLE else View.GONE
        btnScratchPad.setOnClickListener { releaseRenderIfIdle(); onScratchPad() }
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

    /** Arm [tool] from the host side and sync the buttons — what the eraser sub-bar's pick lands on. */
    fun arm(tool: Tool) = tools.arm(tool)

    /** Wear [ink] on the pen button (arc 49 / P4). Unchanged is silent. */
    fun reportPenShade(ink: Int) = penGlyph.report(ink)

    /** The period's title, presented only once the pen is idle (the frame-silence rule). */
    fun setTitle(text: String) {
        whenPenIdle { title.text = text }
    }

    private fun whenPenIdle(action: () -> Unit) = PenIdle.whenIdle(paper, title, action)

    /** The [PaperView.releaseRender] contract: pen-gated, or a tap inside the pen-up tail can cost
     *  a live stroke. While the pen is active nobody is looking at a pressed state anyway. */
    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        /** The one pen width, in px — the notebook's, so the two surfaces write identically. */
        const val PEN_WIDTH_PX = 3f

        /** The one eraser hit radius, in px — g-paper's default, and the notebook's. */
        const val ERASER_RADIUS_PX = 15f
    }
}
