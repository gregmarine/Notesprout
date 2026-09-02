package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.ink.InkAction
import com.symmetricalpalmtree.notesproutsn.ink.InkDocument
import com.symmetricalpalmtree.notesproutsn.ink.InkPage
import com.symmetricalpalmtree.notesproutsn.ink.PageInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TreeMap

/**
 * The pad's pages — in memory, over [ScratchStore] (arc 11 / J4, on rows since arc 22 / X2, over
 * `:ext-ink` since arc 23 / Y1). The screen owns the paper and the chrome; this owns *which* page is
 * showing, the page list and its structural edits, and the page's size; **what is on the page** —
 * the [TreeMap] of strokes, the op log, the re-flush rule and the four stroke-level replays — is
 * [InkDocument]'s, shared with the calendar so the two never drift.
 *
 * **The split of threads is deliberate.** Mutations ([addStroke], [erase], [move]) are synchronous
 * and run on Main, straight out of the g-paper callbacks: they touch only the in-memory page, so a
 * pen-up never waits on IO. Everything that reaches the store ([load], [goTo], [insert],
 * [deleteCurrent], [flushUntilClean], the replays) is `suspend` and hops to [Dispatchers.IO] for
 * the store call itself. The screen serialises the suspending half behind one mutex, exactly as the
 * notebook serialises its page ops.
 *
 * **The page size is the pad's one extra unwritten thing.** A page stored as `0 × 0` learns its
 * size at first layout and owes the row one `UPDATE`; it rides ahead of the strokes in the next
 * flush ([InkDocument.flushUntilClean]'s `extraDirty`), and comes back if that write fails.
 *
 * [goTo] reads the target page **first** and flushes the departing one **second**, so the swap
 * itself has no suspension point for a commit to fall into.
 *
 * **A received placement (J5) never lands here.** `ScratchStore.receive` writes it on the Binder
 * thread before the screen exists; the document simply [load]s what is already in the store. What
 * the screen does record is one undo entry, and the two arms it replays through are here.
 */
