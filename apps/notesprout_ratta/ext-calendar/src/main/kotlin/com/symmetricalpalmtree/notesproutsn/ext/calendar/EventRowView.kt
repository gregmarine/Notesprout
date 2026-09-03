package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * One line of the events band, built in code (arc 24 / Z2) — `TagRowView`'s idiom, for its reason:
 * the row *count* is the content, and a layout file here would only ever be inflated in a loop.
 *
 * Two shapes, and [EventsPaging] measures the band against exactly the heights these build to, so
 * the arithmetic and the glass cannot disagree about where a page ends:
 *
 * - a **section header** — one short label over the rows it names;
 * - an **event row** — two lines. The first is a fixed-width badge ("All day", "9:00 AM", "In 3
 *   days") and the title; the badge's `minWidth` is what makes every title on the page start on the
 *   same vertical line, which is the whole reason a badge column exists rather than a prefix.
 *   The second is the meta line.
 *
 * **The second line is inkBlack and smaller, never inkLight.** It carries information — what the
 * event is, when it ends, how it repeats — and the palette reserves the grey for text meant *not*
 * to be read. Secondary is said with size here, as everywhere else in the app.
 *
 * E-ink rules baked in: no ripple, no elevation, a 1 dp inkBlack hairline under each row because
 * borderGray is invisible on the panel.
 */
object EventRowView {

    /** The height an event row takes — the family's hand-sized tap target plus a second line, and
     *  never a hardcoded number: it grows with `toolbar_button_size` on the tablet tier like every
     *  other control. */
    fun rowHeightPx(context: Context): Int {
        val d = context.resources.displayMetrics.density
        return context.resources.getDimensionPixelSize(R.dimen.toolbar_button_size) +
            (20 * d).toInt() + hairline(context)
    }

    /** The height a section header takes. Deliberately shorter than a row — a label is not a tap
     *  target, and the band is worth more to the events than to their names. */
    fun headerHeightPx(context: Context): Int =
        (34 * context.resources.displayMetrics.density).toInt()

    /** A section label: "Today" / "Upcoming". No divider under it — the rows it names follow
     *  immediately, and a line between a label and its own list would read as a separation. */
    fun buildHeader(context: Context, label: String): View {
        val d = context.resources.displayMetrics.density
        return AppCompatTextView(context).apply {
            text = label
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM
            setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                headerHeightPx(context),
            )
        }
    }

    /**
     * One event row: [badge] and [title] on the first line, [meta] on the second, the whole row one
     * tap target that opens the editor. **No long press** — an event has one destructive verb and it
     * lives inside the editor, where it can name what it is about to remove.
     */
    fun buildEvent(
        context: Context,
        badge: String,
        title: String,
        meta: String,
        onClick: () -> Unit,
    ): View {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)
        val hairline = hairline(context)

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            isClickable = true
            isFocusable = false   // a chrome row must never take focus off a field
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

        val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val first = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        first.addView(
            AppCompatTextView(context).apply {
                text = badge
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minWidth = (72 * d).toInt()
                setTextColor(ink)
            },
        )
        first.addView(
            AppCompatTextView(context).apply {
                text = title
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        lines.addView(first, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        lines.addView(
            AppCompatTextView(context).apply {
                text = meta
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ink)
            },
        )
        row.addView(lines, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        column.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeightPx(context) - hairline),
        )
        column.addView(
            View(context).apply { setBackgroundColor(ink) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline),
        )
        return column
    }

    private fun hairline(context: Context): Int =
        context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
}
