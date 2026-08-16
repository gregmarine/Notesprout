package com.symmetricalpalmtree.notesprout.core

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
import com.symmetricalpalmtree.notesprout.R

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

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        var dialog: AlertDialog? = null

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

            root.addView(titleRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            root.addView(makeDivider(context, inkBlack, dividerH))
        }

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

        val maxScrollH = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = object : android.widget.ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val capped = MeasureSpec.makeMeasureSpec(maxScrollH, MeasureSpec.AT_MOST)
                super.onMeasure(widthSpec, capped)
            }
        }.apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
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
