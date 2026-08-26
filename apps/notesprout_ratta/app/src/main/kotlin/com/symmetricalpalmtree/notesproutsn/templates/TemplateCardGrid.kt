package com.symmetricalpalmtree.notesproutsn.templates

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.library.GridMath

/**
 * The Templates screen's card grid (arc 13 / G1) — the library's shape for templates: measured
 * against the real band once, paginated, never scrolling, the same [GridMath] and the same card
 * footprint, so a template card, a notebook card and a page card are one object on screen.
 *
 * Like its two siblings it is deliberately dumb about *content*: the host supplies each card's
 * miniature (already rendered, off Main) and decides what a tap or a long-press means. What lives
 * here is geometry — the one thing all three grids genuinely share, and it is already extracted
 * into [GridMath], which is why this stays a few dozen lines rather than a copy of anything.
 *
 * [bind] removes only the `GridLayout` it added last, because the empty state is a sibling inside
 * the same container.
 */
class TemplateCardGrid(
    private val container: ViewGroup,
    private val onTap: (TemplateCard) -> Unit,
    private val onLongPress: ((TemplateCard) -> Unit)? = null,
) {
    private var columns = 1
    private var gap = 0
    private var cardHeight = 0

    /** The width one card gets — also the miniature's render width ([TemplateThumbnails]). */
    var cardWidth = 0
        private set

    var cardsPerPage = 1
        private set

    private var currentGrid: View? = null

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
     * Draw the [pageIndex] slice of [items]. [art] supplies a card's miniature by id; a missing
     * entry just means "not rendered", and the card shows an empty band rather than a wrong one.
     * Folders take the library's own folder card — a place looks like a place everywhere.
     *
     * [ticked] are the card ids showing the paper in force (arc 13 / G3). A **set**, not one id: two
     * library rows can hold the same picture, and both are honestly the paper the page is using.
     * [pinned] (G5) is resolved once per refresh from the pinned list, so no card asks on its own.
     */
    fun bind(
        items: List<TemplateCard>,
        pageIndex: Int,
        art: Map<String, Bitmap?>,
        ticked: Set<String> = emptySet(),
        pinned: Set<String> = emptySet(),
    ) {
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
            val isFolder = item is TemplateCard.Folder || item is TemplateCard.Defaults
            val view = if (isFolder) folderCard(inflater, item)
                       else templateCard(inflater, item, art[item.id], item.id in ticked, item.id in pinned)
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

    private fun folderCard(inflater: LayoutInflater, item: TemplateCard): View =
        inflater.inflate(R.layout.card_folder, container, false).apply {
            findViewById<TextView>(R.id.folderName).text = item.name
        }

    private fun templateCard(
        inflater: LayoutInflater,
        item: TemplateCard,
        art: Bitmap?,
        ticked: Boolean,
        pinned: Boolean,
    ): View = inflater.inflate(R.layout.card_template, container, false).apply {
        findViewById<TextView>(R.id.templateName).text = item.name
        findViewById<ImageView>(R.id.templatePreview).setImageBitmap(art)
        findViewById<ImageView>(R.id.templateTick).visibility = if (ticked) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.pinBadge).visibility = if (pinned) View.VISIBLE else View.GONE
    }
}
