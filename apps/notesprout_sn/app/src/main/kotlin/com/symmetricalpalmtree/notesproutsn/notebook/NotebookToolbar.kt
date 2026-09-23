package com.symmetricalpalmtree.notesproutsn.notebook

import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.InkColorCodec
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityNotebookBinding

/**
 * The notebook's chrome: back, and the three tool buttons — the eraser one standing for **two**
 * erasers since arc 29 / LE2 (Point and Lasso, picked from the sub-bar its own re-tap opens; the
 * button's icon says which is armed). It owns every tool decision — the activity hands it the
 * binding and the surface and never touches `paper.penWidth` itself.
 *
 * **The tools are fixed (P1) — but the pen has a shade since arc 49 / P4.** Pen is PEN ·
 * [PEN_WIDTH_PX] in one of the family's sixteen greys; the eraser is [ERASER_RADIUS_PX]; there are
 * no width or style panels. Handwriting is the app, and a bar that only ever arms a tool is one less
 * thing between the pen and the paper — the R3 panels bought five widths, five styles and sixteen
 * greys at the cost of a two-tap gesture on every button and a chrome surface that could sit open
 * over the page. **The user reversed that for the shade alone** (arc 49 "Panel", decision 4) once
 * the page went direct to the Supernote panel and a grey pen could preview as grey: a second tap on
 * the armed pen opens `:sn-screen`'s `PaletteBar` ([onPenReTap]), the pen button wears the armed
 * tone as a fill inside the ballpen glyph ([PenShadeGlyph] — the colour rule's one opening, greys
 * being ink), and the level is one for every paper screen on the device, in the host's
 * `PenShadePrefs` (decision 6) — never in the `.soil`. Existing strokes still render exactly as
 * they were authored (width, style and grey travel in the row), so nothing had to migrate.
 *
 * Two rules shape what is left:
 *  - **Release the render first — but pen-gated.** Every handler calls [releaseRenderIfIdle] before
 *    it does anything else: while the EPD writing overlay is armed the bar will not show a new
 *    pressed state and the tap reads as broken. The gate is the [PaperView.releaseRender] API
 *    contract — an ungated release inside the pen-active window can cost a live stroke.
 *  - **[sync] is the truth, not our taps.** g-paper changes tools by itself (smart lasso arms
 *    LASSO and restores PEN when the selection goes), so button state is driven from
 *    `PaperListener.onToolChanged` — never assumed from the tap that started it.
 *
 * Selected = the bordered `state_selected` look of `bg_toolbar_button`. No colour anywhere but the
 * pen glyph's fill, which is ink.
 */
