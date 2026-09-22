package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * The collapsed chrome's rules (arc 36 / C1), kept apart from the views so they can be tested:
 * which glyph the corner button wears for a tool, which mini-toolbar buttons read as armed, and
 * when a contact outside the rows takes them down.
 *
 * There is no dependency on Android views here on purpose — the decisions are the part worth
 * testing, and the view work ([CollapsedChrome]) is the part that cannot be. The drawable ids are
 * plain generated constants, so a JVM test can pin them.
 */
object CollapsedTools {

    /** The fixed order of the mini toolbar's tool buttons (decision 2 / 3): the two erasers are
     *  two buttons, so the lasso eraser is one tap away while collapsed. */
    val ORDER: List<Tool> = listOf(Tool.PEN, Tool.ERASER, Tool.LASSO_ERASER, Tool.LASSO)

    /**
     * The corner button's glyph for [tool]. The lasso wears the clipboard mark exactly as the
     * bar's button does while [clipboardLoaded] (arc 8's one standing hint that a pen tap on bare
     * paper will paste). [Tool.NONE] — a surface that captures nothing — wears the pen: the
     * button names what a tap will bring back, and the pen is what every screen arms first.
     *
     * The pen wears Tabler's `ballpen` (`ic_pen`, the user's call of 2026-09-22 — the pencil glyph
     * is for a true pencil, so it moved to `ic_pencil`, the sketch face's Pencil). The sketch face's
     * two pen **kinds** (arc 44 / T3, both `Tool.PEN`) never wear this resource: each paints its own
     * shade-filled glyph ([CollapsedChrome.PenKinds.primaryIcon] / `altIcon`), and the corner
     * button wears the armed kind's.
     */
    fun iconFor(tool: Tool, clipboardLoaded: Boolean = false): Int = when (tool) {
        Tool.PEN -> R.drawable.ic_pen
        Tool.NONE -> R.drawable.ic_pen
        Tool.ERASER -> R.drawable.ic_eraser
        Tool.LASSO_ERASER -> R.drawable.ic_lasso_eraser
        Tool.LASSO -> if (clipboardLoaded) R.drawable.ic_lasso_clipboard else R.drawable.ic_lasso
    }

    /**
     * Which of the PEN slot's **two kind buttons** reads as armed (arc 44 / T3) — the pencil's and
     * the gel pen's on the sketch face, on the top bar ([PaperToolbar.sync]) and on the mini
     * toolbar ([CollapsedChrome.sync]) alike.
     *
     * It lives here, as one rule, because both bars ask it and two spellings of "is this the armed
     * pen?" would be two things to keep in step — the module's standing answer to the
     * `RattaNotebookView` sibling-copy trap, in miniature. Both buttons are only ever lit under
     * [Tool.PEN], and then exactly one of them: [altPenArmed] says which kind the screen has armed,
     * [isAltButton] which kind this button offers, and they must agree.
     *
     * A screen with **one** pen passes the defaults (`altPenArmed = false`, `isAltButton = false`)
     * and gets `tool == PEN` back, which is the rule it has always had.
     */
    fun penButtonSelected(tool: Tool, altPenArmed: Boolean, isAltButton: Boolean): Boolean =
        tool == Tool.PEN && altPenArmed == isAltButton

    /**
     * A small overflow is not an overflow (the user's calls on the sticky editor and the pad,
     * 2026-09-11): a `…` that opens a row of one or two buttons is two taps for one, so up to
     * [INLINE_MAX] entries sit on the mini toolbar itself and there is no `…` — the sticky editor's
     * Back, the pad's Back · Send. Three or more (the notebook's and the calendar's doors) go
     * behind it: the mini toolbar stays six buttons at most, which is what fits beside the corner
     * button on the Nomad's width.
     */
    fun overflowInline(count: Int): Boolean = count in 1..INLINE_MAX

    const val INLINE_MAX = 2

    /** The one mini-toolbar button that reads as armed under [tool]; none under [Tool.NONE]. */
    fun selectedFor(tool: Tool): Tool? = tool.takeIf { it in ORDER }

    /**
     * Whether a contact at some point takes the rows down. Nothing showing → nothing to do; on
     * the collapsed chrome itself ([onChrome] — the corner button, whose own click toggles and
     * whose dismissal here would close-then-reopen, the lasso popup's trap, or the rows) → no;
     * inside a sub-bar the screen hung off the rows ([keep]) → no; anywhere else — a bare pen tap,
     * a stroke, a finger gesture, a bar button — yes.
     */
    fun outsideTapDismisses(showing: Boolean, onChrome: Boolean, keep: Boolean): Boolean =
        showing && !onChrome && !keep
}
