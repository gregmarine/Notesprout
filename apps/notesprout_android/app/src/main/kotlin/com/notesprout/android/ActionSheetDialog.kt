package com.notesprout.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * A flat, e-ink-safe action sheet dialog.
 *
 * Usage:
 *   ActionSheetDialog(context)
 *       .title("My Title")               // optional — shows title + X close button in header
 *       .addAction(R.drawable.ic_export, "Export") { doExport() }
 *       .addAction(R.drawable.ic_trash,  "Delete") { doDelete() }
 *       .show()
 *
 * Design invariants:
 *   - No Material Components, no elevation, no shadow, no animation
 *   - shape_bordered window background applied after show()
 *   - Tapping outside dismisses (AlertDialog default)
 *   - When a title is provided, an X close button appears in the upper-right of the title row
 *   - 1dp inkBlack dividers between items
 *   - Title row is a pinned header; the action list scrolls (height-capped) when it would otherwise
 *     run off a short screen — so no item is ever clipped unreachable (e.g. BOOX Go 6)
 */
class ActionSheetDialog(private val context: Context) {

    private data class Action(
        val iconRes: Int?,
        val label: String,
        val onClick: () -> Unit,
    )

    private var title: String? = null
    private val actions = mutableListOf<Action>()
    private var touchOutsideDismisses: Boolean = true

    fun title(text: String): ActionSheetDialog {
        title = text
        return this
    }

    fun addAction(iconRes: Int?, label: String, onClick: () -> Unit): ActionSheetDialog {
        actions.add(Action(iconRes, label, onClick))
        return this
    }

    /** Prevent touch-outside from dismissing — use for mandatory choices. X button and Back still work. */
    fun canceledOnTouchOutside(value: Boolean): ActionSheetDialog {
        touchOutsideDismisses = value
        return this
    }

    fun show() {
        val density = context.resources.displayMetrics.density
        val inkBlack = ContextCompat.getColor(context, R.color.inkBlack)

        val padH = (16 * density).toInt()
        val padV = (14 * density).toInt()
        val iconSize = (24 * density).toInt()
        val iconGap = (12 * density).toInt()
        val dividerH = (1 * density).toInt()

        // Root holds a fixed title header (if any) + a scrollable action list, so a tall menu on a
        // short screen scrolls instead of clipping items off the bottom (e.g. Export on the BOOX Go 6).
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Action rows live inside a height-capped ScrollView. A short menu wraps to its content
        // (compact); a tall one caps at [maxScrollH] and scrolls internally.
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        var dialog: AlertDialog? = null

        // ── Optional title row with X close button ─────────────────────────────
        val t = title
        if (t != null) {
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
            }

            val titleView = AppCompatTextView(context).apply {
                text = t
                textSize = 16f
                setTextColor(inkBlack)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            titleRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val closeBtn = AppCompatImageView(context).apply {
                setImageResource(R.drawable.ic_x)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                background = ColorDrawable(Color.TRANSPARENT)
                setOnClickListener { dialog?.dismiss() }
            }
            titleRow.addView(closeBtn, LinearLayout.LayoutParams(iconSize, iconSize))

            // Title stays pinned above the scroll area.
            root.addView(titleRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            root.addView(makeDivider(context, inkBlack, dividerH))
        }

        // ── Action rows ────────────────────────────────────────────────────────
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                container.addView(makeDivider(context, inkBlack, dividerH))
            }

            val row = buildRow(context, action.iconRes, action.label, inkBlack, padH, padV, iconSize, iconGap) {
                dialog?.dismiss()
                action.onClick()
            }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // Cap the scrollable list so title + list always fit the screen (leaves room for the pinned
        // header + AlertDialog margins). Only bites when the content is genuinely taller than this.
        val maxScrollH = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = object : android.widget.ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val capped = MeasureSpec.makeMeasureSpec(maxScrollH, MeasureSpec.AT_MOST)
                super.onMeasure(widthSpec, capped)
            }
        }.apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER   // no e-ink overscroll glow/animation
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        dialog.setCanceledOnTouchOutside(touchOutsideDismisses)
        dialog.show()

        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
    }

    private fun makeDivider(context: Context, color: Int, heightPx: Int): View =
        View(context).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx,
            )
        }

    private fun buildRow(
        context: Context,
        iconRes: Int?,
        label: String,
        inkBlack: Int,
        padH: Int,
        padV: Int,
        iconSize: Int,
        iconGap: Int,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener { onClick() }

            if (iconRes != null) {
                val icon = AppCompatImageView(context).apply {
                    setImageResource(iconRes)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).also {
                    it.marginEnd = iconGap
                })
            } else {
                addView(android.widget.Space(context), LinearLayout.LayoutParams(iconSize, iconSize).also {
                    it.marginEnd = iconGap
                })
            }

            val tv = AppCompatTextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(inkBlack)
            }
            addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
