package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TreeMap

/**
 * The pad's pages — in memory, over [ScratchStore] (arc 11 / J4, on rows since arc 22 / X2). The
 * screen owns the paper and the chrome; this owns *what is on the pages* and when it reaches the
 * store.
 *
 * **The split of threads is deliberate.** Mutations ([addStroke], [erase], [move]) are synchronous
 * and run on Main, straight out of the g-paper callbacks: they touch only the in-memory page, so a
 * pen-up never waits on IO. Everything that reaches the store ([load], [goTo], [insert],
 * [deleteCurrent], [flushUntilClean], the replays) is `suspend` and hops to [Dispatchers.IO] for
 * the store call itself. The screen serialises the suspending half behind one mutex, exactly as the
 * notebook serialises its page ops.
 *
 * **The page is a `TreeMap` keyed on the stroke's `"order"`.** That column is the writing order and
 * it is load-bearing: recognition and every render read ink as a sequence, and an erase that came
 * back at the end rather than in place would change the page. Orders are unique per page and
 * monotone — a new stroke takes `last + 1` — so a restore can put a stroke back at the order it
 * held and nothing can have taken it. "Monotone" is a **high-water mark** ([highWater]), not the
 * last key in the map: erasing the tail stroke lowers the last key, and a new stroke that reused
 * its order would collide with the erased one when an undo brought it back.
 *
 * **What is unwritten is an op log, not a dirty flag.** Each edit records one entry per stroke id —
 * a `Put` (the row as it should now read) or a `Drop` — in a [LinkedHashMap], so a second edit to
 * the same stroke coalesces onto the first and a flush is one statement per touched stroke rather
 * than a re-encode of the page. Arc 11's whole-page blob, its byte accounting and its 4 MiB
 * ceiling are gone with it.
 *
 * **Re-flush until clean.** [flushUntilClean] snapshots the log and clears it *before* the IO hop,
 * then loops while it has been re-dirtied: a stroke committed during a write would otherwise be in
 * a snapshot that was already sent. A failed flush merges its snapshot back **under** anything
 * newer and rethrows. [goTo] reads the target page **first** and flushes the departing one
 * **second**, so the swap itself has no suspension point for a commit to fall into.
 *
 * **A received placement (J5) never lands here.** `ScratchStore.receive` writes it on the Binder
 * thread before the screen exists; the document simply [load]s what is already in the store. What
 * the screen does record is one undo entry, and the two arms it replays through are here.
 */
