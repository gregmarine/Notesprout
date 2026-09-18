package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * A small bordered bar of icon buttons hung **under a top-bar button** (arc 21 / W2) — the shape
 * [LassoPopup] minted in arc 8 and the tag button's secondary toolbar needed a second time.
 *
 * It exists as one class rather than two because the second one would have been a copy: the same
 * placement call, the same measure-before-place rule, the same rects, the same button recipe. That
 * is the `RattaNotebookView` sibling-copy trap in miniature, and the app's answer to it is always
 * to put the shared logic in one place and let the callers say only what differs — which here is
 * the buttons and when the bar is allowed to open.
 *
 * Buttons are **icon-only with long-press hints**, the recipe every chrome button in SN follows,
 * at [R.dimen.toolbar_button_size] so they grow with the tablet tier. [bar]'s own orientation is
 * the caller's layout: a horizontal one takes buttons ([addButton]), and a **vertical** one takes
 * rows the caller builds ([addRow], arc 44 / T3 — the sketch face's [PencilBar]).
 *
 * What the caller still owns: *when* it opens and closes (a tool switch, a page swap, a finger
 * gesture, an outside tap), and unioning [rects] into the exclusion rects and the `overChrome`
 * test — a pen landing on a floating bar must never ink, and a finger tapping one must not read
 * as a page gesture.
 *
 * Moved from `:app` into `:sn-screen` in arc 29 / LE2 so the eraser sub-bar ([EraserBar]) can be
 * shared by all four paper surfaces rather than copied into each one.
 */
class AnchoredBar(
    private val root: ViewGroup,
    private val bar: LinearLayout,
    /** The top-bar button the bar hangs under by default — [show] may name another (arc 36). */
    private val anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    private val bandBottom: () -> Int?,
) {

    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    /**
     * Add one button, left to right in call order — and hand it back, so a caller whose buttons
     * arrive phase by phase can hide the ones that would do nothing yet (J4: GONE, never
     * disabled). Callers that offer everything they add ignore the return, as they always did.
     */
    fun addButton(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton =
        button(iconRes, hint, onClick).also { bar.addView(it) }

    /**
     * Add a whole child of the caller's own making, in call order (arc 44 / T3) — what a bar whose
     * [bar] is **vertical** needs, because its children are rows rather than buttons: the sketch
     * face's [PencilBar] is two rows of shade swatches over a row of sizes, and a swatch is not an
     * icon button at all (it is a ring and a fill, and the fill is ink).
     *
     * Everything else stays exactly as it is for every other bar: the placement, the
     * measure-before-place rule, the rects and the hit test know nothing about what is inside. What
     * the caller gives up by coming through this door rather than [addButton] is the shared button
     * recipe — so a caller that builds its own children owns the dimen-driven size, the hint and
     * the tooltip itself ([button] is public and static for exactly that).
     */
    fun addRow(row: View) {
        bar.addView(row)
    }

    /**
     * Open the bar under [anchor] — the constructor's button unless a caller names another: the
     * collapsed chrome (arc 36) hangs the Insert bar and the tags popup off its own mini-toolbar
     * buttons, because the bar button they were built on is inside a `GONE` bar and keeps stale
     * edges. Returns false — showing nothing — before the root has been laid out, which is what
     * makes a caller's "second tap = no-op" honest at every moment the geometry is not yet knowable.
     */
    fun show(anchor: View = this.anchor): Boolean {
        val band = bandBottom() ?: return false
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val anchorLoc = IntArray(2).also { anchor.getLocationInWindow(it) }
        // Visibility-aware, like every rect reader since arc 33: a `GONE` anchor (a bar button while
        // the chrome is collapsed) keeps stale edges, and a bar hung under it would land under
        // nothing — so that show is a loud no-op rather than a misplaced bar.
        if (PaperToolbar.rectOf(anchor) == null) return false

        // Measure before placing: the anchor centres on the bar's real width, and a bar that has
        // never been visible has none (the SelectionToolbar lesson).
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = SelectionAnchor.placeUnder(
            anchorLeft = anchorLoc[0] - rootLoc[0],
            anchorRight = anchorLoc[0] - rootLoc[0] + anchor.width,
            anchorBottom = anchorLoc[1] - rootLoc[1] + anchor.height,
            w = bar.measuredWidth,
            h = bar.measuredHeight,
            gap = (GAP_DP * density).toInt(),
            rootWidth = root.width,
            bandBottom = band,
        )
        val lp = (bar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = p.x
        lp.topMargin = p.y
        bar.layoutParams = lp
        return true
    }

    /** Idempotent — every dismiss path calls it without checking. */
    fun hide() {
        bar.visibility = View.GONE
    }

    /** The visible bar's rect in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(bar))

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    private fun button(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton =
        button(bar.context, iconRes, hint, onClick)

    companion object {
        /** Gap between the anchoring button and the bar — the selection bar's gap, so every
         *  floating bar sits off its anchor alike. */
        private const val GAP_DP = 8f

        /**
         * **The** floating-bar icon-button recipe: dimen-driven size (so it grows with the tablet
         * tier), no ripple, no state-list animator, tooltip == content description. Public and
         * static because the other two floating bars in the notebook (`ShapeTransformBar`,
         * `SelectionToolbar`) build their buttons the same way and had each grown a copy of it —
         * one recipe, or three that drift.
         */
        fun button(
            ctx: Context,
            iconRes: Int,
            hint: String,
            onClick: () -> Unit,
        ): AppCompatImageButton {
            val size = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
            val pad = ctx.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
            return AppCompatImageButton(ctx).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.bg_toolbar_button)
                stateListAnimator = null
                contentDescription = hint
                TooltipCompat.setTooltipText(this, hint)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setOnClickListener { onClick() }
            }
        }
    }
}
