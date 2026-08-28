package com.symmetricalpalmtree.notesproutsn.export

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The Export screen's four kinds of row, built in code because the row **count** is the content —
 * how many exporters are installed, how many choices each option declares. Same reasoning (and the
 * same e-ink rules) as [com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog]: no ripple, no
 * elevation, inkBlack only, tick marks in a column that always exists so labels line up.
 *
 * The radio row's look is `Widget.Notesprout.RadioButton`'s, set field by field: a *style* cannot be
 * applied to a view constructed in code (a `ContextThemeWrapper` applies themes, not styles), and
 * the alternative — a one-row layout file per kind — would be four files to keep in step with one
 * another. The constants below are the style's, and they are the only place they are repeated.
 */
class ExportPanel(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, R.color.inkBlack)
    private val padV = (8 * density).toInt()
    private val iconSize = (24 * density).toInt()
    private val iconGap = (12 * density).toInt()

    /** A section heading — inkBlack made *smaller*, never inkLight: it names what follows. */
    fun caption(text: String): TextView = AppCompatTextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(ink)
        setPadding(0, (12 * density).toInt(), 0, 0)
        layoutParams = wrapRow()
    }

    /**
     * A value with no control behind it — the collapsed chooser when one exporter is installed, and
     * a single-choice option with exactly one choice. A radio for either would be a control that
     * cannot be operated, which reads as broken rather than as settled.
     */
    fun value(text: String): TextView = AppCompatTextView(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(ink)
        setPadding(0, padV, 0, padV)
        layoutParams = wrapRow()
    }

    /** One of several — the family's radio, checked by state, never by colour. */
    fun choice(text: String, checked: Boolean, onPick: () -> Unit): View =
        AppCompatRadioButton(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(ink)
            buttonDrawable = ContextCompat.getDrawable(context, R.drawable.radio_selector)
            background = ColorDrawable(Color.TRANSPARENT)
            stateListAnimator = null
            isChecked = checked
            // A click listener, not a checked-change one: the panel is re-rendered whole after
            // every pick, and a change listener would fire again as that render sets the state.
            setOnClickListener { onPick() }
            setPadding(padV, padV, padV, padV)
            layoutParams = wrapRow()
        }

    /** On/off, as the tick the sheets already use — the column exists whether or not it is filled. */
    fun toggle(text: String, on: Boolean, onToggle: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, padV, 0, padV)
        isClickable = true
        isFocusable = true
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { onToggle() }
        addView(
            AppCompatImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // INVISIBLE, not GONE: the label must not step sideways as the tick comes and goes.
                visibility = if (on) View.VISIBLE else View.INVISIBLE
                contentDescription = null
            },
            LinearLayout.LayoutParams(iconSize, iconSize).also { it.marginEnd = iconGap },
        )
        addView(
            AppCompatTextView(context).apply {
                this.text = text
                textSize = 16f
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        layoutParams = wrapRow()
    }

    private fun wrapRow() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
