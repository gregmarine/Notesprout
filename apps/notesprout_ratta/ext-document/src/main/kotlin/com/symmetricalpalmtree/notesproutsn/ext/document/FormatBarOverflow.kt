package com.symmetricalpalmtree.notesproutsn.ext.document

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The format bar's overflow: what does not fit on the bar moves into a panel below it.
 *
 * og's `ToolbarOverflowManager` semantics, reduced to the one bar this screen has (horizontal, no
 * pinned views, no flexible space). The two properties that make it worth having are both about
 * *not* losing the tools:
 *
 * - **Views are MOVED, never cloned.** A tool in the panel is the same object that was on the bar,
 *   so its click listener, its long-press hint and its content description come with it and cannot
 *   drift out of step with the copy on the bar.
 * - **The panel is in flow, below the bar.** It pushes the text down instead of floating over it —
 *   on e-ink an overlay leaves a ghost of itself, and a writer who opened a menu should still be
 *   able to see the line they are writing.
 *
 * The full palette is fourteen tools plus four separators; a Nomad cannot show it whole. A bar that
 * scrolled would hide its tail with no sign that there is one, so the tail moves — and it always
 * moves to the same place, so what stays on the bar stays put for a given screen and muscle memory
 * still holds.
 *
 * Two things a caller must honour, both of which the arithmetic depends on: every moveable child
 * needs an **exact px** width in its `LayoutParams` (`WRAP_CONTENT` measures as 0 here), and group
 * separators must be plain [View] instances — that is how [isDivider] tells a separator from a tool.
 */
class FormatBarOverflow(
    private val bar: LinearLayout,
    private val panel: LinearLayout,
    private val dividerOverflow: View,
    private val btnOverflow: View,
) {

    /** Every moveable item (tools and their separators) in bar order, minus the overflow controls. */
    private var originalOrder: List<View> = emptyList()
    private var initialized = false

    // ── The panel ─────────────────────────────────────────────────────────────

    fun open() { panel.visibility = View.VISIBLE }
    fun close() { panel.visibility = View.GONE }
    fun isOpen(): Boolean = panel.visibility == View.VISIBLE
    fun toggle() { if (isOpen()) close() else open() }

    /**
     * A tap anywhere that is not the bar or the panel puts the overflow away — placing the caret in
     * the text should not have to be preceded by dismissing a menu. The event is **never consumed**
     * here: the touch is the writer choosing where to type, and it must still land.
     */
    fun dismissIfOutside(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || !isOpen()) return
        if (!isInside(bar, event) && !isInside(panel, event)) close()
    }

    private fun isInside(view: View, event: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val xy = IntArray(2).also { view.getLocationOnScreen(it) }
        return event.rawX >= xy[0] && event.rawX <= xy[0] + view.width &&
            event.rawY >= xy[1] && event.rawY <= xy[1] + view.height
    }

    // ── The cut ───────────────────────────────────────────────────────────────

    private fun initialize() {
        if (initialized) return
        val moveable = mutableListOf<View>()
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child === dividerOverflow || child === btnOverflow) continue
            moveable += child
        }
        originalOrder = moveable
        initialized = true
    }

    /**
     * Work out what fits and rebuild both containers. Safe to call on every width change; the panel
     * is always left closed afterwards, because what is *in* it has just changed.
     */
    fun recalc() {
        initialize()
        val available = bar.width - bar.paddingStart - bar.paddingEnd
        if (available <= 0) return

        val naturalTotal = originalOrder.sumOf { naturalWidth(it) }
        if (naturalTotal <= available) {
            rebuild(onBar = originalOrder, inPanel = emptyList(), showOverflow = false)
            return
        }

        // Everything else has to leave room for the overflow controls themselves.
        val capacity = available - naturalWidth(dividerOverflow) - naturalWidth(btnOverflow)
        var used = 0
        var cut = originalOrder.size
        for (i in originalOrder.indices) {
            val w = naturalWidth(originalOrder[i])
            if (used + w > capacity) {
                cut = i
                break
            }
            used += w
        }
        // Never leave a group separator as the last thing on the bar: it would sit immediately
        // before the overflow separator and read as one doubled line.
        if (cut > 0 && isDivider(originalOrder[cut - 1])) cut--

        rebuild(
            onBar = originalOrder.subList(0, cut),
            inPanel = originalOrder.subList(cut, originalOrder.size),
            showOverflow = true,
        )
    }

    private fun rebuild(onBar: List<View>, inPanel: List<View>, showOverflow: Boolean) {
        for (child in originalOrder) (child.parent as? ViewGroup)?.removeView(child)
        bar.removeAllViews()
        panel.removeAllViews()

        for (child in onBar) bar.addView(child)
        bar.addView(dividerOverflow)
        bar.addView(btnOverflow)

        // GONE, not disabled: a disabled control is invisible on e-ink, and there is nothing here to
        // reach when everything already fits.
        dividerOverflow.visibility = if (showOverflow) View.VISIBLE else View.GONE
        btnOverflow.visibility = if (showOverflow) View.VISIBLE else View.GONE
        panel.visibility = View.GONE

        if (showOverflow) fillPanel(inPanel)
    }

    /** Pack the overflowed tools into rows the width of the bar, greedily, keeping bar order. */
    private fun fillPanel(items: List<View>) {
        if (items.isEmpty()) return
        val rowCapacity = bar.width - bar.paddingStart - bar.paddingEnd
        var row = newRow()
        panel.addView(row)
        var used = 0
        for (item in items) {
            val w = naturalWidth(item)
            if (used > 0 && used + w > rowCapacity) {
                row = newRow()
                panel.addView(row)
                used = 0
            }
            row.addView(item)
            used += w
        }
    }

    /** One packing row: as tall as the bar, padded so a button's white fill never paints over the
     *  panel's 1dp border. */
    private fun newRow() = LinearLayout(bar.context).apply {
        val pad = (4f * bar.resources.displayMetrics.density).toInt()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, 0, pad, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar.height)
    }

    /** The width a view occupies in the bar: its exact `LayoutParams` width plus its margins. Works
     *  for a GONE view too, whose measured width is 0. */
    private fun naturalWidth(view: View): Int {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
        val w = if (lp.width >= 0) lp.width else 0
        return w + lp.leftMargin + lp.rightMargin
    }

    /** True for the plain 1dp separators, false for every button. */
    private fun isDivider(view: View): Boolean = view.javaClass == View::class.java
}
