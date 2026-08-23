package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * Every decision the link picker makes that is not a view (arc 6 / K2) — which mode a prefill
 * opens in, which style latch is down, how the page cards are numbered, and whether the current
 * selection composes a payload at all. [LinkPickerActivity] is then only chrome and wiring.
 *
 * Pure Kotlin — no Android, JVM-tested. Two rules are worth naming because they are easy to get
 * subtly wrong and impossible to see in a screenshot:
 *
 *  - **Numbering never drifts.** Page positions are computed over the notebook's FULL page list
 *    and only *then* is the current page dropped, so the page after the excluded one is still
 *    "Page 4" — the number a user counts to on paper, not an index into a filtered list.
 *  - **A link never targets its own home.** A link to the notebook it already lives in would
 *    navigate nowhere; the picker hides the current notebook, and [composeOk] refuses it anyway,
 *    because "hidden from the grid" is a chrome fact and this is the contract.
 */
object LinkPickerModel {

    /** The picker's three shelves, in the order they appear in the mode row. */
    enum class PickMode { THIS_NOTEBOOK, NOTEBOOK, NOTEBOOK_PAGE }

    /**
     * The mode a prefill opens in — [PickMode.THIS_NOTEBOOK] for a fresh create, and for anything
     * unusable: a prefill is cosmetic, and an undecodable payload is a *silently* fresh picker,
     * never a dialog (the user asked to edit a link, not to be told about a byte string).
     */
    fun modeFor(decoded: LinkPayload.Decoded?): PickMode = when (decoded?.kind) {
        LinkPayload.KIND_NOTEBOOK -> PickMode.NOTEBOOK
        LinkPayload.KIND_NOTEBOOK_PAGE -> PickMode.NOTEBOOK_PAGE
        else -> PickMode.THIS_NOTEBOOK          // KIND_PAGE, and every unusable prefill
    }

    /** The style latch a prefill puts down — underline by default (the locked chrome default). */
    fun chromeFor(decoded: LinkPayload.Decoded?): Int = decoded?.chrome ?: LinkPayload.CHROME_UNDERLINE

    /**
     * The page grid's cards: each page with its **1-based position in the whole notebook**, with
     * [excludePageId] dropped afterwards (the current page, in the This-notebook grid). Null
     * excludes nothing — what a foreign notebook's grid wants, where no page is "current".
     */
    fun pageCards(pages: List<PickerPage>, excludePageId: String?): List<Pair<PickerPage, Int>> =
        pages.mapIndexed { index, page -> page to index + 1 }
            .filter { (page, _) -> page.id != excludePageId }

    /** Which grid page holds item [itemIndex] — how a prefilled selection on page 3 gets shown
     *  rather than looking like nothing was ever selected. Out-of-range answers page 0. */
    fun gridPageOf(itemIndex: Int, cardsPerPage: Int): Int =
        if (itemIndex < 0 || cardsPerPage <= 0) 0 else itemIndex / cardsPerPage

    // ── Create-in-picker (K3) ────────────────────────────────────────────────

    /**
     * Where a picker-created page lands in [pageIds]: **at** the anchor for `before`, one past it
     * otherwise. A null anchor — nothing selected — appends, and so does an anchor that is no
     * longer in the list: a page deleted underneath the picker must not silently redirect the
     * insert to whatever now sits at its old index.
     */
    fun insertIndexFor(pageIds: List<String>, anchorId: String?, before: Boolean): Int {
        val at = anchorId?.let { pageIds.indexOf(it) } ?: -1
        if (at < 0) return pageIds.size
        return if (before) at else at + 1
    }

    /**
     * Index of the page a created page inherits its paper — template and authored size — from: the
     * anchor when it is still there, else the last page, so an appended page continues the paper the
     * notebook already has. `-1` only for an empty list, which the caller refuses: a notebook always
     * has at least one page, and a blank page with no size is not a page.
     */
    fun inheritIndexFor(pageIds: List<String>, anchorId: String?): Int {
        val at = anchorId?.let { pageIds.indexOf(it) } ?: -1
        return if (at >= 0) at else pageIds.lastIndex
    }

    /**
     * Which create buttons a picker state shows. Never "disabled" — a disabled control is invisible
     * on e-ink, so a button that cannot apply here is simply not on screen.
     */
    data class CreateButtons(val newPage: Boolean, val newNotebookAndFolder: Boolean)

    /**
     * A page grid offers New page; a browse offers New notebook and New folder. The two never
     * overlap, because they are the two things one grid can be: [PickMode.NOTEBOOK_PAGE] is a
     * browse until a notebook is [drilled] into, and then it is a page grid.
     */
    fun createButtons(mode: PickMode, drilled: Boolean): CreateButtons = when (mode) {
        PickMode.THIS_NOTEBOOK -> CreateButtons(newPage = true, newNotebookAndFolder = false)
        PickMode.NOTEBOOK -> CreateButtons(newPage = false, newNotebookAndFolder = true)
        PickMode.NOTEBOOK_PAGE ->
            if (drilled) CreateButtons(newPage = true, newNotebookAndFolder = false)
            else CreateButtons(newPage = false, newNotebookAndFolder = true)
    }

    /**
     * The payload OK would return, or **null when there is nothing to compose**: no target picked
     * yet, a mode whose second half is still missing (a notebook drilled but no page chosen), or
     * the self-target the picker's exclusions already make unreachable.
     *
     * Null is the picker's cue to explain rather than act — never a disabled OK button (a disabled
     * control is invisible on e-ink) and never a silent no-op.
     */
    fun composeOk(
        mode: PickMode,
        chrome: Int,
        currentNotebookId: String,
        selectedNotebookId: String?,
        selectedPageId: String?,
    ): String? {
        val (kind, notebookId, pageId) = when (mode) {
            PickMode.THIS_NOTEBOOK -> Triple(LinkPayload.KIND_PAGE, null, selectedPageId ?: return null)
            PickMode.NOTEBOOK ->
                Triple(LinkPayload.KIND_NOTEBOOK, selectedNotebookId ?: return null, null)
            PickMode.NOTEBOOK_PAGE ->
                Triple(
                    LinkPayload.KIND_NOTEBOOK_PAGE,
                    selectedNotebookId ?: return null,
                    selectedPageId ?: return null,
                )
        }
        if (notebookId == currentNotebookId) return null   // a link home navigates nowhere
        // The ids come from the index and from `.soil` rows — untrusted enough that a malformed one
        // must not throw out of an OK tap. `encode` rejects a caller bug; here that is "incomplete".
        return runCatching { LinkPayload.encode(chrome, kind, notebookId, pageId) }.getOrNull()
    }
}
