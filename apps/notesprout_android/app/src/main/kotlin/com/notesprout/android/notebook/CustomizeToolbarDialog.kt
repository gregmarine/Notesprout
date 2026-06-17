package com.notesprout.android.notebook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.AppCompatButton
import com.notesprout.android.R
import com.notesprout.android.data.toolbar.ToolbarAxis
import com.notesprout.android.data.toolbar.ToolbarConfig
import com.notesprout.android.data.toolbar.ToolbarPlacement
import com.notesprout.android.databinding.DialogCustomizeToolbarBinding
import com.notesprout.android.databinding.ItemToolbarCustomizeRowBinding

/**
 * "Customize Toolbar" dialog — reorder buttons by dragging the grip handle, and show/hide each
 * button by tapping its row. The pinned Close button can be reordered but never hidden.
 *
 * No Material, no RecyclerView (not a dependency). Rows are plain inflated views inside a vertical
 * [LinearLayout] in a [ScrollView]; drag-reorder is hand-rolled — the lifted row follows the finger
 * while neighbours slide to preview the gap (make-room), with edge auto-scroll, and the row is
 * reparented once on drop (move-not-clone, the same spirit as [ToolbarLayoutManager]).
 *
 * On Save the new order (read from the row view order) plus the hidden set is folded into a fresh
 * [ToolbarConfig] (preserving all other fields) and handed back via [onApply], which persists it
 * and re-applies the live toolbar.
 */
