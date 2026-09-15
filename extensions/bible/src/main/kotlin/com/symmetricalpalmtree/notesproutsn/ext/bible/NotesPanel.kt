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
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.notesproutsn.core.ListSwipe
import com.symmetricalpalmtree.notesproutsn.core.Slog
import java.util.Date

/**
 * "What have I written about this?" — the personal commentary as a **side panel** (arc 42
 * "Notes" / N1, decision 6): [RecentsPanel]'s shape in a third subject, because the question it
 * answers is the Recents' question asked of the notebooks instead of the reader. A full-window
 * `Dialog` over the reader; one layout, two forms — `dialog_notes.xml` branches in code on
 * [ContentsLayout.fullScreen] — below 480 dp the panel fills the screen behind a back arrow, at
 * or above it is a **right** sidebar ([NotesModel.SIDEBAR_WIDTH_FRACTION] = 60 %, wider than the
 * Recents' because a row names a place *and* its references) over a transparent scrim whose tap
 * dismisses.
 *
 * **Rows** are two lines — where it was written ("Study · Page 4", 20 sp) and what it says about
 * the scope with when it was written (13 sp) — both inkBlack: secondary text is *smaller*, never
 * grey. One row per notebook page or document ([NotesModel.group]), in the order the references
 * point, so the list reads down the chapter. The list **paginates, it never scrolls** (the e-ink
 * rule): one row is inflated and **measured** at the real panel width after the first layout and
 * `itemsPerPage` follows; the pager footer is `INVISIBLE` at one page and a tap at a bound is a
 * no-op. A one-finger horizontal swipe over the body flips pages too, taken from the *dialog's*
 * `dispatchTouchEvent` — a `Dialog` owns its own window and the reader behind it never sees a
 * stroke of it.
 *
 * **Rebuild** (decision 9) sits in the header: the index is fed by a live push, and the one place
 * to ask for it to be walked again is where its answer is being read. It dismisses first and
 * hands the job up — the reader closes for it, and the host reopens the reader where it was.
 *
 * A modal snapshot: what it shows is what [BibleActivity] gathered before opening it — nothing is
 * cached and nothing invalidates. A row tap dismisses and hands its group up; "No notes for
 * Genesis 1" is a real answer, shown in the body, never a reason to hide the door.
 *
 * **Never logged:** a notebook name is the user's own word and a label names where they have
 * read — counts only.
 */
class NotesPanel(
    private val activity: Activity,
    /** What the reader is showing, for the empty line — the title as it stands ("Genesis 1"). */
    private val scopeLabel: String,
    private val groups: List<NotesModel.NoteGroup>,
    private val onDismissed: () -> Unit,
    /** The chosen entry; the panel has dismissed itself by the time this runs. */
    private val onPicked: (NotesModel.NoteGroup) -> Unit,
    /** Rebuild the whole index; the panel has dismissed itself first. */
    private val onRebuild: () -> Unit,
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
        dialog.setContentView(R.layout.dialog_notes)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val root = dialog.findViewById<FrameLayout>(R.id.notesRoot)
        val panel = dialog.findViewById<LinearLayout>(R.id.notesPanel)
        body = dialog.findViewById(R.id.notesBody)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnNotesBack)
        val btnRebuild = dialog.findViewById<AppCompatButton>(R.id.btnNotesRebuild)
        list = dialog.findViewById(R.id.notesRows)
        empty = dialog.findViewById(R.id.notesEmpty)
        pager = dialog.findViewById(R.id.notesPager)
        pageLabel = dialog.findViewById(R.id.notesPageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnNotesFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnNotesPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnNotesNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnNotesLast)
        listOf(btnBack, btnFirst, btnPrev, btnNext, btnLast).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)   // every icon button names itself
        }

        empty.text = activity.getString(R.string.bible_notes_empty, scopeLabel)
        btnRebuild.setOnClickListener {
            dialog.dismiss()
            onRebuild()
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
                NotesModel.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            ).also { it.gravity = Gravity.END }
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(ContentsModel.pageCount(groups.size, itemsPerPage) - 1) }

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
                        "rows/page=$itemsPerPage entries=${groups.size}"
                }
            }
        })

        dialog.show()
    }

    /** Safe when nothing is showing — the screen's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** Inflate one row, measure it at the list's real width, and return its full height in px. */
    private fun measureRowHeightPx(): Int {
        val sample = LayoutInflater.from(activity).inflate(R.layout.item_note_entry, list, false)
        sample.measure(
            View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return sample.measuredHeight
    }

    private fun goToListPage(page: Int) {
        val clamped = ContentsModel.clampPage(page, ContentsModel.pageCount(groups.size, itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
        listPage = clamped
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (groups.isEmpty()) {
            empty.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        empty.visibility = View.GONE
        val pageCount = ContentsModel.pageCount(groups.size, itemsPerPage)
        listPage = ContentsModel.clampPage(listPage, pageCount)
        val start = listPage * itemsPerPage
        val end = minOf(start + itemsPerPage, groups.size)
        val inflater = LayoutInflater.from(activity)
        // The date alone: a note is a thing the user wrote, not a moment they passed through —
        // the hour it was written says nothing the day does not.
        val dateFormat = DateFormat.getMediumDateFormat(activity)
        val pageWord = activity.getString(R.string.bible_notes_page_word)
        val documentWord = activity.getString(R.string.bible_notes_document_word)
        for (group in groups.subList(start, end)) {
            val row = inflater.inflate(R.layout.item_note_entry, list, false)
            row.findViewById<TextView>(R.id.noteTitle).text =
                NotesModel.title(group, pageWord, documentWord)
            row.findViewById<TextView>(R.id.noteDetail).text =
                NotesModel.detail(group, dateFormat.format(Date(group.newestAt)), documentWord)
            row.setOnClickListener {
                dialog.dismiss()
                onPicked(group)
            }
            list.addView(row)
        }
        pageLabel.text = activity.getString(R.string.bible_page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val TAG = "NotesPanel"
    }
}
