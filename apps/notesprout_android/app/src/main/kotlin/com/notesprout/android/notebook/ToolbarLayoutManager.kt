package com.notesprout.android.notebook

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.notesprout.android.R
import com.notesprout.android.data.toolbar.ToolbarAxis
import com.notesprout.android.data.toolbar.ToolbarConfig
import com.notesprout.android.data.toolbar.ToolbarPlacement

/**
 * Arranges the *existing* toolbar button views (declared once in XML, listeners wired in
 * [com.notesprout.android.NotebookActivity]) into [toolbar] according to a [ToolbarConfig].
 *
 * Move-not-clone, exactly like [ToolbarOverflowManager]: button views are reparented, never
 * recreated, so `isSelected` state, icon state, and click listeners all survive untouched.
 *
 * Responsibilities:
 * - Resolve the active visible key list = `order` minus `hidden`, with the pinned Close always
 *   present, filtered to keys whose views exist.
 * - Anchor + orient the bar per placement: TOP/BOTTOM horizontal, LEFT/RIGHT vertical.
 * - Insert orientation-aware **auto-dividers** (1dp × 28dp horizontal, 28dp × 1dp vertical, inkBlack)
 *   between consecutive visible buttons whose [ToolbarButtonRegistry.ButtonSpec.group] differs.
 * - Append a manager-owned **internal weighted [Space]** then [dividerOverflow] + [btnOverflow]
 *   so the overflow button stays pinned to the trailing (right) edge. This is the only spacer in
 *   the system — never user-facing, never part of `order`.
 *
 * After [apply], hand off to [ToolbarOverflowManager] (which detects the weighted Space and
 * preserves it) for fit/overflow.
 */