class CustomizeToolbarDialog(
    private val context: Context,
    private val current: ToolbarConfig,
    private val onApply: (ToolbarConfig) -> Unit,
) {
    private val hidden: MutableSet<String> = current.hidden.toMutableSet()

    /** Live placement selection; committed to the config on Save. */
    private var placement: ToolbarPlacement = current.placement

    /** Live float-axis selection; only meaningful when [placement] is FLOAT. */
    private var floatAxis: ToolbarAxis = current.floatAxis

    private val density = context.resources.displayMetrics.density

    // Reusable scratch buffer for getLocationOnScreen during drag.
    private val loc = IntArray(2)

    // ── Active drag state (only one drag at a time) ─────────────────────────────
    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var dragRow: View? = null
    private var dragFrom = -1
    private var dragStartRawY = 0f
    private var dragStartScrollY = 0
    private var dragLastRawY = 0f

    /** Distance (px) from a viewport edge within which dragging triggers auto-scroll. */
    private val edgeZonePx get() = 56f * density
    /** Auto-scroll step per tick (px) and tick interval (ms). */
    private val autoScrollStepPx get() = (12f * density).toInt()
    private val autoScrollIntervalMs = 24L

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val dir = autoScrollDirection()
            if (dir == 0 || dragRow == null) return
            scrollView.scrollBy(0, dir * autoScrollStepPx)
            updateDragVisual()
            handler.postDelayed(this, autoScrollIntervalMs)
        }
    }

    fun show() {
        val binding = DialogCustomizeToolbarBinding.inflate(LayoutInflater.from(context))
        scrollView = binding.rowScroll
        container = binding.rowContainer
        bindPlacementControl(binding)
        populateRows(binding.rowContainer, workingOrder(current.order))

        val dialog = AlertDialog.Builder(context)
            .setTitle("Customize Toolbar")
            .setView(binding.root)
            .setPositiveButton("Save", null)
            .setNeutralButton("Reset", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        // Cap the scrolling list height so the fixed header + the AlertDialog's title + button bar
        // always have room. With a wrap_content ScrollView, a tall list can squeeze the Save/Cancel
        // buttons off the bottom; bounding it makes only the list scroll, deterministically.
        scrollView.post {
            val maxH = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
            if (scrollView.height > maxH) {
                scrollView.layoutParams = scrollView.layoutParams.apply { height = maxH }
                scrollView.requestLayout()
            }
        }

        // Reset rebuilds the list in place (defaults) without closing — user still confirms via Save.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            hidden.clear()
            placement = ToolbarPlacement.TOP
            floatAxis = ToolbarAxis.HORIZONTAL
            refreshPlacementButtons(binding)
            populateRows(binding.rowContainer, ToolbarButtonRegistry.DEFAULT_ORDER)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newOrder = readOrder(binding.rowContainer)
            onApply(current.copy(
                order = newOrder,
                hidden = hidden.toSet(),
                placement = placement,
                floatAxis = floatAxis,
            ))
            dialog.dismiss()
        }
    }

    /**
     * Wire the placement segmented control (all five placements live) and the float-axis toggle. The
     * axis row is shown only while Float is selected. Selected state is indicated with `isSelected`
     * (border via `bg_toolbar_button`).
     */
    private fun bindPlacementControl(binding: DialogCustomizeToolbarBinding) {
        binding.btnPlaceTop.setOnClickListener { placement = ToolbarPlacement.TOP; refreshPlacementButtons(binding) }
        binding.btnPlaceBottom.setOnClickListener { placement = ToolbarPlacement.BOTTOM; refreshPlacementButtons(binding) }
        binding.btnPlaceLeft.setOnClickListener { placement = ToolbarPlacement.LEFT; refreshPlacementButtons(binding) }
        binding.btnPlaceRight.setOnClickListener { placement = ToolbarPlacement.RIGHT; refreshPlacementButtons(binding) }
        binding.btnPlaceFloat.setOnClickListener { placement = ToolbarPlacement.FLOAT; refreshPlacementButtons(binding) }
        binding.btnAxisHorizontal.setOnClickListener { floatAxis = ToolbarAxis.HORIZONTAL; refreshPlacementButtons(binding) }
        binding.btnAxisVertical.setOnClickListener { floatAxis = ToolbarAxis.VERTICAL; refreshPlacementButtons(binding) }
        refreshPlacementButtons(binding)
    }

    private fun refreshPlacementButtons(binding: DialogCustomizeToolbarBinding) {
        val map: List<Pair<AppCompatButton, ToolbarPlacement>> = listOf(
            binding.btnPlaceTop to ToolbarPlacement.TOP,
            binding.btnPlaceBottom to ToolbarPlacement.BOTTOM,
            binding.btnPlaceLeft to ToolbarPlacement.LEFT,
            binding.btnPlaceRight to ToolbarPlacement.RIGHT,
            binding.btnPlaceFloat to ToolbarPlacement.FLOAT,
        )
        for ((button, value) in map) button.isSelected = value == placement
        binding.axisRow.visibility = if (placement == ToolbarPlacement.FLOAT) View.VISIBLE else View.GONE
        binding.btnAxisHorizontal.isSelected = floatAxis == ToolbarAxis.HORIZONTAL
        binding.btnAxisVertical.isSelected = floatAxis == ToolbarAxis.VERTICAL
    }

    /**
     * Saved configs predate later-added buttons (keys are append-only). Start from the saved order,
     * drop any keys without a spec, then append registry keys not yet present so new buttons always
     * appear.
     */
    private fun workingOrder(savedOrder: List<String>): List<String> {
        val result = savedOrder.filterTo(mutableListOf()) { ToolbarButtonRegistry.spec(it) != null }
        for (key in ToolbarButtonRegistry.DEFAULT_ORDER) if (key !in result) result.add(key)
        return result
    }

    private fun populateRows(container: LinearLayout, order: List<String>) {
        container.removeAllViews()
        for (key in order) {
            val spec = ToolbarButtonRegistry.spec(key) ?: continue
            val row = ItemToolbarCustomizeRowBinding.inflate(
                LayoutInflater.from(context), container, false
            )
            row.root.tag = key
            row.icon.setImageResource(spec.iconRes)
            row.label.text = spec.label
            bindVisibility(row, spec)

            if (!spec.pinned) {
                row.root.setOnClickListener {
                    if (key in hidden) hidden.remove(key) else hidden.add(key)
                    bindVisibility(row, spec)
                }
            }
            attachDragHandle(container, row)
            container.addView(row.root)
        }
    }

    private fun bindVisibility(row: ItemToolbarCustomizeRowBinding, spec: ToolbarButtonRegistry.ButtonSpec) {
        when {
            spec.pinned -> {
                row.status.text = "Always shown"
                row.label.setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
            }
            (row.root.tag as? String) in hidden -> {
                row.status.text = "Hidden"
                row.label.setTextColor(ContextCompat.getColor(context, R.color.inkLight))
            }
            else -> {
                row.status.text = "Shown"
                row.label.setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
            }
        }
    }

    /**
     * Hand-rolled drag-reorder. Pressing the grip handle lifts [row]; during the drag the lifted row
     * follows the finger and the *other* rows slide to open a gap at the drop slot (a live preview).
     * The single reparent is committed on release.
     *
     * The dragged row's view is never removed mid-gesture: `removeView` on it would fire
     * `ACTION_CANCEL` to its own handle (the active touch target) and kill the drag. So the preview is
     * done purely with `translationY` on the *neighbours*; only on `ACTION_UP` — once the gesture is
     * already ending — do we reparent for real.
     */
    private fun attachDragHandle(container: LinearLayout, row: ItemToolbarCustomizeRowBinding) {
        val rowView = row.root
        row.dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Stop the enclosing ScrollView from auto-scrolling on the touch — we drive scroll
                    // ourselves via [autoScrollRunnable] so the dragged row stays under the finger.
                    rowView.parent?.requestDisallowInterceptTouchEvent(true)
                    dragRow = rowView
                    dragFrom = container.indexOfChild(rowView)
                    dragStartRawY = event.rawY
                    dragLastRawY = event.rawY
                    dragStartScrollY = scrollView.scrollY
                    rowView.alpha = 0.6f
                    rowView.translationY = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragLastRawY = event.rawY
                    updateDragVisual()
                    // Start/continue auto-scroll when near an edge; the runnable self-cancels when not.
                    if (autoScrollDirection() != 0) {
                        handler.removeCallbacks(autoScrollRunnable)
                        handler.post(autoScrollRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag(commit = event.actionMasked == MotionEvent.ACTION_UP)
                    true
                }
                else -> false
            }
        }
    }

    /** Recompute the lifted row's offset (kept under the finger across scroll) and the neighbours'
     *  make-room offsets for the current drop slot. */
    private fun updateDragVisual() {
        val rowView = dragRow ?: return
        if (dragFrom < 0) return
        val scrollDelta = (scrollView.scrollY - dragStartScrollY).toFloat()
        rowView.translationY = (dragLastRawY - dragStartRawY) + scrollDelta

        val rowTotal = rowTotalPx().toFloat()
        if (rowTotal <= 0f) return
        // Destination index the dragged row would occupy after removal + reinsertion.
        val dest = dropSlot().let { if (it > dragFrom) it - 1 else it }
        for (i in 0 until container.childCount) {
            if (i == dragFrom) continue
            val child = container.getChildAt(i)
            val shifted = when {
                dragFrom < dest && i in (dragFrom + 1)..dest -> i - 1   // moving down: rows slide up
                dragFrom > dest && i in dest until dragFrom -> i + 1     // moving up: rows slide down
                else -> i
            }
            child.translationY = (shifted - i) * rowTotal
        }
    }

    /** Ends the drag: stops auto-scroll, clears all offsets, and (on a real drop) reparents the row. */
    private fun endDrag(commit: Boolean) {
        handler.removeCallbacks(autoScrollRunnable)
        val rowView = dragRow
        dragRow?.parent?.requestDisallowInterceptTouchEvent(false)
        val target = if (commit) dropSlot() else -1
        for (i in 0 until container.childCount) container.getChildAt(i).translationY = 0f
        rowView?.alpha = 1f
        if (commit && rowView != null && dragFrom >= 0 && target != dragFrom) {
            val insertAt = (if (target > dragFrom) target - 1 else target)
                .coerceIn(0, container.childCount - 1)
            container.removeViewAt(dragFrom)
            container.addView(rowView, insertAt)
        }
        dragRow = null
        dragFrom = -1
    }

    /** Insertion slot (0..childCount) under the current finger Y, using untranslated row geometry so
     *  the make-room offsets don't feed back into the calculation. */
    private fun dropSlot(): Int {
        val rowTotal = rowTotalPx()
        if (rowTotal <= 0) return container.childCount
        container.getLocationOnScreen(loc)
        val rel = dragLastRawY - loc[1]
        return ((rel / rowTotal) + 0.5f).toInt().coerceIn(0, container.childCount)
    }

    /** Full vertical span of one row including margins; rows are uniform height. */
    private fun rowTotalPx(): Int {
        val c = container.getChildAt(0) ?: return 0
        val lp = c.layoutParams as ViewGroup.MarginLayoutParams
        return c.height + lp.topMargin + lp.bottomMargin
    }

    /** -1 to scroll up, +1 to scroll down, 0 when the finger is away from both edges or scroll is
     *  already at the matching limit. */
    private fun autoScrollDirection(): Int {
        scrollView.getLocationOnScreen(loc)
        val top = loc[1]
        val bottom = top + scrollView.height
        return when {
            dragLastRawY < top + edgeZonePx && scrollView.scrollY > 0 -> -1
            dragLastRawY > bottom - edgeZonePx && scrollView.canScrollVertically(1) -> 1
            else -> 0
        }
    }

    private fun readOrder(container: LinearLayout): List<String> =
        (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
}