class ScratchDocument(
    private val store: ScratchStore,
    /** The paper surface in px — the size a page with no recorded size of its own takes. */
    private val surfaceSize: () -> Pair<Float, Float>,
) {

    /** One unwritten change to one stroke row. */
    private sealed interface Op {
        /** The row as it should now read — an added stroke, a moved one, a restored one. */
        class Put(val stroke: Stroke, val order: Long) : Op

        /** The row should not be there. `DELETE` tolerates a row that never landed, so an
         *  add-then-erase inside one flush window is safely one `DELETE`. */
        object Drop : Op
    }

    var pageIds: List<String> = emptyList()
        private set

    var currentPageId: String = ""
        private set

    /** The current page, `"order"` → stroke. Iteration order IS the writing order. */
    private val page = TreeMap<Long, Stroke>()

    /** id → the order it sits at, so an edit named by id can find its row. */
    private val orders = HashMap<String, Long>()

    /** The unwritten changes, coalesced per stroke id, in the order they were first made. */
    private val ops = LinkedHashMap<String, Op>()

    /** The page's own width/height is unwritten (it only just learned it). */
    private var sizeDirty = false

    /** One past the highest order this page has held since it was loaded — never lowered by an
     *  erase, so an order a live undo entry remembers can never be handed out again. */
    private var highWater = 0L

    var pageWidth: Float = 0f
        private set
    var pageHeight: Float = 0f
        private set

    val strokes: List<Stroke> get() = page.values.toList()
    val pageCount: Int get() = pageIds.size
    val pageIndex: Int get() = pageIds.indexOf(currentPageId).coerceAtLeast(0)
    val pageNumber: Int get() = pageIndex + 1
    val hasUnsavedChanges: Boolean get() = ops.isNotEmpty() || sizeDirty

    /** The order [id] sits at on the current page, or null if it is not on it. */
    fun orderOf(id: String): Long? = orders[id]

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
        if (id == currentPageId) return
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
        val beforeCurrent = currentPageId
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
        val deletedId = currentPageId
        val ink = currentInk()
        val (rest, landing) = withContext(Dispatchers.IO) { store.deletePage(before, deletedId) }
        pageIds = rest
        // The delete already dropped the strokes, so a lone page comes back blank — which is the point.
        applyPage(landing, withContext(Dispatchers.IO) { store.readPage(landing) })
        return ScratchAction.Page(before, deletedId, rest, landing, deletedId, ink)
    }

    // ── Mutations (Main, synchronous) ────────────────────────────────────────

    /** Take one committed stroke, at the end of the page's writing order. */
    fun addStroke(stroke: Stroke) {
        put(stroke, nextOrder())
    }

    /** Take a whole set at once — a received placement's redo (J5), appended in the order given.
     *  Ids are the caller's (they were minted when the ink arrived and never change). */
    fun addStrokes(strokes: List<Stroke>) {
        for (s in strokes) put(s, nextOrder())
    }

    /** Put strokes back at the orders they held — a [ScratchAction.Pasted] redo. */
    fun addStrokesAt(strokes: List<Stroke>, orders: List<Long>) {
        require(strokes.size == orders.size) { "${strokes.size} strokes for ${orders.size} orders" }
        for (i in strokes.indices) put(strokes[i], orders[i])
    }

    /** Drop [ids]; returns the undo action, or null when nothing of ours was in the set. */
    fun erase(ids: Collection<String>): ScratchAction.Erased? {
        if (ids.isEmpty()) return null
        val entries = ArrayList<ScratchAction.Erased.Entry>(ids.size)
        for (id in ids) {
            val order = orders[id] ?: continue
            val stroke = page[order] ?: continue
            entries += ScratchAction.Erased.Entry(order, stroke)
        }
        if (entries.isEmpty()) return null
        entries.sortBy { it.order }
        for (e in entries) removeStroke(e.stroke.id)
        return ScratchAction.Erased(currentPageId, entries)
    }

    /** Translate [ids] by ([dx], [dy]); each moved stroke's row is rewritten at the order it holds.
     *  Returns the undo action, or null when nothing moved. */
    fun move(ids: Collection<String>, dx: Float, dy: Float): ScratchAction.Moved? {
        val touched = translate(ids, dx, dy)
        if (touched.isEmpty()) return null
        return ScratchAction.Moved(currentPageId, touched, dx, dy)
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Write the current page until it stays written. The op log is snapshotted and cleared *before*
     * the IO hop, so a stroke that commits during the write re-dirties the page and takes another
     * pass; the guard bounds a pathological writer, and what it leaves behind the next debounce
     * picks up. A failure merges the snapshot back **under** anything recorded since (a newer entry
     * for the same stroke wins — it already describes the row's latest state) and rethrows
     * [StoreUnavailable]; because every statement is idempotent, the retry converges.
     */
    suspend fun flushUntilClean() {
        var pass = 0
        while (hasUnsavedChanges) {
            if (pass++ >= MAX_FLUSH_PASSES) {
                Slog.d(TAG) { "flush still dirty after $MAX_FLUSH_PASSES passes — leaving it to the next save" }
                return
            }
            val pageId = currentPageId
            val snapshot = LinkedHashMap(ops)
            val sizeSnapshot = sizeDirty
            val statements = statementsFor(pageId, snapshot, sizeSnapshot)
            ops.clear()
            sizeDirty = false
            try {
                withContext(Dispatchers.IO) { store.execAll(statements) }
            } catch (t: Throwable) {
                for ((id, op) in snapshot) if (id !in ops) ops[id] = op
                sizeDirty = sizeDirty || sizeSnapshot
                throw t
            }
            Slog.d(TAG) { "flushed ${statements.size} statement(s)" }
        }
    }

    /** The current page as it stands (J5) — what a received new page's redo has to put back, and
     *  what a delete's undo carries. */
    fun currentInk(): PageInk = PageInk(pageWidth, pageHeight, page.map { it.key to it.value })

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
            is ScratchAction.Drew -> {
                if (!goToLiving(a.pageId)) return
                removeStroke(a.stroke.id)
                flushUntilClean()
            }
            is ScratchAction.Erased -> {
                if (!goToLiving(a.pageId)) return
                for (e in a.entries) put(e.stroke, e.order)
                flushUntilClean()
            }
            is ScratchAction.Moved -> {
                if (!goToLiving(a.pageId)) return
                translate(a.ids, -a.dx, -a.dy)
                flushUntilClean()
            }
            is ScratchAction.Pasted -> {
                if (!goToLiving(a.pageId)) return
                for (s in a.strokes) removeStroke(s.id)
                flushUntilClean()
            }
            is ScratchAction.Page -> replayPages(a.before, a.beforeCurrent, a.pageId, a.ink)
        }
    }

    /** Re-apply [a] — [revert]'s mirror. */
    suspend fun reapply(a: ScratchAction) {
        when (a) {
            is ScratchAction.Drew -> {
                if (!goToLiving(a.pageId)) return
                addStroke(a.stroke)
                flushUntilClean()
            }
            is ScratchAction.Erased -> {
                if (!goToLiving(a.pageId)) return
                for (e in a.entries) removeStroke(e.stroke.id)
                flushUntilClean()
            }
            is ScratchAction.Moved -> {
                if (!goToLiving(a.pageId)) return
                translate(a.ids, a.dx, a.dy)
                flushUntilClean()
            }
            is ScratchAction.Pasted -> {
                if (!goToLiving(a.pageId)) return
                addStrokesAt(a.strokes, a.orders)
                flushUntilClean()
            }
            // Redo writes the page's ink **in the `after` state**: null for an insert (it lands
            // blank) and for a delete (there is nothing to keep), and the arrived ink for a received
            // new page. The `before`/`after` id lists are what tell the three apart.
            is ScratchAction.Page -> replayPages(a.after, a.afterCurrent, a.pageId, a.afterInk)
        }
    }

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
        currentPageId = ""
        applyPage(current, withContext(Dispatchers.IO) { store.readPage(current) })
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun statementsFor(pageId: String, snapshot: Map<String, Op>, size: Boolean): List<Statement> {
        val statements = ArrayList<Statement>(snapshot.size + 1)
        val now = System.currentTimeMillis()
        if (size) statements += ScratchSql.sizePage(pageId, pageWidth, pageHeight, now)
        for ((id, op) in snapshot) {
            statements += when (op) {
                is Op.Put -> ScratchSql.putStroke(pageId, op.order, op.stroke)
                Op.Drop -> ScratchSql.dropStroke(id)
            }
        }
        return statements
    }

    private fun applyPage(id: String, ink: PageInk) {
        page.clear()
        orders.clear()
        ops.clear()
        sizeDirty = false
        currentPageId = id
        pageWidth = ink.width
        pageHeight = ink.height
        highWater = 0L
        for ((order, stroke) in ink.strokes) {
            // Two stored rows at one order (never written by this code — but a row is a row): the
            // second is moved past the end and re-put, rather than silently hiding the first.
            val at = if (page.containsKey(order)) highWater else order
            page[at] = stroke
            orders[stroke.id] = at
            highWater = maxOf(highWater, at + 1)
            if (at != order) ops[stroke.id] = Op.Put(stroke, at)
        }
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

    /** The next writing order on this page: the high-water mark (0 on a page that never held ink). */
    private fun nextOrder(): Long = highWater

    private fun put(stroke: Stroke, order: Long) {
        val previous = orders.put(stroke.id, order)
        if (previous != null && previous != order) page.remove(previous)
        page[order] = stroke
        highWater = maxOf(highWater, order + 1)
        ops[stroke.id] = Op.Put(stroke, order)
    }

    private fun removeStroke(id: String): Boolean {
        val order = orders.remove(id) ?: return false
        page.remove(order)
        // Always a Drop, never "forget the Put": a Put may be a move of a row that is already
        // stored, and dropping the entry would leave that row behind. DELETE tolerates the rest.
        ops[id] = Op.Drop
        return true
    }

    /** Translate what of [ids] is on this page; returns the ids actually moved. */
    private fun translate(ids: Collection<String>, dx: Float, dy: Float): List<String> {
        val moved = ArrayList<String>(ids.size)
        for (id in ids) {
            val order = orders[id] ?: continue
            val stroke = page[order] ?: continue
            put(stroke.translated(dx, dy), order)
            moved += id
        }
        return moved
    }

    private companion object {
        const val TAG = "ScratchDocument"

        /** Enough passes to outrun a hand that keeps writing; beyond it the next debounce takes over. */
        const val MAX_FLUSH_PASSES = 8
    }
}
