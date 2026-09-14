package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * "Where does it say…?" — the reader's Search as a **side panel** (arc 37 / B8): Biblesprout's
 * `FindActivity` in [RecentsPanel]'s shape — a full-window `Dialog` over the reader, mirrored to
 * the right like the Recents (its button sits beside the clock), at the Contents' 60 % because a
 * row carries a verse; full-screen behind a back arrow below the 480 dp breakpoint.
 *
 * **The header is the field.** One line, "Reference or words": what is typed goes up to the
 * screen as-is through [onQuery], and the screen decides — a reference dismisses this panel and
 * opens the place, words come back down as [showResults]. The panel never touches the database
 * and never classifies; it shows what it is handed. The soft keyboard comes up with the panel
 * when there is nothing to show yet, and goes away on a search — **unless a hardware keyboard is
 * attached**, because on Supernote such a keyboard delivers keys only while the IME is showing
 * (the host's reference dialog rule). The window resizes for the IME and the rows re-measure.
 *
 * **Rows** are the verse's address in bold and two lines of its text (always two, so every row is
 * one height), the matched words bold and the text windowed to the first match
 * ([SearchSnippet]); the count line above says how many, honestly, even when the list is capped.
 * The list **paginates, it never scrolls**: rows per page from a measured row and the real body,
 * re-measured whenever the body's height changes (the IME coming and going). A one-finger
 * horizontal swipe over the body flips pages, from the dialog's own `dispatchTouchEvent`.
 *
 * A row tap dismisses and hands the hit up. The panel keeps no state of its own: the screen hands
 * it the last results on re-open, so a search survives the panel's closing for the life of the
 * screen, as Biblesprout's list survives a Back from the reader.
 */