class ScratchDocument(
    private val store: ScratchStore,
    /** The paper surface in px — the size a page with no recorded size of its own takes. */
    private val surfaceSize: () -> Pair<Float, Float>,
) : InkPage {

    private val ink = InkDocument(ScratchSql, TAG)

    var pageIds: List<String> = emptyList()
        private set

    override val pageId: String get() = ink.pageId

    /** The page's own width/height is unwritten (it only just learned it). */
    private var sizeDirty = false

    override var pageWidth: Float = 0f
        private set
    override var pageHeight: Float = 0f
        private set

    override val strokes: List<Stroke> get() = ink.strokes
    val pageCount: Int get() = pageIds.size
    val pageIndex: Int get() = pageIds.indexOf(pageId).coerceAtLeast(0)
    val pageNumber: Int get() = pageIndex + 1
    val hasUnsavedChanges: Boolean get() = ink.hasUnsavedChanges || sizeDirty

    /** The order [id] sits at on the current page, or null if it is not on it. */
    fun orderOf(id: String): Long? = ink.orderOf(id)

    // ── Loading ──────────────────────────────────────────────────────────────

    /** First run creates one blank page (in [ScratchStore.load]); we land on the remembered one. */
    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        pageIds = loaded.ids
        applyPage(loaded.currentId, withContext(Dispatchers.IO) { store.readPage(loaded.currentId) })
    }

    /**
     * Show [id]. The target is read **before** the departing page is flushed, so that once the flush
     * returns the swap runs with no suspension point in it — a commit landing mid-swap would
     * otherwise be recorded against a page that is no longer on the paper.
     */
    suspend fun goTo(id: String) {
        if (id == pageId) return
        val next = withContext(Dispatchers.IO) { store.readPage(id) }
        flushUntilClean()
        applyPage(id, next)
        withContext(Dispatchers.IO) { store.setCurrent(id) }
    }

    /** Flip by page index; out-of-range indices are ignored (the arrows no-op at a bound). */
    suspend fun goToIndex(index: Int) {
        val id = pageIds.getOrNull(index) ?: return
        goTo(id)
    }

    // ── Structural ───────────────────────────────────────────────────────────

    suspend fun insert(after: Boolean): ScratchAction.Page {
        flushUntilClean()
        val before = pageIds
        val beforeCurrent = pageId
        val (next, newId) = withContext(Dispatchers.IO) {
            if (after) store.insertPage(before, beforeCurrent) else store.insertPageBefore(before, beforeCurrent)
        }
        pageIds = next
        applyPage(newId, PageInk.EMPTY)
        return ScratchAction.Page(before, beforeCurrent, next, newId, newId, ink = null)
    }

    /**
     * Delete the current page. Its ink is taken from memory **before** the delete so undo can put it
     * back — the flush just above means memory and the store agree, and a page can be far bigger
     * than one read. The last page is emptied rather than removed ([ScratchPages.delete]'s rule),
     * and the action that records that has `before == after` and still carries the ink.
     */
    suspend fun deleteCurrent(): ScratchAction.Page {
        flushUntilClean()
        val before = pageIds
        val deletedId = pageId
        val ink = currentInk()
        val (rest, landing) = withContext(Dispatchers.IO) { store.deletePage(before, deletedId) }
        pageIds = rest
        // The delete already dropped the strokes, so a lone page comes back blank — which is the point.
        applyPage(landing, withContext(Dispatchers.IO) { store.readPage(landing) })
        return ScratchAction.Page(before, deletedId, rest, landing, deletedId, ink)
    }

    // ── Mutations (Main, synchronous) ────────────────────────────────────────

    /** Take one committed stroke, at the end of the page's writing order. */
    override fun addStroke(stroke: Stroke) = ink.addStroke(stroke)

    /** Drop [ids]; returns the undo action, or null when nothing of ours was in the set. */
    override fun erase(ids: Collection<String>): InkAction.Erased? = ink.erase(ids)

    /** Translate [ids] by ([dx], [dy]). Returns the undo action, or null when nothing moved. */
    override fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? = ink.move(ids, dx, dy)

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Write the current page until it stays written ([InkDocument.flushUntilClean]). The page's
     * size, when it is owed, leads the first pass and is put back if that pass fails; the store
     * call itself runs on IO.
     */
    override suspend fun flushUntilClean(maxPasses: Int): Boolean =
        ink.flushUntilClean(extraDirty = { sizeDirty }, maxPasses = maxPasses) { statements ->
            val pageId = ink.pageId
            val size = sizeDirty
            sizeDirty = false
            val all: List<Statement> =
                if (size) listOf(ScratchSql.sizePage(pageId, pageWidth, pageHeight, System.currentTimeMillis())) + statements
                else statements
            try {
                withContext(Dispatchers.IO) { store.execAll(all) }
            } catch (t: Throwable) {
                sizeDirty = sizeDirty || size
                throw t
            }
        }

    /** The current page as it stands (J5) — what a received new page's redo has to put back, and
     *  what a delete's undo carries. */
    fun currentInk(): PageInk = PageInk(pageWidth, pageHeight, ink.entries())

    /** The page's size once the surface has been laid out — a page stored as `0 × 0` takes it. */
    fun adoptSurfaceSize() {
        if (pageWidth > 0f && pageHeight > 0f) return
        val (w, h) = surfaceSize()
        if (w <= 0f || h <= 0f) return
        pageWidth = w
        pageHeight = h
        sizeDirty = true
    }

    // ── Undo / redo replay ───────────────────────────────────────────────────

    /**
     * Reverse [a]. Every replay lands the document on the affected page and leaves the store written,
     * so what the screen reloads afterwards is what a reopen would show.
     */
    suspend fun revert(a: ScratchAction) {
        when (a) {
            is ScratchAction.Ink -> {
                if (!goToLiving(a.action.pageId)) return
                ink.revert(a.action)
                flushUntilClean()
            }
            is ScratchAction.Page -> replayPages(a.before, a.beforeCurrent, a.pageId, a.ink)
        }
    }

    /** Re-apply [a] — [revert]'s mirror. */
    suspend fun reapply(a: ScratchAction) {
        when (a) {
            is ScratchAction.Ink -> {
                if (!goToLiving(a.action.pageId)) return
                ink.reapply(a.action)
                flushUntilClean()
            }
            // Redo writes the page's ink **in the `after` state**: null for an insert (it lands
            // blank) and for a delete (there is nothing to keep), and the arrived ink for a received
            // new page. The `before`/`after` id lists are what tell the three apart.
            is ScratchAction.Page -> replayPages(a.after, a.afterCurrent, a.pageId, a.afterInk)
        }
    }

    /** The ink-only forms, for callers holding a bare [InkAction]. */
    suspend fun revert(a: InkAction) = revert(ScratchAction.Ink(a))
    suspend fun reapply(a: InkAction) = reapply(ScratchAction.Ink(a))

    /** Go to [id] only if it is still a page. A stroke action whose page has since been deleted has
     *  nothing to reverse — the delete's own entry is what puts that page back, and it sits below
     *  this one on the stack. */
    private suspend fun goToLiving(id: String): Boolean {
        if (id !in pageIds) {
            Slog.d(TAG) { "replay skipped: page $id is gone" }
            return false
        }
        goTo(id)
        return true
    }

    /**
     * The one shape both directions of a [ScratchAction.Page] replay take, as one statement list:
     * the affected page is re-created and re-inked when it exists in that state, or deleted (cascade)
     * when it does not; then every position is renumbered and the current page named.
     *
     * `sizePage` is emitted **only** with an ink, so a state that carries none can never write
     * `0 × 0` over a page that already knows its size — which is exactly the lone-page delete's redo.
     */
    private suspend fun replayPages(ids: List<String>, current: String, pageId: String, ink: PageInk?) {
        // Anything typed since is part of the state being reversed — get it down first.
        flushUntilClean()
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>(ids.size + (ink?.strokes?.size ?: 0) + 4)
        if (pageId in ids) {
            statements += ScratchSql.insertPage(pageId, ids.indexOf(pageId), ink?.width ?: 0f, ink?.height ?: 0f, now)
            if (ink != null) statements += ScratchSql.sizePage(pageId, ink.width, ink.height, now)
            statements += ScratchSql.clearPage(pageId)
            ink?.strokes?.forEach { (order, stroke) -> statements += ScratchSql.putStroke(pageId, order, stroke) }
        } else {
            statements += ScratchSql.deletePage(pageId)
        }
        ids.forEachIndexed { i, id -> statements += ScratchSql.position(id, i) }
        statements += ScratchSql.setCurrent(current)
        withContext(Dispatchers.IO) { store.execAll(statements) }
        pageIds = ids
        // Force the reload: the landing page may be the one we are already on (a delete of the last
        // remaining page lands back on itself), and its ink has just changed underneath us.
        applyPage(current, withContext(Dispatchers.IO) { store.readPage(current) })
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun applyPage(id: String, page: PageInk) {
        ink.reset(id, page.strokes)
        sizeDirty = false
        pageWidth = page.width
        pageHeight = page.height
        if (pageWidth <= 0f || pageHeight <= 0f) {
            val (w, h) = surfaceSize()
            if (w > 0f && h > 0f) {
                pageWidth = w
                pageHeight = h
                // The page has just learned its size — the row has to be told.
                sizeDirty = true
            }
        }
    }

    private companion object {
        const val TAG = "ScratchDocument"
    }
}
