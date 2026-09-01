package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * One tag row, built in code (arc 21 / W1) — the `ActionSheetDialog` idiom, for the same reason: the
 * row *count* is the content, and a layout file would only be inflated in a loop.
 *
 * Every row is one hand-sized tap target ([R.dimen.toolbar_button_size] — never a hardcoded
 * height, and it grows with the tablet tier like every other), a label, and a trailing glyph that
 * says what tapping it does. E-ink rules baked in: no ripple, no elevation, a 1 dp inkBlack
 * hairline under each row because borderGray is invisible on the panel.
 */
object TagRowView {

    /** The height every row takes — what the pager's arithmetic measures the band against. */
    fun rowHeightPx(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.toolbar_button_size) +
            context.resources.displayMetrics.density.toInt().coerceAtLeast(1)

    /**
     * A row for [label], with [trailingIcon] naming the action ([onClick]) and an optional
     * [onLongClick]. [dim] draws the label in inkLight — used for nothing that carries information,
     * per the palette rule, so it is left to callers that have hint text.
     */
    fun build(
        context: Context,
        label: String,
        trailingIcon: Int?,
        trailingDescription: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        dim: Boolean = false,
    ): View {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)
        val hairline = d.toInt().coerceAtLeast(1)
        val iconSize = (24 * d).toInt()

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            isClickable = true
            isFocusable = false   // a chrome row must never steal focus from the add field
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        row.addView(
            AppCompatTextView(context).apply {
                text = label
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(
                    if (dim) ContextCompat.getColor(context, R.color.inkLight) else ink,
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (trailingIcon != null) {
            row.addView(
                AppCompatImageView(context).apply {
                    setImageResource(trailingIcon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = trailingDescription
                },
                LinearLayout.LayoutParams(iconSize, iconSize),
            )
        }
        column.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowHeightPx(context) - hairline,
            ),
        )
        column.addView(
            View(context).apply { setBackgroundColor(ink) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline),
        )
        return column
    }
}
