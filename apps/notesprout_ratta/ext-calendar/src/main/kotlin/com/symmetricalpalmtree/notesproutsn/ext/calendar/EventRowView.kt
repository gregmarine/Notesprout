package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat

/**
 * One line of the events band, built in code (arc 24 / Z2) — `TagRowView`'s idiom, for its reason:
 * the row *count* is the content, and a layout file here would only ever be inflated in a loop.
 *
 * Two shapes, and [EventsPaging] measures the band against exactly the heights these build to, so
 * the arithmetic and the glass cannot disagree about where a page ends:
 *
 * - a **section header** — one short label over the rows it names;
 * - an **event row** — og's bordered card (arc 24 / Z5a), with the SN palette: 1 dp inkBlack
 *   border, 4 dp radius, a leading fixed-width badge ("All day", "9:00 AM", "In 3 days") and a
 *   vertical rule, then title (16 sp) over meta (13 sp), with the trash icon at the trailing edge.
 *   **Regular weight, not og's bold** — the user's call. The row *slot* is the card plus the 8 dp
 *   gap that sits above it, so the first card of a band or the first under a header always has air
 *   above it and the last card is flush at the band's bottom.
 *
 * **The second line is inkBlack and smaller, never inkLight.** It carries information — what the
 * event is, when it ends, how it repeats — and the palette reserves the grey for text meant *not*
 * to be read. Secondary is said with size here, as everywhere else in the app.
 *
 * E-ink rules baked in: no ripple, no elevation, the card's border is `shape_bordered` (`:sn-screen`)
 * rather than a hairline — borderGray is invisible on the panel and og's bordered-card shape reads
 * better than a rule under an unbordered row.
 */
object EventRowView {

    /** The card's own height — the family's hand-sized tap target (the trash icon, the tallest
     *  child) plus 12 dp of inner padding on both edges. Never a hardcoded number: it grows with
     *  `toolbar_button_size` on the tablet tier like every other control. */
    private fun cardHeightPx(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.toolbar_button_size) +
            (2 * 12 * context.resources.displayMetrics.density).toInt()

    /** The height a row's *slot* takes: the card plus the 8 dp gap that sits above it. */
    fun rowHeightPx(context: Context): Int =
        cardHeightPx(context) + (8 * context.resources.displayMetrics.density).toInt()

    /** The height a section header takes. Deliberately shorter than a row — a label is not a tap
     *  target, and the band is worth more to the events than to their names. */
    fun headerHeightPx(context: Context): Int =
        (34 * context.resources.displayMetrics.density).toInt()

    /** A section label: "Today" / "Upcoming". No divider under it — the rows it names follow
     *  immediately, and a line between a label and its own list would read as a separation. Side
     *  padding is 8 dp (the card's own outer margin) + 12 dp (the card's inner padding) = 20 dp, so
     *  the label's left edge lines up with the card's badge column. */
    fun buildHeader(context: Context, label: String): View {
        val d = context.resources.displayMetrics.density
        return AppCompatTextView(context).apply {
            text = label
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.BOTTOM
            setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
            setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (4 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                headerHeightPx(context),
            )
        }
    }

    /**
     * One event card: [badge] and the vertical rule lead, then [title] over [meta], a trash icon at
     * the trailing edge, and the rest of the card one tap target that opens the editor.
     *
     * **Delete is here, not in the editor** (the user's call, arc 24 / Z2 rebuild): the person is
     * already looking at the thing they mean, and the confirm dialog [onDelete] raises is what names
     * the blast radius. The icon is a child button, so its own tap is consumed there and the card's
     * click never fires with it.
     *
     * There is no long press on the card itself: the one destructive verb has a control of its own.
     */
    fun buildEvent(
        context: Context,
        badge: String,
        title: String,
        meta: String,
        onClick: () -> Unit,
        onDelete: () -> Unit,
    ): View {
        val d = context.resources.displayMetrics.density
        val ink = ContextCompat.getColor(context, R.color.inkBlack)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.shape_bordered)
            val pad = (12 * d).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = false   // a chrome card must never take focus off a field
            setOnClickListener { onClick() }
        }

        card.addView(
            AppCompatTextView(context).apply {
                text = badge
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ink)
            },
            LinearLayout.LayoutParams((72 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        card.addView(
            View(context).apply { setBackgroundColor(ink) },
            LinearLayout.LayoutParams(hairline(context), (32 * d).toInt()).apply {
                marginStart = (10 * d).toInt()
                marginEnd = (10 * d).toInt()
            },
        )

        val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        lines.addView(
            AppCompatTextView(context).apply {
                text = title
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ink)
            },
        )
        lines.addView(
            AppCompatTextView(context).apply {
                text = meta
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ink)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (2 * d).toInt() },
        )
        card.addView(lines, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val trashSize = context.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)
        card.addView(
            AppCompatImageButton(context).apply {
                setImageResource(R.drawable.ic_trash)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = context.resources.getDimensionPixelSize(R.dimen.toolbar_button_padding)
                setPadding(pad, pad, pad, pad)
                background = ColorDrawable(Color.TRANSPARENT)
                stateListAnimator = null      // no ripple, no lift: e-ink draws neither
                isFocusable = false           // chrome must never take focus off a field
                contentDescription = context.getString(R.string.cd_events_delete)
                TooltipCompat.setTooltipText(this, contentDescription)
                setOnClickListener { onDelete() }
                // Words read better than glyphs on e-ink: the long press says what the icon is.
                setOnLongClickListener {
                    Toast.makeText(context, R.string.cd_events_delete, Toast.LENGTH_SHORT).show()
                    true
                }
            },
            LinearLayout.LayoutParams(trashSize, trashSize).apply { marginStart = (8 * d).toInt() },
        )

        // The 8 dp gap sits ABOVE the card, never below: the first card of a band or the first
        // under a header always has air above it, and the last card is flush at the band's bottom.
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            cardHeightPx(context),
        ).apply {
            val margin = (8 * d).toInt()
            setMargins(margin, margin, margin, 0)
        }
        return card
    }

    private fun hairline(context: Context): Int =
        context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
}
