package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.format.DateFormat
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
import java.util.Date

/**
 * "Where was I?" — the reader's Recents as a **side panel** (arc 37 / B7): the notebook's
 * `RecentsDialog`, subject for subject — [ContentsPanel]'s twin, **mirrored to the right**. A
 * full-window `Dialog` over the reader; one layout, two forms — `dialog_recents.xml` branches in
 * code on [ContentsLayout.fullScreen] (the 480 dp breakpoint is decided once): below it the
 * panel fills the screen behind a back arrow, at or above it is a **right** sidebar (its 2 dp
 * inkBlack *left* border, [RecentChapters.SIDEBAR_WIDTH_FRACTION] = 50 % wide) over a
 * transparent scrim whose tap dismisses. The header runs title-then-arrow so the dismissal sits
 * nearest the edge the panel came from.
 *
 * **Rows** are two lines — the chapter in the running head's form ("Psalm 23", 20 sp) and when it
 * was picked (`<medium date>, <time>`, 13 sp), both inkBlack: secondary text is *smaller*, never
 * grey. The list **paginates, it never scrolls** (the e-ink rule): one row is inflated and
 * **measured** at the real panel width after the first layout and `itemsPerPage` follows from
 * that; the pager footer is `INVISIBLE` at one page and a tap at a bound is a no-op. A one-finger
 * horizontal swipe over the body flips pages too — taken from the *dialog's* `dispatchTouchEvent`,
 * because a `Dialog` owns its own window and the reader behind it never sees a stroke of it.
 *
 * **Two kinds of row** (arc 38 / R2): a chapter picked by name, and a **passage** followed here
 * from a notebook's Bible link. They read the same — a name and a time — because they answer the
 * same question; only the tap differs, and [RecentChapters.select] has already merged the two
 * histories by when they happened.
 *
 * A modal snapshot: what it shows is what [BibleActivity] gathered before opening it — nothing is
 * cached and nothing invalidates. A row tap dismisses and hands its entry up; "No recents" is a
 * real answer, shown in the body, never a reason to hide the door.
 */
class RecentsPanel(
    private val activity: Activity,
    private val rows: List<RecentEntry>,
    private val onDismissed: () -> Unit,
    /** The chosen chapter; the panel has dismissed itself by the time this runs. */
    private val onPicked: (ChapterRef) -> Unit,
    /** The chosen passage, as its wire (arc 38 / R2); the panel has dismissed itself first. */
    private val onPickedPassage: (String) -> Unit,
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
    private var listPage = 0
    private var itemsPerPage = 1

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_recents)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val root = dialog.findViewById<FrameLayout>(R.id.recentsRoot)
        val panel = dialog.findViewById<LinearLayout>(R.id.recentsPanel)
        body = dialog.findViewById(R.id.recentsBody)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsBack)
        list = dialog.findViewById(R.id.recentsRows)
        empty = dialog.findViewById(R.id.recentsEmpty)
        pager = dialog.findViewById(R.id.recentsPager)
        pageLabel = dialog.findViewById(R.id.recentsPageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnRecentsLast)
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
            // Plain paper, not the sidebar shape — its left border would be a stray line down the
            // screen edge.
            panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(
                RecentChapters.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            ).also { it.gravity = Gravity.END }
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(ContentsModel.pageCount(rows.size, itemsPerPage) - 1) }

        // itemsPerPage from the real body height and a really-measured row, once after the first
        // layout — a two-line row's height depends on the font scale, so nothing is estimated.
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val bodyPx = list.height - list.paddingTop - list.paddingBottom
                itemsPerPage = RecentChapters.itemsPerPage(bodyPx, measureRowHeightPx())
                listPage = 0
                render()
                Slog.d(TAG) {
                    "shown: fullScreen=$fullScreen widthDp=$widthDp panel=${panel.width}px " +
                        "rows/page=$itemsPerPage entries=${rows.size}"
                }
            }
        })

        dialog.show()
    }

    /** Safe when nothing is showing — the host's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** Inflate one row, measure it at the list's real width, and return its full height in px. */
    private fun measureRowHeightPx(): Int {
        val sample = LayoutInflater.from(activity).inflate(R.layout.item_recent_entry, list, false)
        sample.measure(
            View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return sample.measuredHeight
    }

    private fun goToListPage(page: Int) {
        val clamped = ContentsModel.clampPage(page, ContentsModel.pageCount(rows.size, itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
        listPage = clamped
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (rows.isEmpty()) {
            empty.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        empty.visibility = View.GONE
        val pageCount = ContentsModel.pageCount(rows.size, itemsPerPage)
        listPage = ContentsModel.clampPage(listPage, pageCount)
        val start = listPage * itemsPerPage
        val end = minOf(start + itemsPerPage, rows.size)
        val inflater = LayoutInflater.from(activity)
        val dateFormat = DateFormat.getMediumDateFormat(activity)
        val timeFormat = DateFormat.getTimeFormat(activity)
        for (entry in rows.subList(start, end)) {
            val row = inflater.inflate(R.layout.item_recent_entry, list, false)
            row.findViewById<TextView>(R.id.recentName).text = RecentChapters.label(entry)
            val at = Date(entry.at)
            row.findViewById<TextView>(R.id.recentWhen).text =
                activity.getString(R.string.bible_recents_when, dateFormat.format(at), timeFormat.format(at))
            row.setOnClickListener {
                dialog.dismiss()
                when (entry) {
                    is RecentEntry.Chapter -> onPicked(entry.ref)
                    is RecentEntry.Reference -> onPickedPassage(entry.wire)
                }
            }
            list.addView(row)
        }
        pageLabel.text = activity.getString(R.string.bible_page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val TAG = "RecentsPanel"
    }
}
