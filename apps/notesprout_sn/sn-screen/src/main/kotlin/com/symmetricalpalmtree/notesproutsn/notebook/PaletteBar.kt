package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.core.InkTones
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * The **shade panel** (arc 46 "Palette", the user's decisions 4 and 5 as amended on the first
 * walk; arc 44 / T3's `PencilBar` remade; **every writing face's since arc 49 / P4**, the user's
 * decision 4 there) — Atelier's sixteen tones ([InkTones]) in four rows of four, hung under the
 * armed pen button on its re-tap. `EraserBar`'s shape in every particular that matters:
 * [AnchoredBar] places it, measures it and owns its rects; the screen owns *when* it opens and
 * closes, and unions [rects] into the exclusion rects and the `overChrome` test, because a pen
 * landing on a floating bar must never ink.
 *
 * **It lives here, in `:sn-screen`, because every paper surface hangs it** since P4 — the sketch
 * face's pencil and gel pen (arc 46), and the notebook's, the sticky editor's, the scratch pad's
 * and the calendar's one pen (arc 49 / P4, the user's reversal of R3's shade removal — for the
 * shade only; widths and styles stay fixed). A second copy would be the `RattaNotebookView`
 * sibling-copy trap one file at a time, which is exactly what this module exists to prevent. It
 * moved here from `:ext-sketch` unchanged in shape; what changed is that it no longer knows whose
 * shade it edits.
 *
 * ## What it edits
 *
 * **One level** — whatever [armedLevel] answers, whatever [onPicked] does with the pick. On the
 * sketch face that is the armed kind's shade (the pencil's while the pencil is armed, the gel
 * pen's while the pen is — the only way in is a re-tap on the armed kind's own button, so the
 * panel always belongs to the kind whose button it hangs under); on a writing face it is the one
 * pen's shade, device-wide. The button's fill ([ShadeIcon]) says what changed.
 *
 * ## The swatch (decision 4 — Atelier's shape)
 *
 * A swatch is not an icon button. It is the grey itself as a **filled circle**, a white gap, and
 * a **dotted** black outer ring; the **selected** swatch's outer ring is **solid**. Painted in
 * `onDraw` rather than being a drawable, because the fill is a grey off the ladder and the
 * selection has to read at every level offered — including white, which is the gap's own colour,
 * so every fill also carries a hairline of black at its edge (invisible on black, the mark that
 * makes white and the palest greys read against the white bar). Painted rather than left to the
 * bar's white background, so it stays true whatever is behind it.
 *
 * **Greys are ink.** They appear here and nowhere else in any chrome but the pen glyphs — the
 * colour rule's one opening is a swatch showing the tone being chosen. Every ring is black.
 *
 * **The bar stays open after a pick.** A visit is often "this grey — no, that one", and a bar that
 * closed on the first pick would cost a re-tap for the second. It closes on the armed button's
 * next re-tap (the toggle), any tool change, a page swap, a finger gesture, a contact outside it,
 * a chrome flip and the exit — the screen's list, not this class's.
 *
 * **Frame silence**: opening this bar and repainting it after a pick are one chrome frame each at a
 * deliberate tap, with the pen that tapped it still hovering — the floating-bar exception every SN
 * paper screen's sub-bar rides (`isPenActive` counts hover, so a pen-idle gate here would hold the
 * bar back until the hand left the glass). What *is* gated is the render release, on
 * [PenIdle.releaseRenderIfIdle]'s own contract: an ungated release inside the pen-active window can
 * cost a live stroke.
 */
