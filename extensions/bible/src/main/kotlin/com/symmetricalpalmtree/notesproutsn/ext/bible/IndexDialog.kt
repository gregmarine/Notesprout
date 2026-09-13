package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.core.Dialogs

/**
 * "Where to?" — the reader's index (arc 37 / B3, decision 11), in the calendar day picker's shape:
 * a bordered dialog with one pair of arrows in its header, a **books** grid whose tap opens a
 * **chapters** grid, and a title that says which grid you are in and — in the chapters grid — is
 * the way back to the books. The book and chapter being read are filled black with white bold
 * text; nothing is ringed, because there is no "today" here.
 *
 * **The cells are built in code** because a grid of eighteen names or thirty-six numbers is not a
 * layout, it is a loop. What it shows is [IndexModel]'s and is JVM-tested; this file is only
 * views. Each cell is a bordered [TextView] filling a weight-1 slot whose height is
 * `@dimen/toolbar_button_size` — the family's one hand-sized tap target (44 dp; 62 dp on the
 * sw720dp tier the Nomad and Manta sit in), never a number written here.
 *
 * E-ink rules throughout: bordered dialog, no elevation, monochrome, and **the arrows are never
 * disabled** — at either end of the grid a tap simply does nothing (a greyed control is invisible
 * on e-ink, so it would read as broken rather than as finished).
 *
 * The books flow in canon order and no testament is named: see [IndexModel].
 */
object IndexDialog {

