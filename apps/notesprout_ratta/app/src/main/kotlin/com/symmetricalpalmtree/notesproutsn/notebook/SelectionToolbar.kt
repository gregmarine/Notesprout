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
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.notesproutsn.R

/**
 * What the lasso caught, as far as the toolbar is concerned — the screen classifies, the bar only
 * renders the classification.
 *
 * [STROKES] ink alone (convertible) · [HEADING] exactly one heading and nothing else (re-levelable,
 * so its current level is the selected button) · [LINK] exactly one link and nothing else (the only
 * mode that offers Edit / Unlink) · [MIXED] ink plus headings, or more than one heading — no single
 * sensible level to write, but still wrappable · [MIXED_WITH_LINK] a selection containing a link
 * alongside anything else.
 *
 * The two link-bearing modes are what enforces the arc-6 **no-nesting** rule: Link is offered on
 * every link-free selection and on none that already holds one (K1 locked decision — a link never
 * wraps a link).
 */
enum class SelectionMode { STROKES, HEADING, LINK, MIXED, MIXED_WITH_LINK }

/**
 * The selection's context toolbar: a small bordered bar that floats over the paper for as long as a
 * lasso selection is up, plus the H1…H6 level **sub-toolbar** — a second floating bar of its own,
 * the og/Paper shape (eye-check #5 finding: the levels pop up *near* the bar; they are not a row
 * that grows the bar or swaps its content).
 *
 * It replaces R5's tap-inside-the-box action sheet. A sheet was a second deliberate act on top of
 * the lasso the user had *just* drawn, and on e-ink a dialog is a full repaint; the bar is already
 * there when the selection appears, and its rect is chrome the pen cannot ink through.
 *
 * **Main bar**, in order: Delete (always) · **H** (a level is only writable on ink or on one
 * heading) · **Link** (any link-free selection — K1) · **Edit** and **Unlink** (a lone link, the
 * only selection with one payload to act on). **Sub-toolbar**: H1…H6, shown by an H tap and hung off the *bar*
 * by [SelectionAnchor.placeSub] — below it, above when the bar itself flipped — so opening it never
 * moves the Delete the user just aimed at. Every [show] closes it: a fresh selection (or a
 * re-anchor after a move) is a new decision and should not inherit the last one's open drawer.
 *
 * Geometry is [SelectionAnchor]'s, in the notebook root's coordinates: [Bounds] arrive in **paper**
 * coordinates (paper-view pixels), get grown by [SELECTION_BOX_INFLATE_PX] so the gap is measured
 * from the box g-paper actually draws rather than the tight rect, and are then shifted by the
 * paper view's offset inside the root. Placement is by margins on each bar's
 * [FrameLayout.LayoutParams] — both bars are floating children of the root, not part of either
 * chrome strip.
 *
 * The screen owns *when*: it shows on `onSelectionCreated`, hides on `onSelectionDragStarted`,
 * re-anchors after `onSelectionMoved`, and hides on dismissal and on every page swap. It also
 * unions [rects] into the exclusion rects and counts both bars as chrome for the finger paths.
 * Neither the H tap nor a level tap hides anything itself — a convert can fail and leave the very
 * same selection up, and only the screen knows which it was.
 */