class PaletteBar(
    root: ViewGroup,
    bar: LinearLayout,
    /** The top bar's pen button — the bar's default anchor. [show] names another for a second
     *  kind's own button (the sketch face), and for the mini toolbar's buttons while the chrome is
     *  collapsed. */
    private val anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    bandBottom: () -> Int?,
    private val paper: PaperView,
    /** The level the panel is editing — read at every open and after every pick, never cached
     *  here: the screen owns the shade and a copy of it is a copy that can be wrong. */
    private val armedLevel: () -> Int,
    /** A pick of a level: the screen arms the engine with its tone and remembers it. */
    private val onPicked: (level: Int) -> Unit,
) {

    private val bar = AnchoredBar(root, bar, anchor, bandBottom)

    /** Every swatch with the shade level it offers — walked at each repaint, which is the only
     *  thing the list is for. */
    private val swatches = ArrayList<Pair<Int, View>>()

    val isShowing: Boolean get() = this.bar.isShowing

    init {
        val ctx = root.context
        // Dimen-driven like every tap target in the app, so the swatches grow with the tablet tier
        // — never a hardcoded button size.
        val cell = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

        InkTones.rows().forEach { levels ->
            val row = newRow(ctx)
            levels.forEach { level ->
                val hint = when (level) {
                    InkTones.BLACK -> ctx.getString(R.string.cd_shade_black)
                    InkTones.WHITE -> ctx.getString(R.string.cd_shade_white)
                    else -> ctx.getString(R.string.cd_shade, level)
                }
                val swatch = Swatch(ctx, InkTones.tone(level)).apply {
                    layoutParams = LinearLayout.LayoutParams(cell, cell)
                    contentDescription = hint
                    TooltipCompat.setTooltipText(this, hint)
                    setOnClickListener { pick(level) }
                }
                row.addView(swatch)
                swatches += level to swatch
            }
            this.bar.addRow(row)
        }
    }

    /**
     * Open the bar under [anchor], with the armed level already pressed. Returns false —
     * showing nothing — before the root has been laid out ([AnchoredBar.show]'s rule), which is what
     * keeps the caller's toggle honest at every moment the geometry is not yet knowable.
     */
    fun show(anchor: View = this.anchor): Boolean {
        paint(armedLevel())
        return bar.show(anchor)
    }

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() = bar.hide()

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = bar.rects()

    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    /**
     * Take a pick. The render release comes **first** and pen-gated, `EraserBar`'s rule and for its
     * reason; the screen then arms the engine and remembers it ([onPicked]); and the bar repaints
     * from what the screen now says is armed ([armedLevel], not the pick — the screen may have
     * folded it), because it is still open and the pressed entry has moved.
     */
    private fun pick(level: Int) {
        PenIdle.releaseRenderIfIdle(paper)
        onPicked(level)
        paint(armedLevel())
    }

    /** Make the swatches honest about [level]. `View.setSelected` invalidates on a change and does
     *  nothing on a repeat, so a repaint that changes nothing costs no frame. */
    private fun paint(level: Int) {
        swatches.forEach { (l, view) -> view.isSelected = l == level }
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
     * One shade, Atelier's way: the grey as a filled circle with a black hairline at its edge, a
     * white gap, and an outer black ring — **dotted** when this is not the armed shade, **solid**
     * when it is.
     *
     * The selection **cannot** be a border of the cell: `bg_toolbar_button`'s black
     * `state_selected` frame is invisible against level 0, and a swatch the person cannot tell is
     * armed is a swatch that reads as broken. The white gap is what separates the fill from the
     * ring at every level, so a dotted and a solid ring read as two different things on black and
     * on white alike; the hairline is what makes the white fill a circle at all against the bar.
     */
    private class Swatch(ctx: Context, private val ink: Int) : View(ctx) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = ctx.resources.displayMetrics.density
        private val black = ContextCompat.getColor(ctx, R.color.inkBlack)
        private val dotted = DashPathEffect(floatArrayOf(DOT_DP * density, DOT_DP * density), 0f)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val ring = RING_DP * density
            val outer = minOf(width, height) / 2f - PAD_DP * density
            if (outer <= ring) return

            // The outer ring: dotted for a swatch that is offered, solid for the one that is armed.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ring
            paint.color = black
            paint.pathEffect = if (isSelected) null else dotted
            canvas.drawCircle(cx, cy, outer - ring / 2f, paint)
            paint.pathEffect = null

            // The gap is the bar's own white, left unpainted; then the fill, then its hairline.
            val fill = maxOf(0f, outer - ring - GAP_DP * density)
            paint.style = Paint.Style.FILL
            paint.color = ink
            canvas.drawCircle(cx, cy, fill, paint)
            val hair = HAIR_DP * density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = hair
            paint.color = black
            canvas.drawCircle(cx, cy, maxOf(0f, fill - hair / 2f), paint)
        }
    }

    private companion object {

        /** The outer ring's weight, the swatch's inset from the cell edge, the white gap between
         *  ring and fill, and the dotted ring's dash/space — dp, so they hold at both button tiers. */
        const val RING_DP = 2f
        const val PAD_DP = 6f
        const val GAP_DP = 3f
        const val DOT_DP = 2f

        /** The hairline around every fill — what makes the white lead a circle against the bar. */
        const val HAIR_DP = 1f
    }
}
