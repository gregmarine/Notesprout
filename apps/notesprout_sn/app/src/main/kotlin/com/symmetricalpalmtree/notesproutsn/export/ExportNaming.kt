package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.CalendarDates
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * What an export is **called** — the filename offered to the SAF picker, and the display name the
 * [com.symmetricalpalmtree.notesproutsn.extension.ExportSpec] carries for formats that can hold a
 * title (arc 15 / E1). Pure, JVM-tested: naming is a rule, not a screen.
 *
 * The sanitize rule is og Notesprout's, verbatim (`docs/full-notebook-export.md`), so a file
 * exported by SN is named the way the family has always named one: the notebook's **current index
 * display name** with everything outside `[a-zA-Z0-9_\-. ]` removed and the result trimmed —
 * **spaces inside are kept**, because a notebook called "Meeting notes" should not export as
 * "Meetingnotes". If nothing usable survives, or what survives is `.` or `..` (a name a filesystem
 * reads as a directory), the notebook's **UUID** is the name instead: an id is always a legal
 * filename and is never empty.
 *
 * Two callers, one base, on purpose: the file on disk and the name inside the file must agree, and
 * the way to guarantee that is for both to come from [base].
 */
object ExportNaming {

    /** Everything og strips. Note the space is *not* in the class — it survives. */
    private val ILLEGAL = Regex("[^a-zA-Z0-9_\\-. ]")

    /**
     * The sanitized filename stem for [displayName], falling back to [notebookId] when the name
     * strips down to nothing usable. Strip first, then trim: " ***name*** " must become "name",
     * not " name " — the stripping is what exposes the outer spaces.
     */
    fun base(displayName: String, notebookId: String): String {
        val cleaned = ILLEGAL.replace(displayName, "").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") notebookId else cleaned
    }

    /**
     * What the picker is offered: [base] + "." + the exporter's declared extension (already
     * `[a-z0-9]{1..12}` by [com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo]'s
     * constructor — an exporter cannot smuggle a path or a second extension through it).
     */
    fun suggestedFileName(displayName: String, notebookId: String, fileExtension: String): String =
        fileName(base(displayName, notebookId), fileExtension)

    /** [suggestedFileName] over a stem already made — [base] or [pageStem]. */
    fun fileName(stem: String, fileExtension: String): String = stem + "." + fileExtension

    /**
     * The stem of a **one-page** export (arc 30 / PE2, the user's phase-start call): the notebook's
     * [base], a hyphen, then the page's **topmost heading** — the Contents / link-picker rule
     * ([com.symmetricalpalmtree.notesproutsn.notebook.PageLabels.titleOf]: topmost by `(y, x)`,
     * prefix stripped, loose or link-wrapped) — sanitized by the same rule as the name and capped
     * at [MAX_TITLE_CHARS]; or, when the page has no heading (or one that strips to nothing),
     * `page N` with the page's 1-based position. A plain ASCII hyphen because the sanitize would
     * strip a dash of any other kind. [pageNumber] below 1 (the page could not be placed) names
     * the notebook alone: a filename must never say "page 0".
     *
     * [sketch] names the **sketch page** that follows its ink page in a bundle (arc 43 / K7,
     * decision 5 + the user's phase-start call): the ink page's own stem with ` sketch` appended,
     * so `Meeting notes - Agenda` and `Meeting notes - Agenda sketch` sort together in a folder and
     * say which is which without a second word for the page. The cap applies to the **title**, not
     * to the stem: the suffix is the app's word, not the user's, and truncating it would produce a
     * filename that lies about what is in it.
     */
    fun pageStem(
        displayName: String,
        notebookId: String,
        pageNumber: Int,
        pageTitle: String?,
        sketch: Boolean = false,
    ): String {
        val stem = base(displayName, notebookId)
        val title = pageTitle?.let { ILLEGAL.replace(it, "").trim() }?.take(MAX_TITLE_CHARS)?.trim()
        val page = when {
            !title.isNullOrEmpty() && title != "." && title != ".." -> "$stem - $title"
            pageNumber >= 1 -> "$stem - page $pageNumber"
            else -> stem
        }
        return if (sketch) page + SKETCH_SUFFIX else page
    }

    /** What a sketch page's stem ends with — a word, with its own space, never a separator the
     *  sanitize would strip. */
    private const val SKETCH_SUFFIX = " sketch"

    /**
     * One **bundle** page's name, as the bake read it (arc 43 / K7) — what the per-page delivery
     * turns into a filename, one entry per page of the bundle in its order.
     *
     * It replaced a bare `List<String?>` of titles the moment a bundle page stopped being the same
     * thing as a notebook page: with a sketch interleaved after its ink ([ExportRender]), the
     * file's place in the bundle is no longer its page's number, so the number has to travel beside
     * the title rather than be counted back out of the list's index.
     *
     * [title] carries two meanings by producer, as the list it replaced already did: for a notebook
     * it is the page's heading (null when it has none) and [pageStem] builds the filename around
     * it; for a **calendar** it is the plan's finished stem, used verbatim — a calendar page has no
     * heading to fall back on and no notebook to be prefixed with (see `ExportActivity.stemFor`).
     */
    data class PageName(
        /** The page's 1-based place in the **notebook**, or 0 when it has none. */
        val number: Int = 0,
        val title: String? = null,
        /** True for the sketch page that follows its ink page — the ` sketch` suffix. */
        val sketch: Boolean = false,
    )

    /**
     * The stem of a **calendar** export (arc 31 / HV4): `Calendar - September 2026` for a month,
     * `Calendar - Week of 2026-09-06` for a week (the week's Sunday, which is what the target's own
     * date already is), `Calendar - 2026-09-08` for a day. A day's two files add ` AM` / ` PM` —
     * [CalendarRenderPlan.stems]' job, because that suffix is about the *page*, not the period.
     *
     * There is no notebook here and nothing of the user's words: a calendar page is named by the
     * period it is, and every character this can produce is already inside the sanitize's class
     * (letters, digits, spaces and the ASCII hyphen), so a filename built from it is legal by
     * construction rather than by trimming.
     */
    fun calendarStem(target: CalendarTarget): String {
        val day = target.localDate
        return CALENDAR_PREFIX + when (target.kind) {
            CalendarTarget.KIND_MONTH -> CalendarDates.monthTitle(day)
            CalendarTarget.KIND_WEEK -> "Week of " + CalendarDates.format(day)
            else -> CalendarDates.format(day)
        }
    }

    /** What every calendar export is called before the period is added. */
    private const val CALENDAR_PREFIX = "Calendar - "

    /** The most of a heading a page export's filename carries — a heading is a line, a filename
     *  is a label. */
    const val MAX_TITLE_CHARS = 80

    /**
     * The `ExportSpec.notebookName` value — the same sanitized base, truncated to the spec's
     * [ExporterContract.MAX_NAME_CHARS] cap. The spec also forbids `/` and NUL, which the sanitize
     * already guarantees; the truncation is the only thing left to do, and it is done here rather
     * than at the call site so the spec's constructor can never be the thing that refuses an
     * export over a long name.
     */
    fun specName(displayName: String, notebookId: String): String =
        specNameOf(base(displayName, notebookId))

    /** [specName] over a stem already made, so the file on disk and the name inside it agree at
     *  page scope too. */
    fun specNameOf(stem: String): String = stem.take(ExporterContract.MAX_NAME_CHARS)
}
