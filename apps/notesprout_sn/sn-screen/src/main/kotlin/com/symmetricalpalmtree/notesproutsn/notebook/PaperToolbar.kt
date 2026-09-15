package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * Back plus the tool buttons of a paper-hosting screen (arc 11 / J1) — three, or two where the
 * screen has no lasso ([btnLasso] is nullable since arc 43 / K2) — **binding-free**: it
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
 * **The eraser has two kinds** since arc 29 / LE2: a second tap on the armed eraser is not the P1
 * no-op any more but [onEraserReTap], which opens the shared [EraserBar] — Point · Lasso. Nothing
 * is remembered: a plain tap from another tool always arms [Tool.ERASER], and the lasso eraser is
 * reached only through that re-tap.
 *
 * Selected = the bordered `state_selected` look of `bg_toolbar_button`. No colour anywhere.
 */
class PaperToolbar(
    private val bar: View,
    private val btnBack: ImageButton,
    private val btnPen: ImageButton,
    private val btnEraser: ImageButton,
    /** The lasso button, or **null** on a screen that has none (arc 43 / K2 — the sketch surface's
     *  bar is Pencil · Eraser: a raster page has nothing to select, so a lasso there would arm a
     *  tool the surface does not answer). Every existing caller passes one and compiles unchanged. */
    private val btnLasso: ImageButton?,
    private val paper: PaperView,
    private val onBack: () -> Unit,
    /** A tap on the **already-armed** eraser (arc 29 / LE2): the screen opens the eraser sub-bar
     *  ([EraserBar]) — Point · Lasso. Defaulted so a screen that has not wired one yet keeps the
     *  P1 no-op and keeps compiling unchanged. */
    private val onEraserReTap: () -> Unit = {},
    /** Any **actual** tool change (arc 29 / LE2) — the screen closes floating chrome that belonged
     *  to the old tool. It deliberately does not fire on a re-tap: see [select]. */
    private val onToolTapped: () -> Unit = {},
    /** Fires after every [sync] (arc 36) — the one funnel every tool change passes through (a
     *  bar tap, [arm], every by-hand sync, `onToolChanged`), so anything else that shows the armed
     *  tool (the collapsed chrome's corner button) repaints from here and can never be left out. */
    private val onSynced: () -> Unit = {},
) {
    init {
        listOfNotNull(btnBack, btnPen, btnEraser, btnLasso).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnBack.setOnClickListener { releaseRenderIfIdle(); onBack() }
        btnPen.setOnClickListener { select(Tool.PEN) }
        btnEraser.setOnClickListener { select(Tool.ERASER) }
        btnLasso?.setOnClickListener { select(Tool.LASSO) }
        sync(paper.tool)
    }

    /**
     * Tapping a tool arms it. A second tap on the armed one still changes nothing about the tool —
     * a button that disarmed itself would leave the pen doing something the bar isn't showing —
     * but on the **eraser** it now opens the eraser sub-bar ([onEraserReTap], arc 29 / LE2).
     *
     * The eraser button is armed under **both** erasers, so a tap on it while [Tool.LASSO_ERASER]
     * is armed is a re-tap too — it must not silently drop the user back to the point eraser.
     * The sub-bar is the only way to either one once it is open.
     *
     * [onToolTapped] fires **only on an actual tool change**, and that ordering is load-bearing: it
     * is what takes the sub-bar down when another tool is armed, so firing it on the re-tap too
     * would hide the bar a moment before [onEraserReTap] asked whether it was showing — and the
     * toggle would reopen what it was meant to close, every time (the notebook's O2 finding).
     */
    private fun select(tool: Tool) {
        releaseRenderIfIdle()
        if (tool == Tool.ERASER && (paper.tool == Tool.ERASER || paper.tool == Tool.LASSO_ERASER)) {
            onEraserReTap()
            return
        }
        if (paper.tool == tool) return
        onToolTapped()
        paper.tool = tool
        sync(tool)
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
    }

    /** Which eraser glyph the button currently wears — the layout's `ic_eraser` at construction. */
    private var eraserShowsLasso = false

    /**
     * Make the buttons honest about [tool]. Called from `PaperListener.onToolChanged` — the
     * component arms and restores tools on its own (smart lasso), so this runs for changes the
     * screen never initiated.
     */
    fun sync(tool: Tool) {
        btnPen.isSelected = tool == Tool.PEN
        // The eraser button is armed under both erasers, and its icon says which (arc 29 / LE2 —
        // the notebook's `showClipboardLoaded` precedent: a standing state of the surface belongs
        // on the button, not in a toast that is gone before the next stroke).
        btnEraser.isSelected = tool == Tool.ERASER || tool == Tool.LASSO_ERASER
        // Swapped only on a change of kind: every `onToolChanged` lands here, and re-setting the
        // same drawable would invalidate the button for nothing (frame silence).
        val lassoKind = tool == Tool.LASSO_ERASER
        if (lassoKind != eraserShowsLasso) {
            eraserShowsLasso = lassoKind
            btnEraser.setImageResource(if (lassoKind) R.drawable.ic_lasso_eraser else R.drawable.ic_eraser)
        }
        btnLasso?.isSelected = tool == Tool.LASSO
        onSynced()
    }

    /** The bar's rect in window coordinates (for `setExclusionRects`), or null before layout. */
    fun rectInWindow(): Rect? = rectOf(bar)

    private fun releaseRenderIfIdle() = PenIdle.releaseRenderIfIdle(paper)

    companion object {
        /**
         * A shown, laid-out view's rect in window coordinates; null before layout has run **or
         * while the view is not [View.VISIBLE]**. The visibility check is load-bearing (arc 33):
         * a `GONE` view keeps its last measured width and height, so a size-only test would keep a
         * hidden bar excluding ink and swallowing gestures exactly where it used to be.
         */
        fun rectOf(v: View): Rect? {
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return null
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
    }
}
