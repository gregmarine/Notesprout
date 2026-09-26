package com.symmetricalpalmtree.sketchcompanion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import com.symmetricalpalmtree.sketchcompanion.R

/** A row of equal-width latch buttons where exactly one is down (`isSelected`). */
class LatchRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    var onPick: ((Int) -> Unit)? = null
    private val gap = resources.getDimensionPixelSize(R.dimen.panel_gap)
    private val size = resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

    init { orientation = HORIZONTAL }

    /** Rebuilds the row from [labels]; [decorate] may restyle each button (the swatches do). */
    fun setItems(labels: List<CharSequence>, decorate: ((AppCompatButton, Int) -> Unit)? = null) {
        removeAllViews()
        labels.forEachIndexed { i, label ->
            val b = AppCompatButton(context, null, 0).apply {
                setTextAppearance(context, 0)
                text = label
                contentDescription = label
                isAllCaps = false
                background = context.getDrawable(R.drawable.bg_selectable_card)
                setTextColor(context.getColor(R.color.inkBlack))
                textSize = 13f
                gravity = Gravity.CENTER
                minHeight = 0; minimumHeight = 0
                minWidth = 0; minimumWidth = 0
                setPadding(gap / 2, 0, gap / 2, 0)
                stateListAnimator = null
                setOnClickListener { select(i); onPick?.invoke(i) }
            }
            decorate?.invoke(b, i)
            addView(b, LayoutParams(0, size, 1f).apply {
                if (i > 0) marginStart = gap
            })
        }
    }

    fun select(index: Int) {
        for (i in 0 until childCount) getChildAt(i).isSelected = i == index
    }

    fun button(index: Int): AppCompatButton? = getChildAt(index) as? AppCompatButton

    fun show(shown: Boolean) { visibility = if (shown) View.VISIBLE else View.GONE }
}
