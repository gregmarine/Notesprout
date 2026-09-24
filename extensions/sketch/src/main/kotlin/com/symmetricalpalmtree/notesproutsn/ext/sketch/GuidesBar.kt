package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.notebook.AnchoredBar
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle

/**
 * The **guides panel** (arc 51 "Guides" / J3, the user's decision 5) — one anchored panel under the
 * top bar's Guides button, [PaletteBar]'s recipe over `:sn-screen`'s [AnchoredBar]: the bar places,
 * measures and owns its rects; the screen owns *when* it opens and closes and unions [rects] into
 * the exclusions and the `overChrome` test, because a pen landing on a floating bar must never ink.
 *
 * ## Rows
 *
 * - **Grid** — `Off · Lines · Dots` latches, then the grid's Show/Hide eye.
 * - the six **counts** (`2 3 4 6 8 12`) — only while a grid is on.
 * - **Reference** — Pick (`ic_photo`), Remove (`ic_trash`) and the image's Show/Hide eye.
 * - the four **opacities** (`10 % 25 % 50 % 75 %`) — only while the page carries an image.
 *
 * **Latches, never steppers or sliders** (decision 5): exactly one of a row is down, and down is
 * `bg_selectable_card`'s thicker border — never a colour, never a grey. A control that means nothing
 * yet (Remove, the eye and the opacities with no image; the counts and the eye with no grid) is
 * **`GONE`, never disabled** — a disabled button is invisible on e-ink and reads as broken. When a
 * pick changes which rows show, the bar is re-placed under its anchor, so it stays centred on it.
 *
 * **It stays open after a pick** ([PaletteBar]'s rule — "this count — no, that one"), and closes on
 * the Guides button's re-tap, any tool change, a page swap, a finger gesture, a chrome flip, a
 * contact outside it, and the exit: the screen's list, not this class's.
 *
 * **Frame silence**: opening this bar and repainting it after a pick are one chrome frame each at a
 * deliberate tap — the floating-bar exception every SN sub-bar rides. The render release before a
 * pick is pen-gated, [PenIdle.releaseRenderIfIdle]'s contract.
 */
