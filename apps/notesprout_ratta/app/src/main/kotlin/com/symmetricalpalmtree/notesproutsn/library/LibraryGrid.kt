package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import android.text.TextUtils
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
import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.template.BuiltInTemplates
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import java.util.Date

/**
 * The card-level rules that are pure arithmetic on an index row — kept out of [LibraryGrid] so they
 * can be reasoned about (and tested) without a `View` in sight.
 */
object LibraryCards {

    /**
     * Does this notebook's `flags` carry [NotebookFlags.TEXT_DOCUMENT]?
     *
     * A null or absent value is **not** a text document: every row this predicate sees was written
     * by a build that sets the bit when it means it, and an unreadable one is a handwritten
     * notebook — the family's default, and the only safe way to be wrong.
     */
    fun isTextDocument(flags: Int?): Boolean = ((flags ?: 0) and NotebookFlags.TEXT_DOCUMENT) != 0
}

/** What a card stands for. Folders and notebooks share the grid but not the card layout. */
sealed class CardItem(val summary: ObjectSummary) {

    /**
     * @param subtitle a second line under the name. Only the search shelf sets it (the parent
     *   folder's name): that shelf is flat, so two folders called "Notes" in two different places
     *   would otherwise be the same card twice. The tree, the move picker and the link picker all
     *   pass nothing and the line is GONE, exactly as before.
     */
    class Folder(s: ObjectSummary, val subtitle: String? = null) : CardItem(s)

    /**
     * @param pinned draws the pin badge — the library resolves it once per refresh from the pinned
     *   list, so no card ever asks the index on its own.
     * @param subtitle replaces the last-modified line when set. Recents uses it for the parent
     *   folder name: on that shelf "where is it" is the useful second line, not "when".
     * @param locked draws a **lock in the cover's place** and fetches no thumbnail (arc 26 / U4,
     *   decision 11). In the library it is simply `KeyScope.of(summary.keyScope) == NOTEBOOK`: a
     *   notebook-scoped notebook shows a lock whatever this process has unlocked, because the index
     *   holds no cover for it to show instead. The link picker sets it `!NotebookUnlocks.has(id)`,
     *   so a notebook unlocked there this process reads as an ordinary (coverless) card again.
     */
    class Notebook(
        s: ObjectSummary,
        val pinned: Boolean = false,
        val subtitle: String? = null,
        val locked: Boolean = false,
    ) : CardItem(s)