    /**
     * Shows the index over [activity]. [books] is the source's own book table (the reader already
     * read it for the cursor), [current] the chapter on screen — its book's page opens first, with
     * that book filled. [onPicked] is called with the chosen chapter, and the dialog dismisses;
     * Cancel does nothing.
     */
    fun show(
        activity: Activity,
        books: List<BookRow>,
        current: ChapterRef,
        onPicked: (ChapterRef) -> Unit,
    ) {
        if (books.isEmpty()) return
        val view = activity.layoutInflater.inflate(R.layout.dialog_index, null)
        val title = view.findViewById<TextView>(R.id.tvIndexTitle)
        val grid = view.findViewById<LinearLayout>(R.id.llIndexGrid)
        val prev = view.findViewById<View>(R.id.btnIndexPrev)
        val next = view.findViewById<View>(R.id.btnIndexNext)

        val bookPages = IndexModel.bookPages(books)
        /** Null = the books grid (the level the dialog opens on); a book = its chapters grid. */
        var book: BookRow? = null
        // A page each, not one shared: coming back out of a book lands on the shelf you left, not
        // back on the book you are reading.
        var bookPage = IndexModel.bookPageOf(books, current.usfm)
        var chapterPage = 0
        var dialog: AlertDialog? = null

        fun renderChapters(of: BookRow) {
            title.text = of.name
            title.isClickable = true
            title.contentDescription = activity.getString(R.string.cd_bible_index_flip)
            grid.removeAllViews()
            val inCurrentBook = of.usfm.equals(current.usfm, ignoreCase = true)
            for (row in IndexModel.chapterPages(of.chapterCount).getOrElse(chapterPage) { emptyList() }) {
                val line = gridRow(activity)
                for (cell in row) {
                    line.addView(
                        if (cell == null) spacerCell(activity)
                        else textCell(
                            activity,
                            label = cell.toString(),
                            size = CHAPTER_TEXT_SP,
                            picked = inCurrentBook && cell == current.chapter,
                        ) {
                            onPicked(ChapterRef(of.usfm, cell))
                            dialog?.dismiss()
                        },
                    )
                }
                grid.addView(line)
            }
        }

        fun renderBooks() {
            title.text = activity.getString(R.string.bible_index_books)
            // The top level has nowhere to flip back to, so the title is not a tap target here.
            title.isClickable = false
            title.contentDescription = null
            grid.removeAllViews()
            for (row in bookPages.getOrElse(bookPage) { emptyList() }) {
                val line = gridRow(activity)
                for (cell in row) {
                    line.addView(
                        if (cell == null) spacerCell(activity)
                        else textCell(
                            activity,
                            label = cell.name,
                            size = BOOK_TEXT_SP,
                            picked = cell.usfm.equals(current.usfm, ignoreCase = true),
                        ) {
                            book = cell
                            // The chapter being read is only filled in its own book, and its page
                            // is the one that opens there.
                            chapterPage = if (cell.usfm.equals(current.usfm, ignoreCase = true)) {
                                IndexModel.chapterPageOf(current.chapter)
                            } else {
                                0
                            }
                            renderChapters(cell)
                        },
                    )
                }
                grid.addView(line)
            }
        }

        fun render() {
            val of = book
            if (of == null) renderBooks() else renderChapters(of)
        }

        // Back out of a book, the day picker's title flip. Books level: not clickable at all.
        title.setOnClickListener {
            book = null
            renderBooks()
        }
        // One pair of arrows, two meanings — book pages or chapter pages; the title above them
        // always says which, so the pair never has to be labelled twice. At either end the tap is
        // a **no-op**: the page does not move and nothing repaints.
        fun step(by: Int) {
            val of = book
            if (of == null) {
                val moved = IndexModel.clampPage(bookPage + by, bookPages.size)
                if (moved == bookPage) return
                bookPage = moved
            } else {
                val count = IndexModel.chapterPages(of.chapterCount).size
                val moved = IndexModel.clampPage(chapterPage + by, count)
                if (moved == chapterPage) return
                chapterPage = moved
            }
            render()
        }
        prev.apply {
            TooltipCompat.setTooltipText(this, contentDescription)   // every icon button names itself
            setOnClickListener { step(-1) }
        }
        next.apply {
            TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener { step(1) }
        }
        render()

        val created = Dialogs.style(
            AlertDialog.Builder(activity)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog = created
        created.show()
        // Three quarters of the screen, whatever the tier (the day picker's call): a full-width
        // dialog reads as a page and its bordered background sits at the glass's edge, invisible.
        // The window is sized after show() — before it there is no window to size — and the
        // weighted cells then measure against this width rather than the screen's.
        created.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    /** The dialog's width as a fraction of the screen's. */
    private const val WIDTH_FRACTION = 0.75f

    /** A book's name has two lines to fit in; a chapter number never needs more than one. */
    private const val BOOK_TEXT_SP = 14f
    private const val CHAPTER_TEXT_SP = 15f

    // --- cells ---------------------------------------------------------------

    private fun gridRow(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** A trailing blank: a slot that holds its column open and does nothing. */
    private fun spacerCell(activity: Activity): View = View(activity).apply {
        layoutParams = cellParams(activity)
    }

    /**
     * One bordered cell — a book's name or a chapter's number. `picked` is the one being read:
     * filled black with white bold text, the month cell's recipe. Two lines at most, ellipsized:
     * "1 Thessalonians" wraps, it does not disappear.
     */
    private fun textCell(
        activity: Activity, label: String, size: Float, picked: Boolean, onClick: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = size
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = cellParams(activity)
        if (picked) {
            setBackgroundResource(R.drawable.bg_index_selected)
            setTextColor(color(activity, R.color.paperWhite))
            setTypeface(typeface, Typeface.BOLD)
        } else {
            setBackgroundResource(R.drawable.shape_bordered)
            setTextColor(color(activity, R.color.inkBlack))
        }
        setOnClickListener { onClick() }
    }

    /** Every slot in every grid: weight-1 wide, one hand-sized tap target tall, 3 dp apart. */
    private fun cellParams(activity: Activity): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, tapSize(activity), 1f).apply {
            val m = dp(activity, 3)
            setMargins(m, m, m, m)
        }

    private fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    /** The one hand-sized tap target — `@dimen/toolbar_button_size`, never a number here. */
    private fun tapSize(activity: Activity): Int =
        activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

    private fun color(activity: Activity, res: Int): Int = ContextCompat.getColor(activity, res)
}