class ToolbarLayoutManager(
    private val toolbar: LinearLayout,
    private val dividerOverflow: View,
    private val btnOverflow: View,
) {
    private val context = toolbar.context
    private val density = context.resources.displayMetrics.density

    /**
     * The bar's fixed cross-axis thickness in px (56dp), captured once from the inflated layout
     * params before any placement flips them. For a horizontal bar this is its height; for a
     * vertical bar its width. The Activity reads it via [barThickness] to position the overflow
     * menu, page indicator, and floating selection toolbar without assuming a placement.
     */
    private val barThicknessPx: Int = (toolbar.layoutParams as FrameLayout.LayoutParams).height

    /** Fixed cross-axis thickness of the bar in px — see [barThicknessPx]. */
    fun barThickness(): Int = barThicknessPx

    /**
     * Cached references to every button view, captured once from the inflated hierarchy. Views are
     * never recreated (move-not-clone), so these stay valid for the Activity's lifetime. Holding them
     * directly — rather than re-looking-up by id each apply — is essential: a hidden button's view is
     * detached from the tree, and `findViewById` cannot find a detached view, so a later unhide could
     * never re-add it.
     */
    private var buttonViews: Map<String, View>? = null

    /**
     * The manager-owned grip view that leads a FLOAT bar; the user long-press-drags it to reposition
     * the bar (drag behaviour is wired by the Activity, which owns the float position + persistence).
     * Null for every edge-anchored placement. Like the trailing weighted [Space], it is never part of
     * `order`, never a registry button, and is kept pinned at the leading edge — [ToolbarOverflowManager]
     * is told to skip + preserve it so it never overflows. Re-created on each entry into FLOAT.
     */
    var dragHandle: View? = null
        private set

    /**
     * Rebuild [toolbar]'s children from [config]: visible buttons in order, auto-dividers between
     * group boundaries, then the internal trailing weight + overflow controls. Idempotent — safe
     * to re-apply when the config changes.
     */
    fun apply(config: ToolbarConfig) {
        applyPlacement(config)

        val views = captureButtonViews()

        toolbar.removeAllViews()

        if (config.placement == ToolbarPlacement.FLOAT) {
            // Rebuilt every apply so an axis change updates its rotation + main-axis sizing. The
            // Activity re-wires the drag listener after each apply, so a fresh instance is fine.
            toolbar.addView(makeDragHandle(config.floatAxis).also { dragHandle = it })
        } else {
            dragHandle = null
        }

        var prevGroup: String? = null
        var first = true
        for (key in resolveVisibleKeys(config)) {
            val spec = ToolbarButtonRegistry.spec(key) ?: continue
            val view = views[key] ?: continue
            if (!first && spec.group != prevGroup) toolbar.addView(makeDivider())
            (view.parent as? ViewGroup)?.removeView(view)
            toolbar.addView(view)
            prevGroup = spec.group
            first = false
        }

        // Trailing internal weight pins the overflow controls to the right edge.
        toolbar.addView(makeWeightSpace())
        (dividerOverflow.parent as? ViewGroup)?.removeView(dividerOverflow)
        (btnOverflow.parent as? ViewGroup)?.removeView(btnOverflow)
        // The XML overflow divider is authored vertical (1dp × 28dp); re-shape it to match the bar's
        // current orientation so it reads as a horizontal rule in a vertical bar.
        dividerOverflow.layoutParams = dividerLayoutParams()
        toolbar.addView(dividerOverflow)
        toolbar.addView(btnOverflow)
    }

    /**
     * Anchor the bar to an edge (or float it), set its orientation + size, and select the matching
     * background. TOP/BOTTOM are horizontal (match_parent × thickness); LEFT/RIGHT are vertical
     * (thickness × match_parent) — both with an edge-aware border on the inner edge. FLOAT is a
     * detached bar: a fixed length of [FLOAT_LENGTH_FRACTION] × the matching screen dimension on its
     * main axis, [barThicknessPx] on the cross axis, positioned via margins from `floatX`/`floatY`
     * (centered on -1), with a full `shape_bordered` border all around.
     */
    private fun applyPlacement(config: ToolbarConfig) {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        // Reset float margins; only the FLOAT branch re-applies them. Edge placements sit flush.
        lp.leftMargin = 0; lp.topMargin = 0; lp.rightMargin = 0; lp.bottomMargin = 0
        when (config.placement) {
            ToolbarPlacement.LEFT -> {
                toolbar.orientation = LinearLayout.VERTICAL
                toolbar.gravity = Gravity.CENTER_HORIZONTAL
                lp.gravity = Gravity.START
                lp.width = barThicknessPx
                lp.height = match
                toolbar.setBackgroundResource(R.drawable.toolbar_background_left)
            }
            ToolbarPlacement.RIGHT -> {
                toolbar.orientation = LinearLayout.VERTICAL
                toolbar.gravity = Gravity.CENTER_HORIZONTAL
                lp.gravity = Gravity.END
                lp.width = barThicknessPx
                lp.height = match
                toolbar.setBackgroundResource(R.drawable.toolbar_background_right)
            }
            ToolbarPlacement.BOTTOM -> {
                toolbar.orientation = LinearLayout.HORIZONTAL
                toolbar.gravity = Gravity.CENTER_VERTICAL
                lp.gravity = Gravity.BOTTOM
                lp.width = match
                lp.height = barThicknessPx
                toolbar.setBackgroundResource(R.drawable.toolbar_background_bottom)
            }
            ToolbarPlacement.FLOAT -> {
                val horizontal = config.floatAxis == ToolbarAxis.HORIZONTAL
                val dm = context.resources.displayMetrics
                toolbar.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                toolbar.gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
                lp.gravity = Gravity.TOP or Gravity.START
                // Mini floats hug their content (only as long as the buttons need); full floats span a
                // fixed FLOAT_LENGTH_FRACTION of the matching screen dimension on their main axis.
                val mainSize = if (config.miniEnabled) {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                } else if (horizontal) {
                    (dm.widthPixels * FLOAT_LENGTH_FRACTION).toInt()
                } else {
                    (dm.heightPixels * FLOAT_LENGTH_FRACTION).toInt()
                }
                if (horizontal) {
                    lp.width = mainSize
                    lp.height = barThicknessPx
                } else {
                    lp.width = barThicknessPx
                    lp.height = mainSize
                }
                // WRAP_CONTENT (-2) has no measured extent yet; clamp against the full screen and let
                // the drag handler re-clamp using the real measured size on the first move.
                val maxX = if (lp.width >= 0) (dm.widthPixels - lp.width).coerceAtLeast(0) else dm.widthPixels
                val maxY = if (lp.height >= 0) (dm.heightPixels - lp.height).coerceAtLeast(0) else dm.heightPixels
                val x = if (config.floatX < 0f) maxX / 2 else config.floatX.toInt()
                val y = if (config.floatY < 0f) maxY / 2 else config.floatY.toInt()
                lp.leftMargin = x.coerceIn(0, maxX)
                lp.topMargin = y.coerceIn(0, maxY)
                toolbar.setBackgroundResource(R.drawable.shape_bordered)
            }
            else -> {
                toolbar.orientation = LinearLayout.HORIZONTAL
                toolbar.gravity = Gravity.CENTER_VERTICAL
                lp.gravity = Gravity.TOP
                lp.width = match
                lp.height = barThicknessPx
                toolbar.setBackgroundResource(R.drawable.toolbar_background_top)
            }
        }
        // Keep buttons off the bar's main-axis end borders so their white fill never paints over the
        // border stroke. A horizontal bar packs along x (pad start/end); a vertical bar along y (pad
        // top/bottom). The cross axis is handled by the bar's centering gravity.
        val pad = dp(4)
        if (toolbar.orientation == LinearLayout.VERTICAL) {
            toolbar.setPadding(pad, pad, pad, pad)
        } else {
            toolbar.setPadding(pad, 0, pad, 0)
        }
        toolbar.layoutParams = lp
    }

    /**
     * Build the FLOAT drag handle: a grip icon filling the bar's cross axis with a fixed main-axis
     * span (so [ToolbarOverflowManager] can reserve its width). Rotated 90° for a vertical bar so the
     * grip dots read across the bar's short edge.
     */
    private fun makeDragHandle(axis: ToolbarAxis): View {
        val horizontal = axis == ToolbarAxis.HORIZONTAL
        return AppCompatImageView(context).apply {
            setImageResource(R.drawable.ic_grip_vertical)
            setColorFilter(ContextCompat.getColor(context, R.color.inkBlack))
            rotation = if (horizontal) 0f else 90f
            layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
            }
        }
    }

    /**
     * Capture (once) and return every button view by key. The first call runs in `onCreate` before
     * overflow, when all buttons still live in the XML toolbar, so searching from the root finds them
     * all; the references are then held permanently.
     */
    private fun captureButtonViews(): Map<String, View> {
        buttonViews?.let { return it }
        val root = toolbar.rootView
        val captured = ToolbarButtonRegistry.SPECS.mapNotNull { spec ->
            root.findViewById<View?>(spec.viewId)?.let { spec.key to it }
        }.toMap()
        buttonViews = captured
        return captured
    }

    /**
     * The active visible key list for [config].
     *
     * **Mini mode** (`miniEnabled` *and* FLOAT placement — mini is a float-only feature): a tight bar
     * of **Close + up to 5 chosen buttons + the gear**.
     * Close always leads and the gear always trails — both are mandatory (Close keeps the way out of
     * the notebook; the gear is the only way back into the dialog to switch mini off), so neither
     * counts against the user's 5-button budget. The chosen `miniSet` (already excludes Close/gear)
     * sits between them, in its own order, filtered to keys with a registered spec.
     *
     * **Full mode** (default): `order − hidden`, in config order, keeping only keys with a registered
     * spec. The pinned buttons (Close and the gear) are always retained (even if an older config
     * listed them in `hidden`) and force-added if missing — Close to the leading edge, the gear to
     * the trailing edge. Keys are append-only: a config persisted before a button shipped won't
     * list that key, so any registered key missing from `order` (and not hidden) is appended at the
     * end — otherwise, e.g., a newly added button could vanish for users with an older saved config.
     */
    private fun resolveVisibleKeys(config: ToolbarConfig): List<String> {
        if (config.miniEnabled && config.placement == ToolbarPlacement.FLOAT) {
            val close = ToolbarButtonRegistry.PINNED_KEY
            val gear = ToolbarButtonRegistry.SETTINGS_KEY
            val result = mutableListOf<String>()
            if (ToolbarButtonRegistry.spec(close) != null) result.add(close)
            config.miniSet.filterTo(result) { key ->
                key != close && key != gear && ToolbarButtonRegistry.spec(key) != null
            }
            if (ToolbarButtonRegistry.spec(gear) != null) result.add(gear)
            return result
        }
        val result = config.order.filterTo(mutableListOf()) { key ->
            val spec = ToolbarButtonRegistry.spec(key)
            spec != null && (spec.pinned || key !in config.hidden)
        }
        for (key in ToolbarButtonRegistry.DEFAULT_ORDER) {
            if (key !in config.order && key !in config.hidden && key !in result) result.add(key)
        }
        // Both pinned buttons must always appear, even if an older saved config hid them: Close is the
        // way out of the notebook (force-prepended to the leading edge), and the gear is the only way
        // back into the customize dialog (force-appended to the trailing edge).
        val close = ToolbarButtonRegistry.PINNED_KEY
        if (close !in result && ToolbarButtonRegistry.spec(close) != null) result.add(0, close)
        val gear = ToolbarButtonRegistry.SETTINGS_KEY
        if (gear !in result && ToolbarButtonRegistry.spec(gear) != null) result.add(gear)
        return result
    }

    /** A plain inkBlack group divider, orientation-aware via [dividerLayoutParams]. Must be a bare
     *  [View] so [ToolbarOverflowManager.isDivider] recognises it. */
    private fun makeDivider(): View = View(context).apply {
        layoutParams = dividerLayoutParams()
        setBackgroundColor(ContextCompat.getColor(context, R.color.inkBlack))
    }

    /** Divider sizing for the bar's current orientation: 1dp × 28dp (vertical rule) for a horizontal
     *  bar, 28dp × 1dp (horizontal rule) for a vertical bar, with margins on the main axis. */
    private fun dividerLayoutParams(): LinearLayout.LayoutParams =
        if (toolbar.orientation == LinearLayout.VERTICAL) {
            LinearLayout.LayoutParams(dp(28), dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        } else {
            LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }

    /** The weight=1 [Space] that pushes the overflow controls to the trailing edge.
     *  [ToolbarOverflowManager] detects this via its positive weight and preserves it. */
    private fun makeWeightSpace(): View = Space(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        /** A floating bar spans this fraction of the matching screen dimension on its main axis. */
        const val FLOAT_LENGTH_FRACTION = 0.75f
    }
}
