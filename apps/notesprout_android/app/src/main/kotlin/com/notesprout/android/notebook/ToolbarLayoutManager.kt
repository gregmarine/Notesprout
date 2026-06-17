package com.notesprout.android.notebook

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.core.content.ContextCompat
import com.notesprout.android.R
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
     * Rebuild [toolbar]'s children from [config]: visible buttons in order, auto-dividers between
     * group boundaries, then the internal trailing weight + overflow controls. Idempotent — safe
     * to re-apply when the config changes.
     */
    fun apply(config: ToolbarConfig) {
        applyPlacement(config.placement)

        val views = captureButtonViews()

        toolbar.removeAllViews()

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
        toolbar.addView(dividerOverflow)
        toolbar.addView(btnOverflow)
    }

    /**
     * Anchor the bar to an edge, set its orientation + size, and select the matching edge-aware
     * background (border on the inner edge). TOP/BOTTOM are horizontal (match_parent × thickness);
     * LEFT/RIGHT are vertical (thickness × match_parent). FLOAT arrives in a later session; until
     * then it falls back to the top layout so the bar is never lost.
     */
    private fun applyPlacement(placement: ToolbarPlacement) {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        when (placement) {
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
            else -> {
                toolbar.orientation = LinearLayout.HORIZONTAL
                toolbar.gravity = Gravity.CENTER_VERTICAL
                lp.gravity = Gravity.TOP
                lp.width = match
                lp.height = barThicknessPx
                toolbar.setBackgroundResource(R.drawable.toolbar_background_top)
            }
        }
        toolbar.layoutParams = lp
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
     * order − hidden, in config order, keeping only keys with a registered spec. The pinned Close
     * is always retained (even if listed in `hidden`) and force-prepended if missing from `order`.
     *
     * Keys are append-only: a config persisted before a button shipped won't list that key. Any
     * registered key missing from `order` (and not hidden) is appended at the end so new buttons
     * always surface — otherwise, e.g., the gear that opens the customize dialog could vanish for
     * users with an older saved config.
     */
    private fun resolveVisibleKeys(config: ToolbarConfig): List<String> {
        val pinned = ToolbarButtonRegistry.PINNED_KEY
        val result = config.order.filterTo(mutableListOf()) { key ->
            ToolbarButtonRegistry.spec(key) != null && (key == pinned || key !in config.hidden)
        }
        for (key in ToolbarButtonRegistry.DEFAULT_ORDER) {
            if (key !in config.order && key !in config.hidden && key !in result) result.add(key)
        }
        if (pinned !in result && ToolbarButtonRegistry.spec(pinned) != null) result.add(0, pinned)
        return result
    }

    /** A plain inkBlack group divider, orientation-aware: 1dp × 28dp for a horizontal bar, 28dp × 1dp
     *  for a vertical bar, with margins on the main axis. Must be a bare [View] so
     *  [ToolbarOverflowManager.isDivider] recognises it. */
    private fun makeDivider(): View = View(context).apply {
        layoutParams = if (toolbar.orientation == LinearLayout.VERTICAL) {
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
        setBackgroundColor(ContextCompat.getColor(context, R.color.inkBlack))
    }

    /** The weight=1 [Space] that pushes the overflow controls to the trailing edge.
     *  [ToolbarOverflowManager] detects this via its positive weight and preserves it. */
    private fun makeWeightSpace(): View = Space(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