class SelectionToolbar(
    private val root: ViewGroup,
    private val paperView: View,
    private val bar: LinearLayout,
    /** The sub-toolbar's own floating container (`selectionSubToolbar`); levels built in code. */
    private val subBar: LinearLayout,
    /** The free band in root coordinates: the top bar's bottom edge .. the bottom strip's top; null before layout. */
    private val band: () -> IntRange?,
    private val releaseRender: () -> Unit,
    private val onDelete: () -> Unit,
    /** A level 1..6 was tapped in the sub-row. The bars stay up — the screen decides what follows. */
    private val onLevelPicked: (Int) -> Unit,
    /** Wrap this selection in a link — inert until K2 gives it a picker to name a target with. */
    private val onLink: () -> Unit,
    /** Retarget the selected link — inert until K2 for the same reason. */
    private val onEditLink: () -> Unit,
    /** Unwrap the selected link, keeping its content on the page. */
    private val onUnlink: () -> Unit,
) {

    private val density = root.resources.displayMetrics.density

    private val headingButton: AppCompatImageButton
    private val linkButton: AppCompatImageButton
    private val editButton: AppCompatImageButton
    private val unlinkButton: AppCompatImageButton
    /** Index 0 is H1 — `levelButtons[n - 1]` is level `n`. */
    private val levelButtons: List<AppCompatImageButton>

    /** The main bar's placement from the last [show] — [placeSub] hangs the sub-toolbar off it. */
    private var barPlacement: SelectionAnchor.Placement? = null

    val isShowing: Boolean get() = bar.visibility == View.VISIBLE

    init {
        val ctx = bar.context
        bar.addView(
            // Release before the row runs, for the same reason the R5 sheet did: the tap has to
            // show its result, and the delete repaints the page underneath. Ungated — see the
            // frame-silence note in docs/notebook.md.
            button(R.drawable.ic_trash, ctx.getString(R.string.delete_selection_action)) {
                releaseRender()
                onDelete()
            }
        )
        headingButton = button(R.drawable.ic_heading, ctx.getString(R.string.heading_action)) {
            releaseRender()
            toggleLevels()
        }
        bar.addView(headingButton)

        linkButton = button(R.drawable.ic_link, ctx.getString(R.string.link_action)) {
            releaseRender()
            onLink()
        }
        bar.addView(linkButton)
        editButton = button(R.drawable.ic_edit, ctx.getString(R.string.link_edit_action)) {
            releaseRender()
            onEditLink()
        }
        bar.addView(editButton)
        unlinkButton = button(R.drawable.ic_link_off, ctx.getString(R.string.link_unlink_action)) {
            releaseRender()
            onUnlink()
        }
        bar.addView(unlinkButton)

        levelButtons = (1..LEVELS).map { level ->
            button(LEVEL_ICONS[level - 1], ctx.getString(R.string.heading_level_hint, level)) {
                releaseRender()
                onLevelPicked(level)
            }.also { subBar.addView(it) }
        }
    }

    /**
     * Show (or re-place) the bar for [bounds]. [mode] decides which buttons are offered, and in
     * [SelectionMode.HEADING] [currentLevel] is the level drawn as selected. Any open sub-toolbar
     * closes. A no-op before the root has been laid out.
     *
     * Every visibility change lands **before** the measure below — the anchor centres and flips on
     * the bar's real width, and a bar measured with the wrong buttons in it is placed off-centre.
     */
    fun show(bounds: Bounds, mode: SelectionMode, currentLevel: Int?) {
        val band = band() ?: return
        val levelable = mode == SelectionMode.STROKES || mode == SelectionMode.HEADING
        val wrappable = levelable || mode == SelectionMode.MIXED
        headingButton.visibility = if (levelable) View.VISIBLE else View.GONE
        linkButton.visibility = if (wrappable) View.VISIBLE else View.GONE
        val lone = if (mode == SelectionMode.LINK) View.VISIBLE else View.GONE
        editButton.visibility = lone
        unlinkButton.visibility = lone
        subBar.visibility = View.GONE
        // Only an existing heading has a level to report; converting ink picks one from scratch.
        val selected = if (mode == SelectionMode.HEADING) currentLevel else null
        levelButtons.forEachIndexed { i, b -> b.isSelected = (i + 1) == selected }

        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val paperLoc = IntArray(2).also { paperView.getLocationInWindow(it) }
        val dx = paperLoc[0] - rootLoc[0]
        val dy = paperLoc[1] - rootLoc[1]
        val box = bounds.inflated(SELECTION_BOX_INFLATE_PX)

        // Measure before placing: the anchor centres and flips on the bar's real size, and a bar
        // that has never been visible has none.
        bar.visibility = View.VISIBLE
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val p = SelectionAnchor.place(
            selLeft = (box.left + dx).toInt(),
            selTop = (box.top + dy).toInt(),
            selRight = (box.right + dx).toInt(),
            selBottom = (box.bottom + dy).toInt(),
            toolbarW = bar.measuredWidth,
            toolbarH = bar.measuredHeight,
            gap = (GAP_DP * density).toInt(),
            rootWidth = root.width,
            bandTop = band.first,
            bandBottom = band.last,
        )
        barPlacement = p
        place(bar, p.x, p.y)
    }

    /** Idempotent — every hide path (drag, dismiss, page swap, close) calls it without checking. */
    fun hide() {
        bar.visibility = View.GONE
        subBar.visibility = View.GONE
        barPlacement = null
    }

    /** The visible bars' rects in **window** coordinates — for exclusions / `overChrome`. */
    fun rects(): List<Rect> = listOfNotNull(rectOf(bar), rectOf(subBar))

    fun contains(x: Int, y: Int): Boolean = rects().any { it.contains(x, y) }

    /** Open/close the level sub-toolbar, hung off the main bar — which never moves for it. */
    private fun toggleLevels() {
        if (subBar.visibility == View.VISIBLE) { subBar.visibility = View.GONE; return }
        val p = barPlacement ?: return
        val band = band() ?: return
        subBar.visibility = View.VISIBLE
        subBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val q = SelectionAnchor.placeSub(
            bar = p,
            barW = bar.measuredWidth.takeIf { it > 0 } ?: bar.width,
            barH = bar.measuredHeight.takeIf { it > 0 } ?: bar.height,
            w = subBar.measuredWidth,
            h = subBar.measuredHeight,
            gap = (GAP_DP * density).toInt(),
            rootWidth = root.width,
            bandTop = band.first,
            bandBottom = band.last,
        )
        place(subBar, q.x, q.y)
    }

    private fun place(v: View, x: Int, y: Int) {
        val lp = (v.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x
        lp.topMargin = y
        v.layoutParams = lp
    }

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
        /** Gap between the drawn selection box and the bar, and between the bar and its sub-toolbar. */
        const val GAP_DP = 8f

        const val LEVELS = 6

        /** H1…H6, in order — `LEVEL_ICONS[level - 1]`. */
        val LEVEL_ICONS = intArrayOf(
            R.drawable.ic_h_1,
            R.drawable.ic_h_2,
            R.drawable.ic_h_3,
            R.drawable.ic_h_4,
            R.drawable.ic_h_5,
            R.drawable.ic_h_6,
        )

        /**
         * g-paper draws the selection box this far outside the tight `Selection.bounds`
         * (`CanvasPaperView.SELECTION_BOX_INFLATE_PX` — its companion is private, so the value is
         * mirrored here rather than referenced; keep the two in step across engine bumps).
         */
        const val SELECTION_BOX_INFLATE_PX = 12f
    }
}
