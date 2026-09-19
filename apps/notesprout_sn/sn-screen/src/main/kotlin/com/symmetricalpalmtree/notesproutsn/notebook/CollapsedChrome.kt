package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * The collapsed chrome (arc 36) — what a paper screen shows while its bars are hidden: one
 * floating **corner button** at the top-right wearing the armed tool's glyph, and, hung under it
 * on a tap, a **mini toolbar** (the screen's [tools], [CollapsedTools.ORDER] by default, the armed one bordered, then
 * the screen's [commands], then `…`) with an **overflow row** under the `…` holding Back and the
 * screen's doors and actions — unless the overflow would hold only one or two buttons, which then
 * sit on the mini toolbar itself with no `…` (the sticky editor's Back, the pad's Back · Send).
 *
 * One copy for the four screens, here in `:sn-screen`, for the reason [EraserBar] and
 * [ChromeToggle] live here: a second copy would be the `RattaNotebookView` sibling-copy trap one
 * file at a time. The screen says only what differs — its commands, its overflow entries, and
 * when the button may open — and owns the rest of the chrome plumbing exactly as it does for its
 * other floating bars: the corner button and both rows go into its exclusion rects and its
 * `overChrome` test ([rects] / [contains]), its outside-contact dismissal calls
 * [dismissOnContact], and every place its other floating bars go down calls [dismiss].
 *
 * **The corner button is honest by construction.** Nothing here caches the armed tool: every
 * repaint ([sync]) reads `paper.tool`, so a caller only ever says "repaint", never "repaint as
 * X" — a stale copy has no way to exist. The one call site is the bar's own `sync` funnel
 * (`PaperToolbar` / `NotebookToolbar`'s `onSynced`), which every tool change already passes
 * through: a bar tap, `arm`, every by-hand sync, and `onToolChanged`. The glyph is
 * [CollapsedTools.iconFor], swapped only on a change (frame silence). The button itself is
 * `GONE` while the bars show — [ChromeToggle]'s `whileHidden` list flips it with the bars.
 *
 * **Entries mirror bar buttons** ([Entry.mirrors]). At every open of its row an entry copies its
 * bar button's visibility (a door absent from the bar is absent here), its selected look (the
 * calendar's view latches) and, only when it actually differs, its glyph (the calendar's
 * Send-or-Export, decided once at that bar's construction). A tap on a mirrored entry with no
 * [Entry.onTap] closes the rows and performs the bar button's own click — one handler, never a
 * copy of it. An entry with [Entry.onTap] is handed its own button as an anchor and owns
 * dismissal: the notebook's Insert hangs its sub-bar under the mini toolbar's button and leaves the
 * row up beneath it.
 *
 * **The pen may be two kinds** since arc 44 / T3 ([PenKinds]) — the sketch face's graphite pencil
 * and its gel pen, which are both [Tool.PEN] to the engine and so cannot be two entries of [tools].
 * The second button is built right after the primary one, the glyph is what says which is armed
 * (here and on the corner button), and a pick of the already-armed primary kind is a re-tap the
 * screen answers by hanging its own bar under this row's button.
 *
 * **…and the primary kind's glyph may be the screen's own** ([PenKinds.primaryIcon]): the sketch
 * face paints its pencil with the armed shade in its body, so the row and the corner button report
 * what a stroke will look like. Nothing here knows what the picture means — it is asked for a
 * [PenIcon] at every repaint and swaps only when the token changes (frame silence), exactly as the
 * resource glyphs do.
 *
 * **Sub-bars hung off the rows go with them.** [onClose] fires before either row is taken down by
 * anything — the corner button's re-tap, the `…` re-tap, a mirrored entry, the screen's own
 * [dismiss] — so the notebook's Insert bar and tags popup never outlive the row they hang under.
 * It fires raw, before the one exclusion push ([onChanged]), so a close is one binder call.
 *
 * **Render release**: opening a row is one chrome frame at a deliberate tap with the pen still
 * hovering — an ungated `releaseRender()`, the Insert bar's rule; a tool pick releases through
 * the screen's own `toolbar.arm` ([onArmed]), pen-gated, exactly once. Nothing here is
 * `whenPenIdle`-gated.
 */
class CollapsedChrome(
    root: ViewGroup,
    /** The corner button — declared in the screen's layout at `top|end`, `GONE` until collapsed. */
    private val knob: ImageButton,
    miniBar: LinearLayout,
    overflowBar: LinearLayout,
    private val paper: PaperView,
    /** The free band's bottom edge in root coordinates ([ChromeBand]); null before layout. */
    bandBottom: () -> Int?,
    /** Whether a tap on the corner button may open the mini toolbar — the screen's `opened &&
     *  !closing` (and the notebook's `canvasShown`). */
    private val canOpen: () -> Boolean,
    /** Buttons after the tools on the mini toolbar (the notebook's Insert). */
    commands: List<Entry> = emptyList(),
    /** The overflow row's entries, in order; empty = no `…` button at all. */
    overflow: List<Entry> = emptyList(),
    /** Fires before a row opens — the screen takes down its other floating popups. */
    private val onOpen: () -> Unit = {},
    /** Fires before a row closes, by any path — the screen takes down the sub-bars it hung off
     *  the rows, **without** pushing exclusions: [onChanged] follows. */
    private val onClose: () -> Unit = {},
    /** Fires after a pick — the screen arms [Tool] on its own toolbar (`toolbar.arm`), which
     *  releases the render pen-gated, syncs the bar and, through the bar's `onSynced`, this. */
    private val onArmed: (Tool) -> Unit,
    /** Fires after every open / close — the screen re-pushes its exclusion rects. */
    private val onChanged: () -> Unit,
    /**
     * Which tool buttons the mini toolbar carries, in order (arc 43 / K2). The default is
     * [CollapsedTools.ORDER] — the four every paper screen up to now has had — and it is the whole
     * of the parameter's reason: a screen with **two** tools (the sketch surface's pencil and its
     * rubbing eraser; there is no lasso on a raster page and nothing to select) would otherwise
     * show two buttons that arm tools its surface does not have. Last in the list and defaulted, so
     * every existing caller compiles unchanged.
     */
    tools: List<Tool> = CollapsedTools.ORDER,
    /**
     * The PEN slot's **two kinds** (arc 44 / T3), or null on every screen with one pen. It is one
     * parameter rather than "a second tool" because the two are not two tools: the sketch face's
     * pencil and its gel pen are both [Tool.PEN], so they cannot be two entries of a `List<Tool>`
     * (the buttons are keyed by tool, and a second `PEN` would simply replace the first). The alt
     * button is built immediately after the primary one, which makes the sketch face's row read
     * Pencil · Pen · Eraser — the top bar's own order.
     */
    private val penKinds: PenKinds? = null,
) {

    /**
     * What the mini toolbar needs to offer **two kinds of one tool** (arc 44 / T3): the second
     * kind's glyph and hint, which kind is armed, how to arm one, and what a pick of the
     * already-armed primary kind does.
     *
     * [altArmed] is read at every repaint and never cached here, for the reason nothing else here
     * is cached either — the screen owns the answer and a copy of it is a copy that can be stale.
     */
    class PenKinds(
        /** What the **primary** kind's button is called. It is the screen's word, not this
         *  module's: `R.string.tool_pen` says "Pen", and on the sketch face the primary kind is the
         *  *pencil*. */
        val primaryHint: String,
        val altIconRes: Int,
        val altHint: String,
        /** Whether the ALT kind is the armed one. Read, never stored. */
        val altArmed: () -> Boolean,
        /** Arm a kind — the screen applies it and then arms [Tool.PEN] on its own toolbar
         *  (`toolbar.arm`), which does the one pen-gated render release and the syncs. */
        val onPick: (alt: Boolean) -> Unit,
        /**
         * A pick of the **already-armed primary** kind, handed this row's own button as an anchor —
         * [Entry.onTap]'s contract, and the notebook Insert bar's precedent: the caller hangs its
         * sub-bar under the button the person actually tapped and **the rows stay up beneath it**,
         * which also means the caller owns that bar's dismissal (`onClose` brings it down with the
         * rows, and the screen's outside-contact rule keeps it alive under a contact of its own).
         * Absent, a re-pick simply arms again and closes the rows like any other tool tap.
         */
        val onPrimaryReTap: ((anchor: View) -> Unit)? = null,
        /**
         * The **primary** kind's glyph, painted by the screen (arc 44 / T3), or null to wear the
         * plain resource one ([CollapsedTools.iconFor]) as every screen did before.
         *
         * It exists for one thing: the sketch face's Pencil reports the armed shade by **filling
         * the pencil's body with it** under an outline that stays solid black. That is the colour
         * rule's one standing opening said in its fill form — the root `CLAUDE.md`'s "the pen
         * button's icon tinted with the armed ink": greys and colours are **ink**, and a control
         * may carry the armed ink only where the ink itself is what is being chosen or reported.
         * Nothing else on any bar may take a colour, and this row is not a precedent for one.
         *
         * Asked at every repaint and never cached, like [altArmed] and for the same reason — the
         * screen owns the armed shade and a copy of it here is a copy that can be stale.
         */
        val primaryIcon: (() -> PenIcon)? = null,
        /**
         * The **alt** kind's glyph, painted by the screen (arc 46 "Palette"), or null to wear
         * [altIconRes] as every screen did before. [primaryIcon]'s rule and reason exactly: the
         * sketch face's gel pen reports its own shade as a fill under a solid outline, on the row's
         * own button always and on the corner button while the alt kind is the armed one.
         */
        val altIcon: (() -> PenIcon)? = null,
    )

    /**
     * One painted glyph and the number that says which one it is (arc 44 / T3).
     *
     * The two halves are separate because they are wanted at different moments: [token] is read at
     * **every** repaint to decide whether anything changed (one integer compare, no allocation —
     * the frame-silence rule this class keeps for its resource glyphs), and [newDrawable] is called
     * only when it did. It mints a fresh instance per call on purpose: the corner button and the
     * row's own button are two views, and one `Drawable` in both would have them fighting over its
     * bounds and its callback.
     *
     * Two glyphs that look the same must carry the same token, and two that differ must not — the
     * sketch face passes the ARGB of the shade it is reporting, which is both by construction.
     */
    class PenIcon(val token: Int, val newDrawable: () -> Drawable)

    /**
     * One mini-toolbar command or overflow-row entry. [mirrors] is the bar button it stands for,
     * read at every open of its row (visibility, selected look, glyph). [onTap] receives the
     * entry's own button as an anchor and owns dismissal; absent, a tap dismisses the rows and
     * performs [mirrors]' own click.
     */
    class Entry(
        val iconRes: Int,
        val hint: String,
        val mirrors: View? = null,
        val onTap: ((anchor: View) -> Unit)? = null,
    ) {
        companion object {
            /** An entry standing for [button], its hint the button's own content description —
             *  the row's long press says what the bar's long press says, and a bar whose wording
             *  is decided at runtime (the calendar's out-door) is read, never copied. */
            fun mirroring(iconRes: Int, button: View, onTap: ((anchor: View) -> Unit)? = null): Entry =
                Entry(iconRes, button.contentDescription?.toString().orEmpty(), button, onTap)
        }
    }

    private val mini = AnchoredBar(root, miniBar, knob, bandBottom)
    private val more = AnchoredBar(root, overflowBar, knob, bandBottom)

    private val toolButtons = LinkedHashMap<Tool, AppCompatImageButton>()
    /** The PEN slot's second kind, when the screen offers one — kept apart from [toolButtons]
     *  because it is keyed by nothing: it is the same [Tool.PEN] the primary button arms. */
    private var altPenButton: AppCompatImageButton? = null
    /** Mirrored entries per row, so an open refreshes only the row it is showing. */
    private val miniMirrored = ArrayList<Pair<AppCompatImageButton, Entry>>()
    private val moreMirrored = ArrayList<Pair<AppCompatImageButton, Entry>>()
    private var btnMore: AppCompatImageButton? = null

    private var knobIcon = 0
    /** The painted glyphs' tokens, last swapped in — null while the button wears a plain resource
     *  one, which is every screen but the sketch face and every tool but the primary pen. */
    private var knobToken: Int? = null
    private var primaryPenToken: Int? = null
    private var altPenToken: Int? = null
    private var clipboardLoaded = false

    val isShowing: Boolean get() = mini.isShowing

    init {
        val ctx = root.context
        knob.contentDescription = ctx.getString(R.string.collapsed_tools)
        TooltipCompat.setTooltipText(knob, knob.contentDescription)
        knob.setOnClickListener { if (isShowing) dismiss() else open() }

        // The hints are this module's: the four screens say the same four words.
        val hints = mapOf(
            Tool.PEN to ctx.getString(R.string.tool_pen),
            Tool.ERASER to ctx.getString(R.string.eraser_point),
            Tool.LASSO_ERASER to ctx.getString(R.string.eraser_lasso),
            Tool.LASSO to ctx.getString(R.string.tool_lasso),
        )
        tools.forEach { tool ->
            val kinds = penKinds.takeIf { tool == Tool.PEN }
            // The button is its own click's anchor (the `add` helper's pattern): a re-pick of the
            // armed primary pen hangs the screen's sub-bar under the button that was tapped, not
            // under a bar button that is `GONE` and keeps stale edges.
            lateinit var button: AppCompatImageButton
            button = mini.addButton(
                CollapsedTools.iconFor(tool),
                kinds?.primaryHint ?: hints.getValue(tool),
            ) { if (kinds != null) pickPen(alt = false, anchor = button) else pick(tool) }
            toolButtons[tool] = button
            // Immediately after the primary one — the sketch face's row reads Pencil · Pen · Eraser.
            if (kinds != null) {
                altPenButton = mini.addButton(kinds.altIconRes, kinds.altHint) { pickPen(alt = true, anchor = null) }
            }
        }
        commands.forEach { add(mini, miniMirrored, it) }
        // A small overflow is not an overflow ([CollapsedTools.overflowInline]): the sticky
        // editor's Back and the pad's Back · Send sit on the mini toolbar itself, no `…`.
        if (CollapsedTools.overflowInline(overflow.size)) {
            overflow.forEach { add(mini, miniMirrored, it) }
        } else if (overflow.isNotEmpty()) {
            btnMore = mini.addButton(R.drawable.ic_dots, ctx.getString(R.string.collapsed_more)) { toggleMore() }
            overflow.forEach { add(more, moreMirrored, it) }
        }
        sync()
    }

    private fun add(bar: AnchoredBar, mirrored: MutableList<Pair<AppCompatImageButton, Entry>>, entry: Entry) {
        lateinit var button: AppCompatImageButton
        button = bar.addButton(entry.iconRes, entry.hint) {
            val onTap = entry.onTap
            if (onTap != null) {
                onTap(button)
            } else {
                dismiss()
                entry.mirrors?.performClick()
            }
        }
        if (entry.mirrors != null) mirrored += button to entry
    }

    /**
     * Copy every mirrored bar button's state for one row — read at its open, never cached.
     * The glyph is copied only when it differs (a constant-state identity check): nothing on any
     * screen swaps a mirrored button's glyph after construction, so in the steady state this is
     * flag compares and no allocation.
     */
    private fun refresh(mirrored: List<Pair<AppCompatImageButton, Entry>>) {
        mirrored.forEach { (button, entry) ->
            val source = entry.mirrors ?: return@forEach
            button.visibility = if (source.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            button.isSelected = source.isSelected
            if (source is ImageView) {
                val state = source.drawable?.constantState ?: return@forEach
                if (button.drawable?.constantState === state) return@forEach
                // A mutated copy: a Drawable carries bounds and a callback, and the bar's button
                // still owns its own.
                button.setImageDrawable(state.newDrawable(button.resources).mutate())
            }
        }
    }

    private fun open() {
        if (!canOpen()) return
        onOpen()
        paper.releaseRender()
        refresh(miniMirrored)
        sync()
        if (mini.show()) {
            Slog.d(TAG) { "mini toolbar open (armed ${paper.tool})" }
            onChanged()
        }
    }

    private fun toggleMore() {
        val button = btnMore ?: return
        if (more.isShowing) {
            hideOverflow()
            return
        }
        onOpen()
        paper.releaseRender()
        refresh(moreMirrored)
        if (more.show(anchor = button)) onChanged()
    }

    /**
     * Arm one of the four. The screen's `toolbar.arm` ([onArmed]) does the assignment (skipped
     * when already armed, so picking the armed one is honestly a no-op on the surface), the one
     * pen-gated render release, the bar's sync and — through the bar's funnel — this one's; then
     * the rows come down with one exclusion push for the whole tap. The rows still close on a
     * re-pick, because a tap on a tool is an answer.
     */
    private fun pick(tool: Tool) {
        onArmed(tool)
        dismiss()
        Slog.d(TAG) { "armed $tool from the mini toolbar" }
    }

    /**
     * Arm one of the pen's two **kinds** from the mini toolbar (arc 44 / T3) — [pick]'s body where
     * the tool is the same either way, and [PaperToolbar.selectPen]'s rule said once more for this
     * row.
     *
     * A pick of the already-armed **primary** kind is the row's own re-tap: the screen is handed
     * this button as an anchor and **the rows stay up**, so its bar hangs under the button that was
     * tapped rather than under a bar button that is `GONE` behind hidden chrome. Everything else —
     * the other kind, a first arming, a re-pick with no re-tap handler — arms and closes the rows,
     * because a tap on a tool is an answer.
     */
    private fun pickPen(alt: Boolean, anchor: AppCompatImageButton?) {
        val kinds = penKinds ?: return
        if (paper.tool == Tool.PEN && kinds.altArmed() == alt) {
            val reTap = kinds.onPrimaryReTap
            if (!alt && reTap != null && anchor != null) {
                reTap(anchor)
                return
            }
        }
        kinds.onPick(alt)
        dismiss()
        Slog.d(TAG) { "armed the ${if (alt) "alt" else "primary"} pen from the mini toolbar" }
    }

    /**
     * Make the corner button and the mini toolbar honest about `paper.tool`. Wired once, into the
     * bar's `onSynced`, so every path a tool can change by repaints it. Idempotent and cheap: the
     * glyph swaps only on a change, `isSelected` is change-checked by the framework.
     */
    fun sync() {
        val armed = paper.tool
        syncKnob(armed)
        syncPrimaryPen()
        syncAltPen()
        // [CollapsedTools.selectedFor] answers against the full order, so on a shortened bar it can
        // name a tool that has no button here — the walk is over the buttons this bar actually
        // built, so that reads as "nothing bordered", which is exactly right.
        val selected = CollapsedTools.selectedFor(armed)
        // Arc 44 / T3: the PEN slot's two buttons follow the KIND as well as the tool, on the one
        // rule the top bar uses ([CollapsedTools.penButtonSelected]). With no second kind
        // `altArmed` is false and the primary button reads `armed == PEN`, as it always did.
        val alt = penKinds?.altArmed() == true
        toolButtons.forEach { (tool, button) ->
            button.isSelected =
                if (tool == Tool.PEN && penKinds != null) CollapsedTools.penButtonSelected(armed, alt, isAltButton = false)
                else tool == selected
        }
        altPenButton?.isSelected = CollapsedTools.penButtonSelected(armed, alt, isAltButton = true)
    }

    /**
     * The lasso wears the clipboard mark while [loaded] objects are on the clipboard (arc 8) —
     * the corner button when the lasso is armed, and the mini toolbar's lasso always. Idempotent.
     */
    fun showClipboardLoaded(loaded: Boolean) {
        if (clipboardLoaded == loaded) return
        clipboardLoaded = loaded
        toolButtons[Tool.LASSO]?.setImageResource(CollapsedTools.iconFor(Tool.LASSO, loaded))
        syncKnob(paper.tool)
    }

    private fun syncKnob(armed: Tool) {
        // Swapped only on a change: every sync lands here, and re-setting the same drawable would
        // invalidate the button for nothing (frame silence). Arc 44 / T3: under two pen kinds the
        // glyph is what says which one is armed — the tool is [Tool.PEN] for both.
        val kinds = penKinds
        val alt = kinds?.altArmed() == true
        // The screen's own painted glyph (arc 44 / T3) wears the corner button exactly when the
        // PRIMARY pen button reads as armed — [CollapsedTools.penButtonSelected]'s rule again
        // rather than a second spelling of "is the pencil what is on the paper?" — and (arc 46)
        // the alt kind's painted glyph when the ALT button reads as armed. Under any other tool
        // the button wears that tool's glyph, untouched.
        val reported = kinds?.primaryIcon
            ?.takeIf { CollapsedTools.penButtonSelected(armed, alt, isAltButton = false) }
            ?.invoke()
            ?: kinds?.altIcon
                ?.takeIf { CollapsedTools.penButtonSelected(armed, alt, isAltButton = true) }
                ?.invoke()
        if (reported != null) {
            // `knobIcon` 0 is "wearing a painted glyph" — no resource id is ever 0, so the pair
            // (0, token) cannot be confused with any resource the button could be showing.
            if (knobIcon == PAINTED && knobToken == reported.token) return
            knobIcon = PAINTED
            knobToken = reported.token
            knob.setImageDrawable(reported.newDrawable())
            return
        }
        val icon = CollapsedTools.iconFor(armed, clipboardLoaded, altPen = alt)
        if (icon == knobIcon) return
        knobIcon = icon
        knobToken = null
        knob.setImageResource(icon)
    }

    /**
     * The row's own primary-pen button wears the screen's painted glyph **always** (arc 44 / T3),
     * armed or not: it is the button that says what a tap will bring back, so a pencil shown in
     * the shade it would draw with is the honest one whatever is on the paper at the moment. A
     * screen that paints nothing ([PenKinds.primaryIcon] null, which is every screen but the
     * sketch face) keeps the resource glyph its button was built with.
     */
    private fun syncPrimaryPen() {
        val icon = penKinds?.primaryIcon?.invoke() ?: return
        val button = toolButtons[Tool.PEN] ?: return
        if (icon.token == primaryPenToken) return
        primaryPenToken = icon.token
        button.setImageDrawable(icon.newDrawable())
    }

    /** The row's alt-pen button wears the screen's painted glyph always (arc 46 "Palette") —
     *  [syncPrimaryPen]'s rule for the second kind. Null on every screen but the sketch face. */
    private fun syncAltPen() {
        val icon = penKinds?.altIcon?.invoke() ?: return
        val button = altPenButton ?: return
        if (icon.token == altPenToken) return
        altPenToken = icon.token
        button.setImageDrawable(icon.newDrawable())
    }

    /** The overflow row alone down — the notebook's Insert takes it down before hanging its own
     *  bar under the mini toolbar, where the two would otherwise share one edge. Idempotent. */
    fun hideOverflow() {
        if (!more.isShowing) return
        onClose()
        more.hide()
        onChanged()
    }

    /** Both rows down. Idempotent — every dismiss path calls it without checking. */
    fun dismiss() {
        if (!mini.isShowing && !more.isShowing) return
        onClose()
        more.hide()
        mini.hide()
        onChanged()
    }

    /**
     * The outside-contact dismissal ([CollapsedTools.outsideTapDismisses]): a contact anywhere but
     * the corner button, the rows, or a sub-bar the screen has hung off them ([keep]) takes the
     * rows down. The screen calls it for every pointer going down, before anything consumes it;
     * the answer says whether this contact was spent on a dismissal (the notebook's paste latch).
     * Nothing is computed while nothing is showing — the idle path is one flag read.
     */
    fun dismissOnContact(x: Int, y: Int, keep: (Int, Int) -> Boolean = { _, _ -> false }): Boolean {
        if (!isShowing) return false
        if (!CollapsedTools.outsideTapDismisses(showing = true, onChrome = contains(x, y), keep = keep(x, y))) return false
        dismiss()
        return true
    }

    /** The visible corner button's and rows' rects in **window** coordinates — for exclusions. */
    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(knob)) + mini.rects() + more.rects()

    /**
     * The matching hit test — the corner button included, so the button that toggles the rows is
     * never also the contact that dismisses them (the lasso popup's close-then-reopen trap).
     * Window coordinates, which on these full-bleed immersive screens are the root's.
     */
    fun contains(x: Int, y: Int): Boolean =
        PaperToolbar.rectOf(knob)?.contains(x, y) == true || mini.contains(x, y) || more.contains(x, y)

    private companion object {
        const val TAG = "CollapsedChrome"

        /** [knobIcon]'s stand-in for "wearing a painted glyph, not a resource one" — 0 is never a
         *  resource id, so it cannot collide with one the button might actually be showing. */
        const val PAINTED = 0
    }
}
