package com.symmetricalpalmtree.notesproutsn.core

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.screen.R

/**
 * A list of choices, drawn as full-width rows separated by 1 dp inkBlack lines — the app's "what do
 * you want to do with this?" surface (long-press an item, pick a sort order).
 *
 * Built in code rather than as a layout because the row *count* is the content. E-ink rules baked
 * in: no ripple (transparent backgrounds), no elevation, hairline dividers in inkBlack because
 * borderGray is invisible on the panel. Icons are optional — the sort sheet passes null on every
 * row except the active one, so the check mark sits in a column that already exists.
 *
 * Tall sheets scroll, capped at 72 % of the screen so the dialog never eats the whole display.
 */
class ActionSheetDialog(private val context: Context) {

    private data class Action(val iconRes: Int?, val label: String, val onClick: () -> Unit)

    private var title: String? = null
    private val actions = mutableListOf<Action>()

    fun title(text: String): ActionSheetDialog = apply { title = text }

    fun addAction(iconRes: Int?, label: String, onClick: () -> Unit): ActionSheetDialog =
        apply { actions.add(Action(iconRes, label, onClick)) }

    fun show() {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)
        val padH = (16 * d).toInt()
        val padV = (14 * d).toInt()
        val iconSize = (24 * d).toInt()
        val iconGap = (12 * d).toInt()
        val hairline = (1 * d).toInt().coerceAtLeast(1)

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        var dialog: AlertDialog? = null

        title?.let { text ->
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
            }
            titleRow.addView(
                AppCompatTextView(context).apply {
                    this.text = text
                    textSize = 16f
                    setTextColor(ink)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            titleRow.addView(
                AppCompatImageView(context).apply {
                    setImageResource(R.drawable.ic_x)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    isFocusable = true
                    background = ColorDrawable(Color.TRANSPARENT)
                    contentDescription = context.getString(R.string.cancel)
                    setOnClickListener { dialog?.dismiss() }
                },
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
            root.addView(titleRow, matchWrap())
            root.addView(divider(ink, hairline))
        }

        actions.forEachIndexed { index, action ->
            if (index > 0) rows.addView(divider(ink, hairline))
            rows.addView(
                row(action, ink, padH, padV, iconSize, iconGap) {
                    dialog?.dismiss()
                    action.onClick()
                },
                matchWrap(),
            )
        }

        val cap = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }.apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows, matchWrap())
        }
        root.addView(scroll, matchWrap())

        dialog = Dialogs.style(AlertDialog.Builder(context).setView(root).create())
        dialog.show()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun divider(color: Int, heightPx: Int): View = View(context).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
    }

    private fun row(
        action: Action,
        ink: Int,
        padH: Int,
        padV: Int,
        iconSize: Int,
        iconGap: Int,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(padH, padV, padH, padV)
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { onClick() }

        // A row with no icon still reserves the column, so labels line up down the sheet.
        val leading = action.iconRes?.let {
            AppCompatImageView(context).apply {
                setImageResource(it)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            }
        } ?: Space(context)
        addView(leading, LinearLayout.LayoutParams(iconSize, iconSize).also { it.marginEnd = iconGap })

        addView(
            AppCompatTextView(context).apply {
                text = action.label
                textSize = 16f
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }
}
