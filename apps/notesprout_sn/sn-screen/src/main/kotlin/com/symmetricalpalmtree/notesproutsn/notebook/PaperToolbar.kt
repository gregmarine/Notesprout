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
 * **The pen may have two kinds too** since arc 44 / T3 — the sketch face's graphite pencil and its
 * gel pen ([btnAltPen]). They are **both [Tool.PEN]** to g-paper: nothing about the tool differs,
 * only the style, width and colour the engine is armed with, and those belong to the screen, which
 * is why this bar asks it ([altPenArmed]) rather than deciding. So a pencil↔pen tap is an *actual*
 * change with no tool change in it — [onToolTapped] fires, the screen applies the kind
 * ([onPenKindPicked]) and [sync] repaints — and a tap on the already-armed pen of **either kind**
 * is [onPenReTap] with that kind, the eraser's re-tap rule in every particular (see [select]). Every parameter is
 * defaulted and last, so a screen with one pen compiles and behaves exactly as it did.
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
    /** The **second kind** of [Tool.PEN] (arc 44 / T3 — the sketch face's gel pen beside its
     *  pencil), or null on every screen with one pen. Both buttons arm the same tool; what differs
     *  is what the screen arms the engine *with*. */
    private val btnAltPen: ImageButton? = null,
    /** Which kind the screen currently has armed — **the screen's truth, read at every [sync] and
     *  never cached here**: the engine cannot be asked (both kinds are [Tool.PEN]) and a copy of
     *  the answer would be a second place for it to be wrong. False on a screen with one pen. */
    private val altPenArmed: () -> Boolean = { false },
    /** Apply the pen kind a tap chose, **before the tool is armed** — the order matters on an EPD
     *  panel, where the firmware pen is re-armed from the colour and width the screen sets, and a
     *  tool armed first would take the first stroke with the kind that is on its way out. */
    private val onPenKindPicked: (alt: Boolean) -> Unit = {},
    /** A tap on the **already-armed** pen of either kind (arc 44 / T3; the alt kind since arc 46)
     *  — the sketch face opens its shade panel under the button, for that kind. [onEraserReTap]'s
     *  rule exactly, including that [onToolTapped] does **not** fire with it (see [select]). */
    private val onPenReTap: (alt: Boolean) -> Unit = {},
    /** The stylus smudge (arc 50 — the sketch face's Smudge, [Tool.SMUDGE]), or null on every
     *  screen without one. A plain tool with no kinds and no sub-bar: a tap arms it, a re-tap is
     *  nothing. Defaulted and last, so every existing caller compiles unchanged. */
    private val btnSmudge: ImageButton? = null,
) {
    init {
        listOfNotNull(btnBack, btnPen, btnEraser, btnLasso, btnAltPen, btnSmudge).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        btnBack.setOnClickListener { releaseRenderIfIdle(); onBack() }
        btnPen.setOnClickListener { selectPen(alt = false) }
        btnAltPen?.setOnClickListener { selectPen(alt = true) }
        btnEraser.setOnClickListener { select(Tool.ERASER) }
        btnLasso?.setOnClickListener { select(Tool.LASSO) }
        btnSmudge?.setOnClickListener { select(Tool.SMUDGE) }
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
     * Arm one of the pen's two **kinds** (arc 44 / T3) — [select]'s body for a tool that has more
     * than one, and the reason it could not simply be [select] with an argument: the thing being
     * changed is not always the tool.
     *
     * **A kind switch under an armed pen is an actual change.** Going pencil → gel pen while
     * [Tool.PEN] is already armed leaves `paper.tool` exactly where it was, and [select]'s
     * "already armed, nothing to do" would make the tap do nothing at all — so the test is against
     * the *kind*, not the tool, and [onToolTapped] fires for it: the pencil's own options bar
     * belongs to the kind that is leaving and has to come down with it.
     *
     * **A re-tap is the same shape the eraser's is**, for the same measured reason (the notebook's
     * O2 finding, restated in [select]): [onPenReTap] fires and [onToolTapped] does **not**, because
     * [onToolTapped] is what takes the sub-bar down — firing it here would hide the bar a moment
     * before the re-tap asked whether it was showing, and the toggle would reopen what it meant to
     * close, every time. A re-tap on the armed **alt** pen is honestly nothing: it has no bar.
     *
     * The kind is applied **before** the tool ([onPenKindPicked]) and [sync] runs last, as always.
     */
    private fun selectPen(alt: Boolean) {
        releaseRenderIfIdle()
        val armed = paper.tool == Tool.PEN
        if (armed && altPenArmed() == alt) {
            onPenReTap(alt)
            return
        }
        onToolTapped()
        onPenKindPicked(alt)
        if (!armed) paper.tool = Tool.PEN
        sync(Tool.PEN)
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
        // Arc 44 / T3: under two kinds the pen buttons follow the KIND as well as the tool, and the
        // rule is [CollapsedTools.penButtonSelected]'s — one rule for this bar and the mini toolbar,
        // never two spellings of it. With one pen ([altPenArmed] false, no [btnAltPen]) it answers
        // `tool == PEN`, which is what this line has always said.
        val alt = altPenArmed()
        btnPen.isSelected = CollapsedTools.penButtonSelected(tool, alt, isAltButton = false)
        btnAltPen?.isSelected = CollapsedTools.penButtonSelected(tool, alt, isAltButton = true)
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
        btnSmudge?.isSelected = tool == Tool.SMUDGE
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
