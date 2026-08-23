package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.library.GridMath

/**
 * The link picker's page grid (arc 6 / K2) — [com.symmetricalpalmtree.notesproutsn.library.LibraryGrid]'s
 * shape for pages instead of notebooks: measured against the real band once, paginated, never
 * scrolling, same [GridMath] and the same card footprint, so a page card and a notebook card are
 * the same size object on screen.
 *
 * It is deliberately dumb. It knows nothing about previews, labels, sources or selection *rules*:
 * the host binds each card through [bind]'s callback, which is also where the async preview is
 * kicked off. That keeps the one thing this class owns — geometry — separable from the one thing
 * the picker owns: what a card says.
 *
 * Like the library grid, [bind] removes only the `GridLayout` it added last, because the empty
 * state is a sibling inside the same container; and binding an empty list is how the *other* grid
 * (browse) clears this one when the mode changes.
 */
class PageCardGrid(
    private val container: ViewGroup,
    private val onTap: (PickerPage) -> Unit,
) {
    private var columns = 1
    private var gap = 0
    private var cardHeight = 0

    /** The width one card gets — also the preview bitmap's width ([PreviewMath.renderSize]). */
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
     * Draw the [pageIndex] slice of [items] — each a page with its 1-based position in the whole
     * notebook ([LinkPickerModel.pageCards]). [onBind] fills one card in: it is handed the card
     * root (already tagged with the page id, so a late async result can check it is still the same
     * card) and the page it stands for.
     */
    fun bind(
        items: List<Pair<PickerPage, Int>>,
        pageIndex: Int,
        selectedPageId: String?,
        onBind: (View, PickerPage, Int) -> Unit,
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
            val (page, position) = items[i]
            val view = inflater.inflate(R.layout.card_page, container, false)
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = cardHeight
                setMargins(half, half, half, half)
            }
            view.tag = page.id
            view.isSelected = page.id == selectedPageId
            view.setOnClickListener { onTap(page) }
            onBind(view, page, position)
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }
}
