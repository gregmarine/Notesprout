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
 * The lasso button's popup (arc 8): a small bordered bar hung under the **already-armed** lasso
 * button, holding **Paste** and **Clear** for the object clipboard.
 *
 * It exists because tap-to-place is invisible. A pen tap on bare paper pastes, and the only other
 * hint is the button's own clipboard-marked icon — so the two acts that have no gesture (paste at
 * the *source* coordinates, and throw the clipboard away) need somewhere to live, and the armed
 * lasso button is the one control that already means "the clipboard is in play".
 *
 * **It opens only while the clipboard holds objects.** With a page on the clipboard — or nothing —
 * a second tap on the armed lasso stays P1's silent no-op: the popup is absent rather than open and
 * half-empty, which is the same rule as the page sheet's absent Paste row (a control that cannot
 * work is not shown greyed; on e-ink a greyed control is invisible anyway).
 *
 * Buttons are **icon-only with long-press hints** (the O1 phase-start decision) — the same recipe
 * as every other chrome button in SN, at [R.dimen.toolbar_button_size].
 *
 * The screen owns *when* it closes: a tool switch, a page swap, a finger gesture, an outside tap,
 * a paste, a clear. It also unions [rects] into the exclusion rects and counts it as chrome for the
 * finger paths — a pen landing on the popup must never ink, and a finger tapping it must not be
 * read as a page gesture.
 */
class LassoPopup(
    private val root: ViewGroup,
    private val bar: LinearLayout,
    /** The armed lasso button the popup hangs under. */
    private val anchor: View,
    /** The free band's bottom edge in root coordinates (the bottom strip's top); null before layout. */
    private val bandBottom: () -> Int?,
    private val releaseRender: () -> Unit,
    private val onPaste: () -> Unit,
    private val onClear: () -> Unit,
) {

    private val density = root.resources.displayMetrics.density

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        bar.addView(button(R.drawable.ic_clipboard, ctx.getString(R.string.paste_objects_action)) {
            releaseRender()
            onPaste()
        })
        bar.addView(button(R.drawable.ic_trash, ctx.getString(R.string.clear_clipboard_action)) {
            releaseRender()
            onClear()
        })
    }

    /**
     * Open the popup under the anchor. Returns false — showing nothing — before the root has been
     * laid out, which is also what makes the caller's "second tap = no-op" honest at every moment
     * the geometry is not yet knowable.
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

    /** The visible popup's rect in **window** coordinates — for exclusions / `overChrome`. */
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
        /** Gap between the lasso button and the popup — the selection bar's gap, so the two
         *  floating bars sit off their anchors alike. */
        const val GAP_DP = 8f
    }
}