class SearchPanel(
    private val activity: Activity,
    /** The last search, shown again — null for a fresh panel (the help shows instead). */
    private val initial: SearchResults?,
    private val onDismissed: () -> Unit,
    /** The field's trimmed text on submit, never blank. The panel is still up. */
    private val onQuery: (String) -> Unit,
    /** The chosen hit; the panel has dismissed itself by the time this runs. */
    private val onPicked: (SearchHit) -> Unit,
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

    private var body: View? = null
    private var listPage = 0
    private var itemsPerPage = 1
    private var rowHeightPx = 0
    private var results: SearchResults? = initial
    /** True from [showSearching] until [showResults]: the body holds the searching word. */
    private var searching = false

    private lateinit var input: EditText
    private lateinit var summary: TextView
    private lateinit var list: LinearLayout
    private lateinit var help: View
    private lateinit var message: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView

    val isShowing: Boolean get() = dialog.isShowing

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_search)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // The keyboard comes up with the panel only when there is nothing to read yet; a
            // re-opened list is for reading, and the field is one tap away. Either way the
            // window resizes for it, so the pager stays on screen and the rows re-measure.
            val state = if (initial == null) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            }
            setSoftInputMode(state or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val root = dialog.findViewById<FrameLayout>(R.id.searchRoot)
        val panel = dialog.findViewById<LinearLayout>(R.id.searchPanel)
        body = dialog.findViewById(R.id.searchBody)
        input = dialog.findViewById(R.id.searchInput)
        summary = dialog.findViewById(R.id.searchSummary)
        list = dialog.findViewById(R.id.searchRows)
        help = dialog.findViewById(R.id.searchHelp)
        message = dialog.findViewById(R.id.searchMessage)
        pager = dialog.findViewById(R.id.searchPager)
        pageLabel = dialog.findViewById(R.id.searchPageLabel)
        val btnGo = dialog.findViewById<AppCompatImageButton>(R.id.btnSearchGo)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnSearchBack)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnSearchFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnSearchPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnSearchNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnSearchLast)
        listOf(btnGo, btnBack, btnFirst, btnPrev, btnNext, btnLast).forEach {
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
            panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(
                ContentsLayout.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            ).also { it.gravity = Gravity.END }
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        btnGo.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        initial?.let { input.setText(it.query); input.setSelection(it.query.length) }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(ContentsModel.pageCount(hitCount(), itemsPerPage) - 1) }

        // Rows per page from the real body and a really-measured row — and again whenever the
        // body's height changes, which it does every time the keyboard comes or goes.
        list.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            list.post { remeasure() }
        }

        renderState()
        dialog.show()
        input.requestFocus()
    }

    /** Safe when nothing is showing — the host's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** The screen is looking: a word in the body, the old list gone. */
    fun showSearching() {
        searching = true
        results = null
        summary.visibility = View.GONE
        help.visibility = View.GONE
        list.removeAllViews()
        pager.visibility = View.INVISIBLE
        pageLabel.text = ""
        message.text = activity.getString(R.string.bible_searching)
        message.visibility = View.VISIBLE
    }

    /** What the screen found — a list, or "No results" as a real answer. */
    fun showResults(found: SearchResults) {
        searching = false
        results = found
        listPage = 0
        renderState()
    }

    private fun submit() {
        val typed = input.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) return
        // The list wants the whole panel; the keyboard goes — unless a hardware keyboard is
        // attached, which on Supernote types only while the IME is showing.
        if (activity.resources.configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_NO) {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        onQuery(typed)
    }

    private fun hitCount(): Int = results?.hits?.size ?: 0

    /** Inflate one row, measure it at the list's real width, and return its full height in px. */
    private fun measureRowHeightPx(): Int {
        val sample = LayoutInflater.from(activity).inflate(R.layout.item_search_hit, list, false)
        sample.findViewById<TextView>(R.id.searchHitRef).text = "X"
        sample.findViewById<TextView>(R.id.searchHitText).text = "X"
        sample.measure(
            View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return sample.measuredHeight
    }

    private fun remeasure() {
        if (list.width <= 0 || list.height <= 0) return
        if (rowHeightPx <= 0) rowHeightPx = measureRowHeightPx()
        val bodyPx = list.height - list.paddingTop - list.paddingBottom
        val perPage = RecentChapters.itemsPerPage(bodyPx, rowHeightPx)
        if (perPage == itemsPerPage && list.childCount > 0) return
        itemsPerPage = perPage
        Slog.d(TAG) { "measured: rows/page=$itemsPerPage body=${bodyPx}px" }
        renderState()
    }

    private fun goToListPage(page: Int) {
        val clamped = ContentsModel.clampPage(page, ContentsModel.pageCount(hitCount(), itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
        listPage = clamped
        renderState()
    }

    /** The body from [results]: the help, "No results", or a page of rows. */
    private fun renderState() {
        val found = results
        // The keyboard going down on submit resizes the body and lands here through the
        // remeasure; the word on screen is "Searching…", and it stays until an answer.
        if (found == null && searching) return
        list.removeAllViews()
        if (found == null) {
            summary.visibility = View.GONE
            message.visibility = View.GONE
            help.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        help.visibility = View.GONE
        if (found.hits.isEmpty()) {
            summary.visibility = View.GONE
            message.text = activity.getString(R.string.bible_search_none, found.query)
            message.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        message.visibility = View.GONE
        summary.text = if (found.capped) {
            activity.getString(R.string.bible_search_capped, found.hits.size, found.total, found.query)
        } else {
            activity.resources.getQuantityString(R.plurals.bible_search_count, found.total, found.total, found.query)
        }
        summary.visibility = View.VISIBLE

        val pageCount = ContentsModel.pageCount(found.hits.size, itemsPerPage)
        listPage = ContentsModel.clampPage(listPage, pageCount)
        val start = listPage * itemsPerPage
        val end = minOf(start + itemsPerPage, found.hits.size)
        val inflater = LayoutInflater.from(activity)
        for (hit in found.hits.subList(start, end)) {
            val row = inflater.inflate(R.layout.item_search_hit, list, false)
            row.findViewById<TextView>(R.id.searchHitRef).text = hit.label
            row.findViewById<TextView>(R.id.searchHitText).text = emphasised(hit.text, found.tokens)
            row.setOnClickListener {
                dialog.dismiss()
                onPicked(hit)
            }
            list.addView(row)
        }
        pageLabel.text = activity.getString(R.string.bible_page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    /** The snippet with its matches in bold. */
    private fun emphasised(text: String, tokens: List<String>): CharSequence {
        val rendered = SearchSnippet.render(text, tokens)
        if (rendered.bold.isEmpty()) return rendered.text
        val span = SpannableString(rendered.text)
        for (range in rendered.bold) {
            span.setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    private companion object {
        const val TAG = "SearchPanel"
    }
}
