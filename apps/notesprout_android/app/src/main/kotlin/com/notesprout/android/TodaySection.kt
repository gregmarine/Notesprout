package com.notesprout.android

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.notesprout.android.core.Slog
import com.notesprout.android.data.TodayGroup
import com.notesprout.android.databinding.ViewTodaySectionBinding

/**
 * One section of the [TodayActivity] dashboard — Tasks, Events, or Notebooks — and the pagination
 * that keeps it inside its band.
 *
 * The dashboard's whole premise is that everything fits on screen at once, which only holds if a
 * section that *doesn't* fit turns into pages rather than a scroll. So rows are built once, measured
 * against the space actually available, and packed into pages; the pager steps through them.
 *
 * **Prev/next only** — deliberately no first/last. "Today" is a small set by construction, and
 * jumping to the end of a two-page list is chrome that earns nothing.
 *
 * Group headers **repeat** at the top of a continuation page. A page of rows under no heading would
 * leave the reader unable to tell overdue work from today's, which is the one distinction the
 * grouping exists to make.
 */
class TodaySection<T>(
    private val ui: ViewTodaySectionBinding,
    title: String,
    addHint: String,
    emptyText: String,
    onAdd: () -> Unit,
    private val makeRow: (T) -> View,
    /**
     * Draw a group's label even when it is the only group.
     *
     * Off by default, because a lone group usually needs no header — "Events" over a single run of
     * rows already says what they are. That reasoning fails wherever the section title names a
     * *category* and the groups name something the title doesn't imply: Notebooks holds **Today**
     * and **Recent**, and an unlabelled lone Recent group would read as today's work.
     */
    private val alwaysLabelGroups: Boolean = false,
) {

    private val context: Context = ui.root.context

    private var pages: List<List<View>> = emptyList()
    private var page = 0

    /** Discards a layout callback whose [submit] has since been superseded. */
    private var token = 0

    init {
        ui.tvSectionTitle.text = title
        // Doubles as the long-press hint — Android surfaces contentDescription as a tooltip, which
        // is what keeps a bare glyph learnable on e-ink (see the design system).
        ui.btnSectionAdd.contentDescription = addHint
        ui.btnSectionAdd.setOnClickListener { onAdd() }
        ui.sectionEmpty.text = emptyText

        ui.btnSectionPrev.setOnClickListener { if (page > 0) { page -= 1; render() } }
        ui.btnSectionNext.setOnClickListener {
            if (page < pages.lastIndex) { page += 1; render() }
        }
    }

    /**
     * Replace the section's contents. The current page number is **kept** (coerced into range), so a
     * refresh triggered by ticking a task off doesn't throw the reader back to page 1.
     */
    fun submit(groups: List<TodayGroup<T>>) {
        val mine = ++token
        val cells = buildCells(groups)
        // Nothing to fit means nothing to measure. Short-circuiting also means an empty section on a
        // tab that has never been opened still says so, rather than waiting on a layout pass that
        // only happens if the user goes looking.
        if (cells.isEmpty()) {
            pages = emptyList()
            render()
            return
        }
        whenMeasured(mine) { width, height ->
            pages = buildPages(cells, width, height)
            render()
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun render() {
        ui.sectionList.removeAllViews()
        ui.sectionEmpty.isVisible = pages.isEmpty()
        if (pages.isNotEmpty()) {
            page = page.coerceIn(0, pages.lastIndex)
            for (view in pages[page]) ui.sectionList.addView(view)
        }
        // INVISIBLE, never GONE. The pager's 44dp has to stay reserved whether or not it is in use:
        // hiding it outright would give the list more room than it was measured against, and then
        // revealing it on the next refresh would clip the very rows that had been made to fit.
        ui.sectionPager.visibility = if (pages.size > 1) View.VISIBLE else View.INVISIBLE
        ui.tvSectionPage.text = "${page + 1}/${pages.size.coerceAtLeast(1)}"
    }

    // ── Cells and paging ───────────────────────────────────────────────────────

    /** A built, measurable unit of the list: a group header, or one row. */
    private class Cell(val view: View, val isHeader: Boolean) {
        /** Measured height including both vertical margins. */
        var height = 0

        /**
         * The bottom margin alone. Discounted when this cell is the *last* on a page: the gap below
         * it separates it from a row that isn't there, so requiring the page to fit it costs a whole
         * row wherever the remainder lands inside one margin's width of the band.
         */
        var bottomMargin = 0
    }

    private fun buildCells(groups: List<TodayGroup<T>>): List<Cell> {
        // A lone group needs no header: the section's own title already names what these rows are.
        val labelled = groups.size > 1 || alwaysLabelGroups
        val cells = mutableListOf<Cell>()
        for (group in groups) {
            if (labelled) cells += Cell(header(group.label), isHeader = true)
            for (item in group.items) cells += Cell(makeRow(item), isHeader = false)
        }
        return cells
    }

    /** A bold black group label, matching the Events and Tasks lists so all three read as a family. */
    private fun header(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (6 * context.resources.displayMetrics.density).toInt() }
    }

    /**
     * Pack [cells] into pages that fit [innerHeight], measuring each against the real [innerWidth]
     * rather than assuming a uniform row height — a two-line title or a visible meta line makes rows
     * genuinely different sizes.
     *
     * A header is emitted lazily, with the first row that follows it, so it can never be stranded at
     * the foot of a page; when a group spills over, the header is re-emitted at the top of the next
     * one. The same header View instance may therefore appear in two pages, which is safe because
     * only one page is ever attached at a time.
     */
    private fun buildPages(cells: List<Cell>, innerWidth: Int, innerHeight: Int): List<List<View>> {
        if (cells.isEmpty()) return emptyList()

        val widthSpec = View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (cell in cells) {
            cell.view.measure(widthSpec, heightSpec)
            val lp = cell.view.layoutParams as? ViewGroup.MarginLayoutParams
            cell.bottomMargin = lp?.bottomMargin ?: 0
            cell.height = cell.view.measuredHeight + (lp?.topMargin ?: 0) + cell.bottomMargin
        }

        val pages = mutableListOf<List<View>>()
        var current = mutableListOf<View>()
        var used = 0
        var activeHeader: Cell? = null   // the group we are inside
        var pendingHeader: Cell? = null  // …and whether it still owes this page its label

        for (cell in cells) {
            if (cell.isHeader) {
                activeHeader = cell
                pendingHeader = cell
                continue
            }
            var headerCost = pendingHeader?.height ?: 0
            // The candidate is measured *without* its bottom margin, because if it lands last on
            // this page that margin is trailing whitespace. `used` still accumulates the full
            // height — a cell only keeps its margin once something follows it. `used > 0`
            // guarantees progress: a single row taller than the band still gets placed rather than
            // looping forever on a page it can never fit.
            if (used > 0 && used + headerCost + cell.height - cell.bottomMargin > innerHeight) {
                pages += current
                current = mutableListOf()
                used = 0
                pendingHeader = activeHeader
                headerCost = pendingHeader?.height ?: 0
            }
            pendingHeader?.let {
                current += it.view
                used += it.height
                pendingHeader = null
            }
            current += cell.view
            used += cell.height
        }
        if (current.isNotEmpty()) pages += current
        // In px, not dp: the packing arithmetic is integer pixels, and rounding to dp hid a
        // one-pixel overflow that cost a whole row.
        Slog.d("TodaySection") {
            "${ui.tvSectionTitle.text}: band=${innerHeight}px " +
                "cells=[${cells.joinToString(",") {
                    "${if (it.isHeader) "H" else "R"}${it.height}/${it.bottomMargin}"
                }}] pages=${pages.map { it.size }}"
        }
        return pages
    }

    // ── Measurement ────────────────────────────────────────────────────────────

    /**
     * Run [block] with the list's usable width and height, once the host reports real dimensions.
     *
     * The host starts unmeasured on first entry, and on a tabbed device a section that isn't the
     * open tab has no size at all until its tab is chosen — so this waits rather than guessing, via
     * a self-removing listener (the same bootstrap the day window's card grid uses). The height
     * comes from the list's parent frame: the list itself is `wrap_content` and would report the
     * height of whatever is already in it.
     */
    private fun whenMeasured(mine: Int, block: (Int, Int) -> Unit) {
        val host = ui.sectionList
        val frame = host.parent as View

        fun usable(): Pair<Int, Int>? {
            val w = host.width - host.paddingStart - host.paddingEnd
            val h = frame.height - host.paddingTop - host.paddingBottom
            return if (w > 0 && h > 0) w to h else null
        }

        usable()?.let { (w, h) -> block(w, h); return }

        host.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val (w, h) = usable() ?: return
                    host.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (mine != token) return
                    block(w, h)
                }
            },
        )
    }
}
