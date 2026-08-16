package com.symmetricalpalmtree.notesprout.library

import android.content.Context
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Bitmaps
import com.symmetricalpalmtree.notesprout.data.index.ObjectSummary
import com.symmetricalpalmtree.notesprout.data.index.ObjectType
import java.util.Date

sealed class CardItem(val summary: ObjectSummary) {
    class Folder(s: ObjectSummary) : CardItem(s)
    /**
     * [pinned] shows the corner badge; [subtitle] overrides the modified-date line (Recents mode uses
     * it for the parent-folder name).
     */
    class Notebook(
        s: ObjectSummary,
        val coverBytes: ByteArray?,
        val pinned: Boolean = false,
        val subtitle: String? = null,
    ) : CardItem(s)
}

class LibraryGrid(
    private val container: ViewGroup,
    private val onTap: (CardItem) -> Unit,
    private val onLongPress: (CardItem) -> Unit,
) {
    private var columns = 0
    private var rows = 0
    var cardsPerPage = 1
        private set

    private var gap = 0

    /** The GridLayout we added last — removed on the next bind so the container's [R.id.emptyState] survives. */
    private var currentGrid: View? = null

    fun measure(context: Context, containerWidth: Int, containerHeight: Int) {
        val density = context.resources.displayMetrics.density
        gap = (8 * density).toInt()
        val widthDp = containerWidth / density
        columns = if (widthDp >= 480) 3 else 2
        val cardW = containerWidth / columns
        val cardH = (cardW * 1.4f).toInt()
        rows = (containerHeight / cardH).coerceAtLeast(1)
        cardsPerPage = columns * rows
    }

    fun bind(items: List<CardItem>, pageIndex: Int) {
        currentGrid?.let { container.removeView(it) }
        currentGrid = null
        if (items.isEmpty()) return
        val start = pageIndex * cardsPerPage
        val end = minOf(start + cardsPerPage, items.size)
        if (start >= items.size) return

        val context = container.context
        val grid = GridLayout(context).apply {
            columnCount = columns
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val inflater = LayoutInflater.from(context)
        val containerWidth = container.width
        val half = gap / 2
        val cardW = containerWidth / columns - gap
        val cardH = (cardW * 1.4f).toInt()

        for (i in start until end) {
            val item = items[i]
            val view = when (item) {
                is CardItem.Folder -> bindFolder(inflater, item, context)
                is CardItem.Notebook -> bindNotebook(inflater, item, context)
            }
            val lp = GridLayout.LayoutParams().apply {
                width = cardW
                height = cardH
                setMargins(half, half, half, half)
            }
            view.layoutParams = lp
            view.setOnClickListener { onTap(item) }
            view.setOnLongClickListener { onLongPress(item); true }
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }

    private fun bindFolder(inflater: LayoutInflater, item: CardItem.Folder, context: Context): View {
        val view = inflater.inflate(R.layout.card_folder, null)
        view.findViewById<TextView>(R.id.folderName).text = item.summary.name
        return view
    }

    private fun bindNotebook(inflater: LayoutInflater, item: CardItem.Notebook, context: Context): View {
        val view = inflater.inflate(R.layout.card_notebook, null)
        val coverImage = view.findViewById<ImageView>(R.id.coverImage)
        val nameView = view.findViewById<TextView>(R.id.cardName)
        val dateView = view.findViewById<TextView>(R.id.cardDate)
        val pinBadge = view.findViewById<ImageView>(R.id.pinBadge)

        nameView.text = item.summary.name

        dateView.text = item.subtitle ?: run {
            val dateFmt = DateFormat.getMediumDateFormat(context)
            val timeFmt = DateFormat.getTimeFormat(context)
            val d = Date(item.summary.updatedAt)
            "${dateFmt.format(d)} ${timeFmt.format(d)}"
        }

        pinBadge.visibility = if (item.pinned) View.VISIBLE else View.GONE

        val bmp = Bitmaps.decodeBounded(item.coverBytes, COVER_DECODE_EDGE)
        if (bmp != null) coverImage.setImageBitmap(bmp)

        return view
    }

    private companion object {
        /** Covers are stored at ≤ 512 px long edge; decode is bounded regardless of what the blob says. */
        const val COVER_DECODE_EDGE = 512
    }
}
