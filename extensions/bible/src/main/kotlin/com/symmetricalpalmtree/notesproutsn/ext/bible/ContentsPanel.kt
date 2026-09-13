package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * "Where to?" — the reader's index as a **side panel** (arc 37 / B6, the user's post-freeze
 * decision replacing B3's bordered two-grid dialog): the notebook Contents' shape, subject for
 * subject. A full-window `Dialog` over the reader; **one layout, two forms** — `dialog_contents.xml`
 * branches in code on [ContentsLayout.fullScreen]: below 480 dp the panel fills the screen behind a
 * back arrow; at or above it is a 60 % left sidebar (its 2 dp inkBlack right border) over a
 * transparent scrim whose tap dismisses.
 *
 * **Every book is a root entry** — `[+/− toggle | chapter count | 1 dp divider | name]`, the
 * Contents' row with the page number's slot holding the book's length. The toggle opens the
 * book; the row itself navigates to its **first chapter** (the Contents' split: tap = go, +/− =
 * show). An open book is followed by its **chapters as a grid**, six
 * bordered numbers to a row (the B3 grid, kept; a number needs a square, not a row of its own).
 * The book being read opens expanded, on the list page holding its current chapter; that book's
 * row takes the Contents' 5 dp right-edge bar and that chapter's cell is filled black with white
 * bold text. Expansion is in-memory only — every open starts from the current book alone.
 *
 * The list **paginates, it never scrolls** (the e-ink rule): rows are one uniform height, the
 * body's height is measured once after the first layout and `itemsPerPage` follows from it, with
 * the library's pager footer below (`INVISIBLE` at one page; a tap at a bound is a no-op, never a
 * disabled look). A one-finger horizontal swipe over the body flips those pages too — a `Dialog`
 * owns its own window, so the sequence is taken from the dialog's `dispatchTouchEvent`, and the
 * reader's own swipe behind the panel never sees a stroke of it: the page underneath cannot turn
 * while the index is up.
 *
 * What it shows is [ContentsModel]'s and is JVM-tested; this file is only views. The cells are the
 * B3 cells: a bordered [TextView] filling a weight-1 slot whose height is
 * `@dimen/toolbar_button_size` — the family's one hand-sized tap target, never a number here.
 */
