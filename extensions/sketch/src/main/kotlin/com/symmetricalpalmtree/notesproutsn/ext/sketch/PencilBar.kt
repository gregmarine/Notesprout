package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.notebook.AnchoredBar
import com.symmetricalpalmtree.notesproutsn.notebook.PenIdle

/**
 * The **pencil's own options bar** (arc 44 / T3, decision 5) — shade swatches over a row of sizes,
 * hung under the armed Pencil button on a re-tap. `EraserBar`'s shape in every particular that
 * matters: [AnchoredBar] places it, measures it and owns its rects; the screen owns *when* it opens
 * and closes, and unions [rects] into the exclusion rects and the `overChrome` test, because a pen
 * landing on a floating bar must never ink.
 *
 * It is the **first tool-options bar on an SN paper screen** since P1 removed the tool panels, and
 * the user's decision of 2026-09-17 grants it for this face alone — it is not a precedent for the
 * notebook's own toolbar.
 *
 * ## What is different from the eraser's bar
 *
 * - **It is three rows, not one.** `activity_sketch.xml`'s `pencilBar` is a *vertical*
 *   [LinearLayout] and each row is a horizontal one built here ([AnchoredBar.addRow] exists for
 *   exactly this). The rows are [SketchPalette.shadeRows]' — one row of four shades today — and then
 *   [SketchPalette.sizeRows]', two rows of six leads. **Six is the widest a row gets** on purpose:
 *   six 62 dp cells plus the bar's padding is ≈ 380 dp, well inside the Nomad's 749 dp, so neither
 *   button tier can push an entry off the edge.
 * - **A swatch is not an icon button.** It is a black ring with the ink itself as the fill, drawn
 *   here rather than being a drawable, because the fill is a grey off the ladder and the selection
 *   has to read at **every** level offered: a black swatch bordered in black says nothing. So the
 *   selected swatch gains a **white gap ring** between the ring and the fill, and a black hairline
 *   at the fill's edge — between them, one mark that works on `#000000` and one on white. Painted
 *   rather than left to the bar's white background, so it stays true whatever is behind it.
 * - **Greys are ink.** They appear here and nowhere else in any chrome — the colour rule's one
 *   opening is a swatch showing the tone being chosen. The size dots and every border are black.
 * - **The bar stays open after a pick.** A visit here is usually "this grey, that lead", and a bar
 *   that closed on the first of the two would cost a second re-tap for the second. It closes on the
 *   Pencil's own re-tap (the toggle), any tool change, a page swap, a finger gesture, a contact
 *   outside it, a chrome flip and the exit — the screen's list, not this class's.
 *
 * **Frame silence**: opening this bar and repainting it after a pick are one chrome frame each at a
 * deliberate tap, with the pen that tapped it still hovering — the floating-bar exception every
 * SN paper screen's sub-bar rides (`isPenActive` counts hover, so a pen-idle gate here would hold
 * the bar back until the hand left the glass). What *is* gated is the render release, on
 * [PenIdle.releaseRenderIfIdle]'s own contract: an ungated release inside the pen-active window can
 * cost a live stroke.
 */