class NotebookToolbar(
    private val binding: ActivityNotebookBinding,
    private val paper: PaperView,
    private val onBack: () -> Unit,
    /** A tap on the **already-armed** lasso (arc 8) — P1's no-op grew a meaning: the screen opens
     *  the clipboard popup, or keeps the no-op when there is nothing on the clipboard. */
    private val onLassoReTap: () -> Unit = {},
    /** A tap on the **already-armed** eraser (arc 29 / LE2) — the same grown no-op the lasso got:
     *  the screen opens the eraser sub-bar (Point · Lasso). The eraser button is armed under
     *  **both** erasers, so a tap while `LASSO_ERASER` is armed is a re-tap too. */
    private val onEraserReTap: () -> Unit = {},
    /** Any tool tap at all — the screen closes floating chrome that belonged to the old tool. */
    private val onToolTapped: () -> Unit = {},
    /** Fires after every [sync] (arc 36) — the one funnel every tool change passes through (a
     *  bar tap, [arm], every by-hand sync, `onToolChanged`), so the collapsed chrome's corner
     *  button repaints from here and can never be left out of a by-hand arm. */
    private val onSynced: () -> Unit = {},
    /** A tap on the **already-armed** pen (arc 49 / P4) — the eraser's grown no-op for the pen:
     *  the screen toggles the shade panel. Only the pen's re-tap; nothing else about it changed. */
    private val onPenReTap: () -> Unit = {},
) {

    /** The pen button wearing its shade (arc 49 / P4) — black until the screen applies the
     *  device's level ([applyShade]). */
    private val penGlyph = PenShadeGlyph(binding.btnPen, InkColorCodec.BLACK)

    init {
        // Arm the surface before anything is drawn or shown. These are the tools, for good — the
        // colour black until the screen applies the device's shade (arc 49 / P4), the same first
        // answer as before.
        paper.tool = Tool.PEN
        paper.penColor = InkColorCodec.BLACK
        paper.penWidth = PEN_WIDTH_PX
        paper.penStyle = StrokeStyle.PEN
        paper.eraserRadius = ERASER_RADIUS_PX

        with(binding) {
            listOf(btnBack, btnPen, btnEraser, btnLasso).forEach {
                TooltipCompat.setTooltipText(it, it.contentDescription)
            }
            btnBack.setOnClickListener {
                releaseRenderIfIdle()
                onBack()
            }
            btnPen.setOnClickListener { onToolTap(Tool.PEN) }
            btnEraser.setOnClickListener { onToolTap(Tool.ERASER) }
            btnLasso.setOnClickListener { onToolTap(Tool.LASSO) }
        }

        sync(paper.tool)
    }

    /**
     * Tapping a tool arms it. A second tap on the armed one still changes nothing about the tool —
     * a button that disarmed itself would leave the pen doing something the bar isn't showing — but
     * on the **lasso** it opens the clipboard popup ([onLassoReTap], arc 8) and on the **eraser**
     * it opens the eraser sub-bar ([onEraserReTap], arc 29 / LE2). Only the pen keeps the P1 no-op.
     *
     * The eraser is "armed" under [Tool.ERASER] **and** [Tool.LASSO_ERASER] — the button is
     * selected under both — so a tap on it while the lasso eraser is armed is a re-tap as well,
     * never a silent drop back to the point eraser. Since arc 49 / P4 the **pen's** re-tap opens
     * the shade panel ([onPenReTap]) — so no button keeps the P1 no-op any more.
     *
     * [onToolTapped] fires **only on an actual tool change**, and that ordering is load-bearing: it
     * is what takes the popup down when another tool is armed, so firing it on the re-tap too would
     * hide the popup a moment before [onLassoReTap] asked whether it was showing — and the toggle
     * would reopen what it was meant to close, every time (O2 review). The eraser sub-bar's toggle
     * is the same shape and depends on the same ordering.
     */
    private fun onToolTap(tool: Tool) {
        releaseRenderIfIdle()
        if (tool == Tool.ERASER && (paper.tool == Tool.ERASER || paper.tool == Tool.LASSO_ERASER)) {
            onEraserReTap()
            return
        }
        if (paper.tool == tool) {
            if (tool == Tool.LASSO) onLassoReTap()
            if (tool == Tool.PEN) onPenReTap()
            return
        }
        onToolTapped()
        paper.tool = tool
        sync(tool)
        Slog.d(TAG) { "armed $tool" }
    }

    /**
     * Arm [tool] from the **host** side and make the buttons say so (arc 29 / LE2) — what the
     * eraser sub-bar's pick lands on. It exists because a tool assignment the host makes is never
     * echoed back as `PaperListener.onToolChanged` (it is not component-initiated), so [sync] has
     * to be called by hand or the bar would keep showing the tool that is no longer armed.
     */
    fun arm(tool: Tool) {
        releaseRenderIfIdle()
        if (paper.tool != tool) paper.tool = tool
        sync(tool)
        Slog.d(TAG) { "armed $tool" }
    }

    /**
     * Arm the pen with [ink] (arc 49 / P4) — the one door the shade comes through: the screen's
     * read of the device's level at open and at every resume, and a pick from the panel. The
     * engine is re-armed from its colour (the firmware pen reads it at the next pen-down), and the
     * pen button wears it. Unchanged is silent, per the glyph's own rule.
     */
    fun applyShade(ink: Int) {
        paper.penColor = ink
        penGlyph.report(ink)
    }

    /** The tone the pen button is wearing — the token the collapsed chrome compares. */
    val penInk: Int get() = penGlyph.ink

    /**
     * Swap the lasso button's icon to the lasso-with-a-plus while [loaded] objects are on the
     * clipboard (arc 8 — og's own icon). This is the **only** standing hint that a pen tap on bare paper will paste
     * — tap-to-place changes nothing else about the surface — so it is a state of the button, not a
     * transient toast. Idempotent; the screen calls it whenever the clipboard's kind can have moved.
     */
    fun showClipboardLoaded(loaded: Boolean) {
        binding.btnLasso.setImageResource(
            if (loaded) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
        )
    }

    /** Which eraser glyph the button currently wears — the layout's `ic_eraser` at construction. */
    private var eraserShowsLasso = false

    /**
     * Make the buttons honest about [tool]. Called from `PaperListener.onToolChanged` — the
     * component arms and restores tools on its own (smart lasso), so this runs for changes we
     * never initiated.
     */
    fun sync(tool: Tool) = with(binding) {
        btnPen.isSelected = tool == Tool.PEN
        // The eraser button stands for both erasers, and its icon says which one is armed (arc 29 /
        // LE2 — [showClipboardLoaded]'s precedent: a standing state of the surface belongs on the
        // button that owns it).
        btnEraser.isSelected = tool == Tool.ERASER || tool == Tool.LASSO_ERASER
        // Swapped only on a change of kind: every `onToolChanged` lands here, and re-setting the
        // same drawable would invalidate the button for nothing (frame silence).
        val lassoKind = tool == Tool.LASSO_ERASER
        if (lassoKind != eraserShowsLasso) {
            eraserShowsLasso = lassoKind
            btnEraser.setImageResource(if (lassoKind) R.drawable.ic_lasso_eraser else R.drawable.ic_eraser)
        }
        btnLasso.isSelected = tool == Tool.LASSO
        onSynced()
    }

    /** [PenIdle.releaseRenderIfIdle] — [PaperView.releaseRender]'s own pen-gated contract. */
    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        private const val TAG = "NotebookToolbar"

        /** The one pen width, in px (Paper-v0 parity, and og Notesprout's stored default). */
        const val PEN_WIDTH_PX = 3f

        /** The one eraser hit radius, in px — g-paper's own default, and Paper v0's. */
        const val ERASER_RADIUS_PX = 15f
    }
}
