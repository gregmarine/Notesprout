package com.symmetricalpalmtree.notesproutsn.ext.document

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat

/**
 * Every tool on the format bar, in bar order, with the glyph and the hint that name it.
 *
 * The editor's model is always raw Markdown, so each of these writes exactly the characters a
 * writer would have typed by hand — none of them is a "rich text" state. The hint carries the
 * keyboard chord as well as the name, because an icon bar has no labels and a long-press is the
 * only place either can be learned.
 */
enum class FormatTool(val icon: Int, val hint: Int) {
    H1(R.drawable.ic_h_1, R.string.fmt_h1),
    H2(R.drawable.ic_h_2, R.string.fmt_h2),
    H3(R.drawable.ic_h_3, R.string.fmt_h3),
    BOLD(R.drawable.ic_bold, R.string.fmt_bold),
    ITALIC(R.drawable.ic_italic, R.string.fmt_italic),
    STRIKETHROUGH(R.drawable.ic_strikethrough, R.string.fmt_strikethrough),
    CODE(R.drawable.ic_code, R.string.fmt_code),
    QUOTE(R.drawable.ic_blockquote, R.string.fmt_quote),
    BULLET(R.drawable.ic_list, R.string.fmt_bullet),
    ORDERED(R.drawable.ic_list_numbers, R.string.fmt_ordered),
    TASK(R.drawable.ic_list_check, R.string.fmt_task),
    LINK(R.drawable.ic_link, R.string.fmt_link),
    IMAGE(R.drawable.ic_photo, R.string.fmt_image),
    RULE(R.drawable.ic_separator_horizontal, R.string.fmt_rule),
}

/**
 * Builds the format bar's buttons into an empty bar.
 *
 * The bar is built in code rather than in XML for one reason: [FormatBarOverflow] moves the real
 * views between the bar and the panel, and a layout that declared them would be describing a
 * arrangement that stops being true the moment the bar is narrower than its contents.
 *
 * Groups are separated by a 1dp × 28dp inkBlack rule — heading / inline / block / insertion, og's
 * four groups unchanged. The overflow controls are built last so they pin to the trailing edge, and
 * they are handed back to the caller because the overflow manager needs them by identity.
 */
object FormatBar {

    /** The overflow controls, which the caller passes to [FormatBarOverflow]. */
    class Controls(val dividerOverflow: View, val btnOverflow: View)

    fun build(
        bar: LinearLayout,
        onTool: (FormatTool) -> Unit,
        /** Using a tool puts the panel away: it opened to reach that tool, and its job is done. */
        onToolUsed: () -> Unit,
        onOverflow: () -> Unit,
    ): Controls {
        val context = bar.context
        fun tool(t: FormatTool) = bar.addView(
            iconButton(context, t.icon, context.getString(t.hint)) { onToolUsed(); onTool(t) },
        )
        fun divider() = bar.addView(groupDivider(context))

        tool(FormatTool.H1); tool(FormatTool.H2); tool(FormatTool.H3)
        divider()
        tool(FormatTool.BOLD); tool(FormatTool.ITALIC)
        tool(FormatTool.STRIKETHROUGH); tool(FormatTool.CODE)
        divider()
        tool(FormatTool.QUOTE); tool(FormatTool.BULLET)
        tool(FormatTool.ORDERED); tool(FormatTool.TASK)
        divider()
        tool(FormatTool.LINK); tool(FormatTool.IMAGE); tool(FormatTool.RULE)

        // Pinned at the trailing edge and hidden whenever everything fits. The overflow button is
        // the one control that does NOT dismiss the panel first — it would close then re-open.
        val dividerOverflow = groupDivider(context)
        val btnOverflow = iconButton(
            context, R.drawable.ic_dots, context.getString(R.string.fmt_more), onOverflow,
        )
        bar.addView(dividerOverflow)
        bar.addView(btnOverflow)
        return Controls(dividerOverflow, btnOverflow)
    }

    /**
     * The one icon button this bar builds: the tier's tap target (44dp under sw720dp, 62dp on a
     * tablet) around a 24dp Tabler glyph, the same dimens `Widget.Notesprout.ToolbarButton` uses —
     * never a hardcoded size.
     */
    fun iconButton(context: Context, icon: Int, hint: String, onClick: () -> Unit): AppCompatImageButton {
        val size = context.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        val inset = context.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
        return AppCompatImageButton(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.bg_toolbar_button)
            stateListAnimator = null
            setPadding(inset, inset, inset, inset)
            // Long-press names the tool and teaches its chord; the same string is what a screen
            // reader announces.
            contentDescription = hint
            TooltipCompat.setTooltipText(this, hint)
            // Never take focus: the editor must keep the caret and the selection the button acts on.
            isFocusable = false
            isFocusableInTouchMode = false
            // An exact px width is what the overflow manager measures against — WRAP_CONTENT is 0 there.
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(context, 2) }
            setOnClickListener { onClick() }
        }
    }

    /** A group separator — a plain [View], which is how [FormatBarOverflow] tells one from a tool. */
    private fun groupDivider(context: Context): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.inkBlack))
        layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 28)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(context, 6)
            marginEnd = dp(context, 6)
        }
    }

    /** dp → px, never rounding a 1dp rule away to nothing. */
    private fun dp(context: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt().coerceAtLeast(if (v > 0) 1 else 0)
}