    /**
     * One **page** of a notebook, found by a tag on it (arc 21 / W4) — the search shelf's card, and
     * nowhere else's.
     *
     * [summary] is the **notebook's**, not the page's: pages have no index row, and the card wants
     * the notebook's cover anyway (the covers map is keyed by notebook id, so two page hits from one
     * notebook cost one fetch between them). What makes the card a page is [pageId], which is what a
     * tap opens the notebook at.
     *
     * @param pageLabel "Page 3" — resolved host-side against the notebook's live page list.
     * @param subtitle the parent folder and the tag that matched, already joined.
     */
    class Page(
        s: ObjectSummary,
        val pageId: String,
        val pageLabel: String,
        val subtitle: String,
    ) : CardItem(s)
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
     *
     * [selectedId] marks one card as chosen — a thicker border, the card background's own
     * `state_selected` (never a colour, never a grey). It exists for the link picker, where
     * browsing *is* choosing; the library and the move picker pass nothing and every card stays
     * unselected exactly as before.
     */
    fun bind(
        items: List<CardItem>,
        pageIndex: Int,
        covers: Map<String, ByteArray?>,
        selectedId: String? = null,
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
            val view = when (item) {
                is CardItem.Folder -> folderCard(inflater, item)
                is CardItem.Notebook -> notebookCard(inflater, context, item, covers[item.summary.id])
                is CardItem.Page -> pageCard(inflater, context, item, covers[item.summary.id])
            }
            view.layoutParams = GridLayout.LayoutParams().apply {
                width = cardWidth
                height = cardHeight
                setMargins(half, half, half, half)
            }
            view.isSelected = selectedId != null && item.summary.id == selectedId
            view.setOnClickListener { onTap(item) }
            // A page card gets no long-press (arc 21 / W4): the action sheet acts on a notebook —
            // rename, move, delete — and firing it from a card that names a page would act on
            // something the card does not name.
            if (item !is CardItem.Page) {
                onLongPress?.let { handler -> view.setOnLongClickListener { handler(item); true } }
            }
            grid.addView(view)
        }
        container.addView(grid)
        currentGrid = grid
    }

    private fun folderCard(inflater: LayoutInflater, item: CardItem.Folder): View =
        inflater.inflate(R.layout.card_folder, container, false).apply {
            findViewById<TextView>(R.id.folderName).text = item.summary.name
            findViewById<TextView>(R.id.folderParent).apply {
                text = item.subtitle
                visibility = if (item.subtitle == null) View.GONE else View.VISIBLE
            }
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

        // A subtitle (Recents: the parent folder) takes the second line when there is one;
        // otherwise the app-locale date + time — the device's own convention, not a hand-rolled
        // format.
        val d = Date(s.updatedAt)
        view.findViewById<TextView>(R.id.cardDate).text = item.subtitle
            ?: "${DateFormat.getMediumDateFormat(context).format(d)} ${DateFormat.getTimeFormat(context).format(d)}"

        view.findViewById<View>(R.id.pinBadge).visibility = if (item.pinned) View.VISIBLE else View.GONE
        if (item.locked) paintLock(view) else paintCover(view, context, s, coverBytes)
        return view
    }

    /**
     * A page found by a tag (arc 21 / W4): the notebook's cover, `<Notebook> · Page N`, and the
     * folder and tag beneath.
     *
     * **The notebook name is ellipsized, not the line.** `cardName` is `singleLine`, so letting the
     * whole title fall off the end would eat the page number — the one part of the card that says
     * which page this is. The name is cut to whatever is left after the suffix is measured, so
     * "· Page 3" always survives. No pin badge: a page is not a notebook and cannot be pinned.
     */
    private fun pageCard(
        inflater: LayoutInflater,
        context: Context,
        item: CardItem.Page,
        coverBytes: ByteArray?,
    ): View {
        val view = inflater.inflate(R.layout.card_notebook, container, false)
        val name = view.findViewById<TextView>(R.id.cardName)
        val suffix = PAGE_SEPARATOR + item.pageLabel
        val available = cardWidth - view.paddingStart - view.paddingEnd -
            name.paddingStart - name.paddingEnd
        val room = available - name.paint.measureText(suffix)
        val head = TextUtils.ellipsize(
            item.summary.name, name.paint, room.coerceAtLeast(0f), TextUtils.TruncateAt.END,
        )
        name.text = head.toString() + suffix

        view.findViewById<TextView>(R.id.cardDate).text = item.subtitle
        view.findViewById<View>(R.id.pinBadge).visibility = View.GONE
        paintCover(view, context, item.summary, coverBytes)
        return view
    }

    /**
     * The lock that stands **in place of** a cover for a notebook-scoped notebook (arc 26 / U4,
     * decision 11) — a padlock on blank paper, the name and date line unchanged beneath it.
     *
     * Not a badge over a thumbnail: the index deliberately holds no cover for such a notebook (a
     * cover is a picture of its contents, and those are the locked thing), so there is nothing to
     * badge. The caller fetches no blob for it either. The glyph is inkBlack stroke on paperWhite,
     * inset to roughly a third of the card's width — big enough to read across a grid on e-ink,
     * small enough to still look like a mark on a page rather than a picture filling it.
     *
     * No content description: the card already carries the notebook's name, and a card is not a
     * button.
     */
    private fun paintLock(view: View) {
        val cover = view.findViewById<ImageView>(R.id.coverImage)
        val inset = (cardWidth * LOCK_INSET_FRACTION).toInt()
        cover.setPadding(inset, inset, inset, inset)
        cover.scaleType = ImageView.ScaleType.FIT_CENTER
        cover.setImageResource(R.drawable.ic_lock)
    }

    /** The card's picture: the cover snapshot, else a text-document glyph, else the paper. Shared by
     *  the notebook card and the page card, which show the same notebook's cover. */
    private fun paintCover(view: View, context: Context, s: ObjectSummary, coverBytes: ByteArray?) {
        val cover = view.findViewById<ImageView>(R.id.coverImage)
        val bmp = Bitmaps.decodeBounded(coverBytes, COVER_DECODE_EDGE)
        if (bmp != null) {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setImageBitmap(bmp)
        } else if (LibraryCards.isTextDocument(s.flags)) {
            // A text document with nothing to show yet — or a cover that would not decode. Both the
            // create and the import render a text cover the moment the document exists, so this is
            // a safety net rather than a normal state; what it must not do is fall through to a
            // paper placeholder, which would picture the one thing a text document is not. The
            // glyph alone is the card's whole identity here — no badge, no label (arc 19 / M8).
            val inset = (cardWidth * GLYPH_INSET_FRACTION).toInt()
            cover.setPadding(inset, inset, inset, inset)
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            cover.setImageResource(R.drawable.ic_file_text)
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
    }

    private fun kindOf(label: String?): TemplateKind =
        runCatching { TemplateKind.valueOf(label ?: "") }.getOrDefault(TemplateKind.BLANK)

    private companion object {
        /** Covers are written small; decode is bounded regardless of what the blob claims. */
        const val COVER_DECODE_EDGE = 512

        /** Between the notebook's name and the page's, on a page card (arc 21 / W4). */
        const val PAGE_SEPARATOR = " · "

        /** Roughly how much of a card's height the cover band gets (the rest is name + date). */
        const val COVER_BAND_FRACTION = 0.75f

        /** White space each side of the coverless text-document glyph, as a fraction of the card's
         *  width — it leaves the icon at roughly 40% of the card, small enough to read as a mark on
         *  a page rather than as a picture that fills it. */
        const val GLYPH_INSET_FRACTION = 0.3f

        /** White space each side of the lock glyph, as a fraction of the card's width. The cover
         *  band is taller than it is wide, so the width is the constraint under `FIT_CENTER`: a
         *  third each side leaves the padlock at **a third of the card's width**, a shade larger
         *  than the text-document glyph because it is the card's only picture. */
        const val LOCK_INSET_FRACTION = 1f / 3f
    }
}
