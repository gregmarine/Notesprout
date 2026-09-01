package com.symmetricalpalmtree.notesproutsn.notebook

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.R

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
 * at [R.dimen.toolbar_button_size] so they grow with the tablet tier.
 *
 * What the caller still owns: *when* it opens and closes (a tool switch, a page swap, a finger
 * gesture, an outside tap), and unioning [rects] into the exclusion rects and the `overChrome`
 * test — a pen landing on a floating bar must never ink, and a finger tapping one must not read
 * as a page gesture.
 */
class AnchoredBar(
    private val root: ViewGroup,
    private val bar: LinearLayout,
    /** The top-bar button the bar hangs under. */
    private val anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    private val bandBottom: () -> Int?,
) {

    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    /** Add one button, left to right in call order. */
    fun addButton(iconRes: Int, hint: String, onClick: () -> Unit) {
        bar.addView(button(iconRes, hint, onClick))
    }

    /**
     * Open the bar under the anchor. Returns false — showing nothing — before the root has been
     * laid out, which is what makes a caller's "second tap = no-op" honest at every moment the
     * geometry is not yet knowable.
     */
    fun show(): Boolean {
        val band = bandBottom() ?: return false
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val anchorLoc = IntArray(2).also { anchor.getLocationInWindow(it) }
        if (anchor.width == 0 || anchor.height == 0) return false

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
    fun rects(): List<Rect> = listOfNotNull(rectOf(bar))

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    private fun rectOf(v: View): Rect? {
        if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** One toolbar button, to the one recipe: dimen-driven size, no ripple, tooltip == description. */
    private fun button(iconRes: Int, hint: String, onClick: () -> Unit): AppCompatImageButton {
        val ctx = bar.context
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

    private companion object {
        /** Gap between the anchoring button and the bar — the selection bar's gap, so every
         *  floating bar sits off its anchor alike. */
        const val GAP_DP = 8f
    }
}