class GuidesBar(
    root: ViewGroup,
    bar: LinearLayout,
    /** The top bar's Guides button — the default anchor; [show] names another for the collapsed
     *  overflow's own button. */
    private val anchor: View,
    bandBottom: () -> Int?,
    private val paper: PaperView,
    /** The page's guides — read at every open and after every pick, never cached here. */
    private val state: () -> GuideState,
    /** A pure settings pick: the screen redraws the sheet and remembers it on the page. */
    private val onChanged: (GuideState) -> Unit,
    /** Pick… — the screen opens the system picker. */
    private val onPickImage: () -> Unit,
    /** Remove — the screen deletes the page's image. */
    private val onRemoveImage: () -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)
    private val ctx: Context = root.context

    /** Where the bar hangs now — so a pick that changes which rows show re-places it under the
     *  same button it was opened from. */
    private var shownUnder: View = anchor

    private val kindLatches = ArrayList<Pair<Int, Button>>()
    private val countLatches = ArrayList<Pair<Int, Button>>()
    private val opacityLatches = ArrayList<Pair<Int, Button>>()
    private val countRow: LinearLayout
    private val opacityRow: LinearLayout
    private val gridEye: AppCompatImageButton
    private val imageEye: AppCompatImageButton
    private val removeButton: AppCompatImageButton

    val isShowing: Boolean get() = this.bar.isShowing

    init {
        // Grid
        this.bar.addRow(header(R.string.guides_grid))
        val kindRow = newRow()
        listOf(
            SketchContract.GRID_OFF to R.string.guides_grid_off,
            SketchContract.GRID_LINES to R.string.guides_grid_lines,
            SketchContract.GRID_DOTS to R.string.guides_grid_dots,
        ).forEach { (kind, label) ->
            val latch = latch(ctx.getString(label)) { pick { it.withGrid(kind) } }
            kindRow.addView(latch)
            kindLatches += kind to latch
        }
        gridEye = AnchoredBar.button(ctx, R.drawable.ic_eye, ctx.getString(R.string.guides_hide_grid)) {
            pick { it.toggleGridVisible() }
        }
        kindRow.addView(gridEye)
        this.bar.addRow(kindRow)

        countRow = newRow()
        GuideSheet.COUNTS.forEach { count ->
            // Numbers are not words, so not strings — the calendar's count latches' rule.
            val latch = latch(count.toString()) { pick { it.withCount(count) } }
            countRow.addView(latch)
            countLatches += count to latch
        }
        this.bar.addRow(countRow)

        // Reference
        this.bar.addRow(header(R.string.guides_reference))
        val imageRow = newRow()
        imageRow.addView(
            AnchoredBar.button(ctx, R.drawable.ic_photo, ctx.getString(R.string.guides_pick_image)) {
                PenIdle.releaseRenderIfIdle(paper)
                onPickImage()
            },
        )
        removeButton = AnchoredBar.button(ctx, R.drawable.ic_trash, ctx.getString(R.string.guides_remove_image)) {
            PenIdle.releaseRenderIfIdle(paper)
            onRemoveImage()
        }
        imageRow.addView(removeButton)
        imageEye = AnchoredBar.button(ctx, R.drawable.ic_eye, ctx.getString(R.string.guides_hide_image)) {
            pick { it.toggleImageVisible() }
        }
        imageRow.addView(imageEye)
        this.bar.addRow(imageRow)

        opacityRow = newRow()
        GuideSheet.OPACITIES.forEach { percent ->
            val latch = latch(ctx.getString(R.string.guides_opacity, percent)) { pick { it.withOpacity(percent) } }
            opacityRow.addView(latch)
            opacityLatches += percent to latch
        }
        this.bar.addRow(opacityRow)
    }

    /** Open the bar under [anchor], painted from the page's state. False — nothing shown — before
     *  the root has been laid out ([AnchoredBar.show]'s rule). */
    fun show(anchor: View = this.anchor): Boolean {
        paint(state())
        shownUnder = anchor
        return bar.show(anchor)
    }

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    /** Repaint from the screen's state — after an image lands or goes, which the screen learns
     *  asynchronously. Re-places the bar when the rows it shows changed. */
    fun refresh() {
        if (!isShowing) return
        if (paint(state())) bar.show(shownUnder)
    }

    /** A settings pick: release the render (pen-gated), hand the screen the new state, repaint
     *  from what the screen now says. */
    private fun pick(edit: (GuideState) -> GuideState) {
        PenIdle.releaseRenderIfIdle(paper)
        onChanged(edit(state()))
        refresh()
    }

    /**
     * Make every latch and eye honest about [s]; answers whether a row's visibility changed (the
     * bar's size did, so it must be re-placed). `setSelected` and `visibility` are no-ops on a
     * repeat, so a repaint that changes nothing costs no frame.
     */
    private fun paint(s: GuideState): Boolean {
        kindLatches.forEach { (kind, b) -> b.isSelected = kind == s.gridKind }
        countLatches.forEach { (count, b) -> b.isSelected = count == s.gridCount }
        opacityLatches.forEach { (percent, b) -> b.isSelected = percent == s.imageOpacity }
        eye(gridEye, s.gridVisible, R.string.guides_hide_grid, R.string.guides_show_grid)
        eye(imageEye, s.imageVisible, R.string.guides_hide_image, R.string.guides_show_image)
        var moved = false
        moved = setShown(gridEye, s.gridOn) || moved
        moved = setShown(countRow, s.gridOn) || moved
        moved = setShown(removeButton, s.hasImage) || moved
        moved = setShown(imageEye, s.hasImage) || moved
        moved = setShown(opacityRow, s.hasImage) || moved
        return moved
    }

    /** An eye says what is true now (open = shown) and its hint says what a tap does. */
    private fun eye(button: AppCompatImageButton, visible: Boolean, hideRes: Int, showRes: Int) {
        val icon = if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off
        if (button.tag != icon) {
            button.tag = icon
            button.setImageResource(icon)
        }
        val hint = ctx.getString(if (visible) hideRes else showRes)
        if (button.contentDescription != hint) {
            button.contentDescription = hint
            TooltipCompat.setTooltipText(button, hint)
        }
    }

    private fun setShown(view: View, shown: Boolean): Boolean {
        val want = if (shown) View.VISIBLE else View.GONE
        if (view.visibility == want) return false
        view.visibility = want
        return true
    }

    /** A word- or number-labelled latch: `Widget.Notesprout.LatchButton`'s look, at the bar's
     *  dimen-driven button height so it grows with the tablet tier beside the icon buttons. */
    private fun latch(label: String, onClick: () -> Unit): Button {
        val size = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val gap = (GAP_DP * ctx.resources.displayMetrics.density).toInt()
        return Button(ctx, null, 0, R.style.Widget_Notesprout_LatchButton).apply {
            text = label
            minWidth = size
            minimumWidth = size
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size).apply {
                setMargins(gap, gap, gap, gap)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun header(res: Int): TextView = TextView(ctx).apply {
        text = ctx.getString(res)
        setTextColor(ContextCompat.getColor(ctx, R.color.inkBlack))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP)
        val pad = (GAP_DP * ctx.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun newRow(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private companion object {
        /** Air around each latch, so a row of bordered latches reads as separate controls. */
        const val GAP_DP = 3f

        /** The row headers — small, black, carrying information (the palette rule for secondary
         *  text: smaller, never grey). */
        const val HEADER_SP = 13f
    }
}
