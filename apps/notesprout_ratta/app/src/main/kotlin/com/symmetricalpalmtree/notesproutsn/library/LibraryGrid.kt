package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Bitmaps
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import java.util.Date

/** What a card stands for. Folders and notebooks share the grid but not the card layout. */
sealed class CardItem(val summary: ObjectSummary) {
    class Folder(s: ObjectSummary) : CardItem(s)
    class Notebook(s: ObjectSummary) : CardItem(s)
}

/**
 * Renders one page of cards into [container] — used by the library and, folders-only, by the move
 * picker.
 *
 * The grid is **measured against the real band, once**, and never scrolls: [measure] asks
 * [GridMath] how many whole cards fit the container it was actually given, and [bind] draws
 * exactly that slice. Nothing here decides what is on the page; the host owns the listing, the
 * page index and the covers.
 *
 * **The empty-state view is a sibling inside [container].** [bind] removes only the `GridLayout` it
 * added last — a `removeAllViews()` here would delete the host's empty message and no folder would
 * ever look empty again once a card had rendered.
 */
class LibraryGrid(
    private val container: ViewGroup,
    private val onTap: (CardItem) -> Unit,
    private val onLongPress: ((CardItem) -> Unit)? = null,
) {
    private var columns = 1
    private var gap = 0
    private var cardWidth = 0
    private var cardHeight = 0

    var cardsPerPage = 1
        private set

    private var currentGrid: View? = null

    /** Measure against the container's real size. Call once it has been laid out (width > 0). */
    fun measure(context: Context, containerWidth: Int, containerHeight: Int) {
        val res = context.resources
        val minCardWidth = res.getDimensionPixelSize(R.dimen.library_card_min_width)
        gap = res.getDimensionPixelSize(R.dimen.library_card_gap)
        columns = GridMath.columns(containerWidth, minCardWidth)
        cardsPerPage = GridMath.cardsPerPage(containerWidth, containerHeight, minCardWidth)
        cardWidth = (GridMath.cardWidthPx(containerWidth, minCardWidth) - gap).coerceAtLeast(1)
        cardHeight = (cardWidth * GridMath.CARD_ASPECT).toInt().coerceAtLeast(1)
    }

    /**
     * Draw the [pageIndex] slice of [items]. [covers] supplies a notebook's cover blob by id; the
     * host fetches covers for the visible page only, so a missing entry just means "not fetched"
     * and the card falls back to its template-kind placeholder.
     */
    fun bind(items: List<CardItem>, pageIndex: Int, covers: Map<String, ByteArray?>) {
        currentGrid?.let { container.removeView(it) }
        currentGrid = null
        val range = GridMath.pageRange(pageIndex, cardsPerPage, items.size)
        if (range.isEmpty()) return

        val context = container.context
        val inflater = LayoutInflater.from(context)
        val grid = GridLayout(context).apply {
            columnCount = columns
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val half = gap / 2

        for (i in range) {
            val item = items[i]
            val view = when (item) {
                is CardItem.Folder -> folderCard(inflater, item)
                is CardItem.Notebook -> notebookCard(inflater, context, item, covers[item.summary.id])
            }
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = cardHeight
                setMargins(half, half, half, half)
            }
            view.setOnClickListener { onTap(item) }
            onLongPress?.let { handler -> view.setOnLongClickListener { handler(item); true } }
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }

    private fun folderCard(inflater: LayoutInflater, item: CardItem.Folder): View =
        inflater.inflate(R.layout.card_folder, container, false).apply {
            findViewById<TextView>(R.id.folderName).text = item.summary.name
        }

    private fun notebookCard(
        inflater: LayoutInflater,
        context: Context,
        item: CardItem.Notebook,
        coverBytes: ByteArray?,
    ): View {
        val view = inflater.inflate(R.layout.card_notebook, container, false)
        val s = item.summary
        view.findViewById<TextView>(R.id.cardName).text = s.name

        // App-locale date + time, not a hand-rolled format — the device's own convention.
        val d = Date(s.updatedAt)
        view.findViewById<TextView>(R.id.cardDate).text =
            "${DateFormat.getMediumDateFormat(context).format(d)} ${DateFormat.getTimeFormat(context).format(d)}"

        val cover = view.findViewById<ImageView>(R.id.coverImage)
        val bmp = Bitmaps.decodeBounded(coverBytes, COVER_DECODE_EDGE)
        if (bmp != null) {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(bmp)
        } else {
            // No snapshot yet (never opened, or R3 hasn't written one): show what the paper looks
            // like. Blank stays blank — an empty card is the honest picture of a blank notebook.
            cover.setImageBitmap(
                BuiltInTemplates.placeholder(
                    kindOf(s.templateKind),
                    cardWidth,
                    (cardHeight * COVER_BAND_FRACTION).toInt(),
                    context.resources.displayMetrics.density,
                )
            )
        }
        return view
    }

    private fun kindOf(label: String?): TemplateKind =
        runCatching { TemplateKind.valueOf(label ?: "") }.getOrDefault(TemplateKind.BLANK)

    private companion object {
        /** Covers are written small; decode is bounded regardless of what the blob claims. */
        const val COVER_DECODE_EDGE = 512

        /** Roughly how much of a card's height the cover band gets (the rest is name + date). */
        const val COVER_BAND_FRACTION = 0.75f
    }
}