class PencilBar(
    root: ViewGroup,
    bar: LinearLayout,
    /** The armed Pencil button on the top bar — the bar's default anchor. [show] may name another
     *  (the mini toolbar's own pencil button, while the chrome is collapsed). */
    private val anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    bandBottom: () -> Int?,
    private val paper: PaperView,
    /** What is armed — read at every open and after every pick, never cached here: the screen owns
     *  the tool state and a copy of it is a copy that can be wrong. */
    private val armed: () -> SketchToolState,
    /** A pick: the screen applies it to the engine and remembers it on the device. */
    private val onPicked: (SketchToolState) -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    /** Every swatch with the shade level it offers, and every dot with its size index — walked at
     *  each repaint, which is the only thing either list is for. */
    private val swatches = ArrayList<Pair<Int, View>>()
    private val sizes = ArrayList<Pair<Int, View>>()

    val isShowing: Boolean get() = this.bar.isShowing

    init {
        val ctx = root.context
        val density = ctx.resources.displayMetrics.density
        // Dimen-driven like every tap target in the app, so the swatches grow with the tablet tier
        // — never a hardcoded button size.
        val cell = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

        SketchPalette.shadeRows().forEach { levels ->
            val row = newRow(ctx)
            levels.forEach { level ->
                val hint = when (level) {
                    0 -> ctx.getString(R.string.cd_pencil_shade_black)
                    SketchPalette.WHITE_SHADE -> ctx.getString(R.string.cd_pencil_shade_white)
                    else -> ctx.getString(R.string.cd_pencil_shade, level)
                }
                val swatch = Swatch(ctx, SketchPalette.shade(level)).apply {
                    layoutParams = LinearLayout.LayoutParams(cell, cell)
                    contentDescription = hint
                    TooltipCompat.setTooltipText(this, hint)
                    setOnClickListener { pick(armed().withShade(level)) }
                }
                row.addView(swatch)
                swatches += level to swatch
            }
            this.bar.addRow(row)
        }

        val count = SketchPalette.SIZES_PX.size
        SketchPalette.sizeRows().forEach { indices ->
            val sizeRow = newRow(ctx)
            indices.forEach { index ->
                val hint = ctx.getString(R.string.cd_pencil_size, index + 1)
                val dot = SizeDot(ctx, dotDiameterPx(index, count, density, cell)).apply {
                    layoutParams = LinearLayout.LayoutParams(cell, cell)
                    // The sizes take the app's ordinary selected look — a black dot on white
                    // carries the standard border perfectly well, and only the swatches needed
                    // inventing.
                    setBackgroundResource(R.drawable.bg_toolbar_button)
                    contentDescription = hint
                    TooltipCompat.setTooltipText(this, hint)
                    setOnClickListener { pick(armed().withSize(index)) }
                }
                sizeRow.addView(dot)
                sizes += index to dot
            }
            this.bar.addRow(sizeRow)
        }
    }

    /**
     * Open the bar under [anchor], with the armed shade and size already pressed. Returns false —
     * showing nothing — before the root has been laid out ([AnchoredBar.show]'s rule), which is what
     * keeps the caller's toggle honest at every moment the geometry is not yet knowable.
     */
    fun show(anchor: View = this.anchor): Boolean {
        paint(armed())
        return bar.show(anchor)
    }

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    /**
     * Take a pick. The render release comes **first** and pen-gated, `EraserBar`'s rule and for its
     * reason; the screen then assigns it to the engine and remembers it ([onPicked]); and the bar
     * repaints, because it is still open and the pressed entry has moved.
     */
    private fun pick(next: SketchToolState) {
        PenIdle.releaseRenderIfIdle(paper)
        onPicked(next)
        paint(next)
    }

    /** Make the entries honest about [state]. `View.setSelected` invalidates on a change and does
     *  nothing on a repeat, so a repaint that changes nothing costs no frame. */
    private fun paint(state: SketchToolState) {
        swatches.forEach { (level, view) -> view.isSelected = level == state.shadeLevel }
        sizes.forEach { (index, view) -> view.isSelected = index == state.sizeIndex }
    }

    private fun newRow(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /**
     * One shade: a black ring with the grey itself inside it, and — when it is the armed one — a
     * white ring in the gap between them, then a hairline of black around the fill.
     *
     * The selection **cannot** be a border here. `bg_toolbar_button`'s black `state_selected` frame
     * is invisible against level 0, and a swatch the person cannot tell is armed is a swatch that
     * reads as broken. The gap ring reads at every dark level because it is white between two
     * darker things — the only contrast an e-paper panel always has. It is nothing at all against
     * the **white** lead (2026-09-18), whose fill is the gap's own colour, so the armed swatch
     * also draws a hairline black ring at the fill's edge: invisible on black (black on black,
     * the gap still says it), the mark that says it on white.
     */
    private class Swatch(ctx: Context, private val ink: Int) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = ctx.resources.displayMetrics.density
        private val black = ContextCompat.getColor(ctx, R.color.inkBlack)
        private val white = ContextCompat.getColor(ctx, R.color.paperWhite)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val ring = RING_DP * density
            val outer = minOf(width, height) / 2f - PAD_DP * density
            if (outer <= ring) return

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ring
            paint.color = black
            canvas.drawCircle(cx, cy, outer - ring / 2f, paint)

            paint.style = Paint.Style.FILL
            if (isSelected) {
                val gap = GAP_DP * density
                paint.color = white
                canvas.drawCircle(cx, cy, outer - ring, paint)
                val fill = maxOf(0f, outer - ring - gap)
                paint.color = ink
                canvas.drawCircle(cx, cy, fill, paint)
                val hair = HAIR_DP * density
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = hair
                paint.color = black
                canvas.drawCircle(cx, cy, maxOf(0f, fill - hair / 2f), paint)
            } else {
                paint.color = ink
                canvas.drawCircle(cx, cy, outer - ring, paint)
            }
        }
    }

    /**
     * One size: a black dot whose diameter grows with the index. It is a **legible ladder, not the
     * literal width** — 1.2 px would be a single dark pixel on the Nomad while 96 px would not fit
     * a cell, so twelve widths drawn true would show as "a row of specks and a row of blots". What
     * the two rows have to say is which is finer and which is heavier, in order, and the ladder is
     * linear in the index so it reads as one run straight across the row break.
     */
    private class SizeDot(ctx: Context, private val diameterPx: Float) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(ctx, R.color.inkBlack)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(width / 2f, height / 2f, diameterPx / 2f, paint)
        }
    }

    private companion object {

        /** The swatch's black rim, its inset from the cell edge, and the white ring that says
         *  "armed" — dp, so they hold at both button tiers. */
        const val RING_DP = 2f
        const val PAD_DP = 6f
        const val GAP_DP = 3f

        /** The hairline around the armed fill — what makes the white lead's selection visible. */
        const val HAIR_DP = 1f

        /** The size ladder's ends in dp: the finest lead and the heaviest, with the rest spaced
         *  evenly between them however many there are. The wide end grew with the list (arc 44,
         *  1.2 … 96 px) so the heaviest leads still read as heavy, and it is **clamped to the
         *  cell** below, never trusted to fit one. */
        const val MIN_DOT_DP = 4f
        const val MAX_DOT_DP = 36f

        /** The dot's clearance inside its cell, dp — the swatch's [PAD_DP], so a dot and a swatch
         *  sit the same distance off their edges and the heaviest dot never touches
         *  `bg_toolbar_button`'s selected frame. */
        const val DOT_PAD_DP = 6f

        /**
         * The [index]'th dot's diameter in px: linear across the whole list between [MIN_DOT_DP]
         * and [MAX_DOT_DP], then clamped so it stays [DOT_PAD_DP] inside [cellPx].
         *
         * The clamp is what makes one ladder safe at both button tiers: 36 dp fits the tablet's
         * 62 dp cell with room, and on the 44 dp cell below it the wide end simply stops at 32 dp
         * rather than crowding the border.
         */
        fun dotDiameterPx(index: Int, count: Int, density: Float, cellPx: Int): Float {
            val max = minOf(MAX_DOT_DP * density, cellPx - 2f * DOT_PAD_DP * density)
            val min = minOf(MIN_DOT_DP * density, max)
            if (count <= 1) return max
            val t = index / (count - 1f)
            return min + t * (max - min)
        }
    }
}