class ContentsPanel(
    private val activity: Activity,
    private val books: List<BookRow>,
    private val current: ChapterRef,
    private val onDismissed: () -> Unit,
    /** The chosen chapter; the panel has dismissed itself by the time this runs. */
    private val onPicked: (ChapterRef) -> Unit,
) {
    private val listSwipe = ListSwipe(
        region = { body },
        onFlipNext = { goToListPage(listPage + 1) },
        onFlipPrevious = { goToListPage(listPage - 1) },
    )

    private val dialog = object : Dialog(activity, R.style.Theme_Notesprout) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            listSwipe.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }

    /** The body band the rows paginate inside — the region the flip arms on; null until shown. */
    private var body: View? = null
    private val expanded = HashSet<String>()
    private var items: List<ContentsModel.Item> = emptyList()
    private var listPage = 0
    private var itemsPerPage = 1

    private lateinit var rows: LinearLayout
    private lateinit var pager: View
    private lateinit var pageLabel: TextView

    fun show() {
        if (books.isEmpty() || activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_contents)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val root = dialog.findViewById<FrameLayout>(R.id.contentsRoot)
        val panel = dialog.findViewById<LinearLayout>(R.id.contentsPanel)
        body = dialog.findViewById(R.id.contentsBody)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsBack)
        rows = dialog.findViewById(R.id.contentsRows)
        pager = dialog.findViewById(R.id.contentsPager)
        pageLabel = dialog.findViewById(R.id.contentsPageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnContentsLast)
        listOf(btnBack, btnFirst, btnPrev, btnNext, btnLast).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)   // every icon button names itself
        }

        val metrics = activity.resources.displayMetrics
        val widthDp = activity.resources.configuration.screenWidthDp
        val fullScreen = ContentsLayout.fullScreen(widthDp)
        if (fullScreen) {
            root.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            panel.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // Plain paper, not the sidebar shape — its right border would be a stray line down the
            // screen edge.
            panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(
                ContentsLayout.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            )
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(ContentsModel.pageCount(items.size, itemsPerPage) - 1) }

        // Opening state: the book being read is the one open, and the page is the one holding the
        // row its chapter sits in — the panel opens looking at where you are.
        expanded += current.usfm.uppercase()
        items = ContentsModel.items(books, expanded)

        // itemsPerPage from the real body height, measured once after the first layout — no estimate.
        rows.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rows.viewTreeObserver.removeOnGlobalLayoutListener(this)
                itemsPerPage = ContentsLayout.itemsPerPage(
                    rows.height - rows.paddingTop - rows.paddingBottom, metrics.density,
                )
                val target = ContentsModel.indexOfChapter(items, current.usfm, current.chapter)
                    .takeIf { it >= 0 } ?: ContentsModel.indexOfBook(items, current.usfm)
                listPage = ContentsModel.pageOf(target, itemsPerPage)
                render()
                Slog.d(TAG) {
                    "shown: fullScreen=$fullScreen widthDp=$widthDp panel=${panel.width}px " +
                        "rows/page=$itemsPerPage items=${items.size} at=$target"
                }
            }
        })

        dialog.show()
    }

    /** Safe when nothing is showing — the host's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    private fun goToListPage(page: Int) {
        val clamped = ContentsModel.clampPage(page, ContentsModel.pageCount(items.size, itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
        listPage = clamped
        render()
    }

    /** Opens or closes a book's chapters. The page holds still where it can: the toggled row stays
     *  the anchor, so a collapse below the fold never leaves the reader on an emptied page. */
    private fun toggle(book: BookRow) {
        val key = book.usfm.uppercase()
        if (!expanded.remove(key)) expanded += key
        items = ContentsModel.items(books, expanded)
        val anchor = ContentsModel.indexOfBook(items, book.usfm)
        listPage = ContentsModel.pageOf(anchor, itemsPerPage)
        render()
    }

    private fun render() {
        rows.removeAllViews()
        val pageCount = ContentsModel.pageCount(items.size, itemsPerPage)
        listPage = ContentsModel.clampPage(listPage, pageCount)
        val start = listPage * itemsPerPage
        val end = minOf(start + itemsPerPage, items.size)
        val inflater = LayoutInflater.from(activity)
        for (item in items.subList(start, end)) {
            rows.addView(
                when (item) {
                    is ContentsModel.Item.Book -> bookRow(inflater, item)
                    is ContentsModel.Item.Chapters -> chapterRow(item)
                },
            )
        }
        pageLabel.text = activity.getString(R.string.bible_page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    // --- rows ----------------------------------------------------------------

    private fun bookRow(inflater: LayoutInflater, item: ContentsModel.Item.Book): View {
        val row = inflater.inflate(R.layout.item_contents_book, rows, false)
        val btnToggle = row.findViewById<AppCompatImageButton>(R.id.btnToggle)
        row.findViewById<TextView>(R.id.entryCount).text = item.book.chapterCount.toString()
        row.findViewById<TextView>(R.id.entryLabel).text = item.book.name
        btnToggle.setImageResource(if (item.expanded) R.drawable.ic_minus else R.drawable.ic_plus)
        btnToggle.contentDescription =
            activity.getString(if (item.expanded) R.string.cd_bible_collapse else R.string.cd_bible_expand)
        TooltipCompat.setTooltipText(btnToggle, btnToggle.contentDescription)
        btnToggle.setOnClickListener { toggle(item.book) }
        // The Contents' split (the user's call): the toggle opens the chapters, the row itself is
        // a destination — the book's first chapter.
        row.setOnClickListener {
            dialog.dismiss()
            onPicked(ChapterRef(item.book.usfm, 1))
        }
        row.background = if (item.book.usfm.equals(current.usfm, ignoreCase = true)) {
            ContextCompat.getDrawable(activity, R.drawable.bg_contents_active_entry)
        } else {
            null
        }
        return row
    }

    /** One row of six bordered numbers, built to exactly a book row's slot so the list stays
     *  uniform; indented past the toggle so it reads as the book's child. */
    private fun chapterRow(item: ContentsModel.Item.Chapters): View {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ContentsLayout.rowPx(activity.resources.displayMetrics.density),
            )
            setPaddingRelative(tapSize() + dp(4), 0, dp(12), 0)
        }
        val inCurrentBook = item.book.usfm.equals(current.usfm, ignoreCase = true)
        for (cell in item.cells) {
            line.addView(
                if (cell == null) spacerCell()
                else textCell(cell.toString(), picked = inCurrentBook && cell == current.chapter) {
                    dialog.dismiss()
                    onPicked(ChapterRef(item.book.usfm, cell))
                },
            )
        }
        return line
    }

    // --- cells (B3's, kept) ---------------------------------------------------

    /** A trailing blank: a slot that holds its column open and does nothing. */
    private fun spacerCell(): View = View(activity).apply { layoutParams = cellParams() }

    /** One bordered cell — a chapter's number. `picked` is the one being read: filled black,
     *  white bold text. Nothing is ringed; there is no "today" here. */
    private fun textCell(label: String, picked: Boolean, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = CHAPTER_TEXT_SP
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = cellParams()
            if (picked) {
                setBackgroundResource(R.drawable.bg_contents_selected)
                setTextColor(ContextCompat.getColor(activity, R.color.paperWhite))
                setTypeface(typeface, Typeface.BOLD)
            } else {
                setBackgroundResource(R.drawable.shape_bordered)
                setTextColor(ContextCompat.getColor(activity, R.color.inkBlack))
            }
            setOnClickListener { onClick() }
        }

    /** Every slot in every grid row: weight-1 wide, one hand-sized tap target tall, 3 dp apart. */
    private fun cellParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, tapSize(), 1f).apply {
            val m = dp(3)
            setMargins(m, m, m, m)
        }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    /** The one hand-sized tap target — `@dimen/toolbar_button_size`, never a number here. */
    private fun tapSize(): Int = activity.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

    private companion object {
        const val TAG = "ContentsPanel"
        const val CHAPTER_TEXT_SP = 15f
    }
}
