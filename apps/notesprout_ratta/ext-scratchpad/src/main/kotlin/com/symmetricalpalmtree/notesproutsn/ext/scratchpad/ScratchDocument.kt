package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pad's pages — in memory, over [ScratchStore] (arc 11 / J4). The screen owns the paper and the
 * chrome; this owns *what is on the pages* and when it reaches the store, and it is where the
 * arc's three correctness rules live.
 *
 * **The split of threads is deliberate.** Mutations ([addStroke], [erase], [move]) are synchronous
 * and run on Main, straight out of the g-paper callbacks: they touch only the in-memory page, so a
 * pen-up never waits on IO. Everything that reaches the store ([load], [goTo], [insert],
 * [deleteCurrent], [flushUntilClean], the replays) is `suspend` and hops to
 * [Dispatchers.IO] for the store call itself. The screen serialises the suspending half behind one
 * mutex, exactly as the notebook serialises its page ops.
 *
 * **Re-flush until clean.** [flushUntilClean] snapshots the page and clears `dirty` *before* the IO
 * hop, then loops while it has been re-dirtied: a stroke committed during a write would otherwise be
 * in the map that was already encoded, and the next page turn would drop it. [goTo] reads the target
 * page **first** and flushes the departing one **second**, so the swap itself has no suspension
 * point for a commit to fall into.
 *
 * **The full rule.** `encodedBytes` is the exact size of what [ScratchPageCodec.encode] would
 * produce — `HEADER_BYTES + Σ strokeBytes` — kept per stroke, because stroke geometry is
 * zlib-compressed and "the same floats re-encode to the same size" is false. A stroke that would
 * cross `STORE_MAX_VALUE_BYTES` is refused whole ([Add.PAGE_FULL]); a move **re-measures** every
 * stroke it touched.
 *
 * **An unreadable page is a failure, not a blank page.** A page blob that will not decode marks the
 * page [isUnreadable]: it is shown empty, it accepts no ink, and it is never written — a blank save
 * over it would destroy what could not be read.
 */
class ScratchDocument(
    private val store: ScratchStore,
    /** The paper surface in px — the size a page with no recorded size of its own takes. */
    private val surfaceSize: () -> Pair<Float, Float>,
) {

    /** Why [addStroke] did or did not take the stroke. */
    enum class Add { OK, PAGE_FULL, UNREADABLE }

    var pageIds: List<String> = emptyList()
        private set

    var currentPageId: String = ""
        private set

    /** The current page, empty while it is [isUnreadable]. Insertion-ordered. */
    private val page = LinkedHashMap<String, Stroke>()

    /** Per-stroke encoded size, so a remove or a move can adjust [encodedBytes] without re-encoding
     *  the whole page (and so a move can never assume its old size still applies). */
    private val sizes = HashMap<String, Int>()

    var pageWidth: Float = 0f
        private set
    var pageHeight: Float = 0f
        private set

    var isUnreadable: Boolean = false
        private set

    private var encodedBytes: Int = ScratchPageCodec.HEADER_BYTES
    private var dirty = false

    /** Ids whose blob would not decode — see [isUnreadable]. Never cleared: a page does not heal. */
    private val unreadableIds = HashSet<String>()

    val strokes: List<Stroke> get() = page.values.toList()
    val pageCount: Int get() = pageIds.size
    val pageIndex: Int get() = pageIds.indexOf(currentPageId).coerceAtLeast(0)
    val pageNumber: Int get() = pageIndex + 1
    val hasUnsavedChanges: Boolean get() = dirty

    // ── Loading ──────────────────────────────────────────────────────────────

    /** First run creates one blank page (in [ScratchStore.load]); we land on the remembered one. */
    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        pageIds = loaded.ids
        applyPage(loaded.currentId, withContext(Dispatchers.IO) { readPage(loaded.currentId) })
    }

    /**
     * Show [id]. The target is read **before** the departing page is flushed, so that once the flush
     * returns the swap runs with no suspension point in it — a commit landing mid-swap would
     * otherwise be recorded against a page that is no longer on the paper.
     */
    suspend fun goTo(id: String) {
        if (id == currentPageId) return
        val next = withContext(Dispatchers.IO) { readPage(id) }
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
        applyPage(newId, null)
        withContext(Dispatchers.IO) { store.setCurrent(newId) }
        return ScratchAction.Page(before, beforeCurrent, next, newId, newId, blob = null)
    }

    /**
     * Delete the current page. The blob is read **before** the delete so undo can put the ink back;
     * the last page is emptied rather than removed ([ScratchPages.delete]'s rule), and the action
     * that records that has `before == after` and still carries the blob.
     */
    suspend fun deleteCurrent(): ScratchAction.Page {
        flushUntilClean()
        val before = pageIds
        val deletedId = currentPageId
        val blob = withContext(Dispatchers.IO) { store.readPage(deletedId) }
        val (rest, landing) = withContext(Dispatchers.IO) { store.deletePage(before, deletedId) }
        pageIds = rest
        // The delete already dropped the blob, so a lone page comes back blank — which is the point.
        applyPage(landing, withContext(Dispatchers.IO) { readPage(landing) })
        withContext(Dispatchers.IO) { store.setCurrent(landing) }
        return ScratchAction.Page(before, deletedId, rest, landing, deletedId, blob)
    }

    // ── Mutations (Main, synchronous) ────────────────────────────────────────

    /**
     * Take one committed stroke. Refuses — taking nothing — when the page would cross the store's
     * value cap or cannot be read; the screen removes the refused stroke from the paper and says why.
     */
    fun addStroke(stroke: Stroke): Add {
        if (isUnreadable) return Add.UNREADABLE
        val bytes = ScratchPageCodec.strokeBytes(stroke)
        if (encodedBytes + bytes > ExtensionContract.STORE_MAX_VALUE_BYTES) {
            Slog.d(TAG) { "page full: $encodedBytes + $bytes > ${ExtensionContract.STORE_MAX_VALUE_BYTES}" }
            return Add.PAGE_FULL
        }
        page[stroke.id] = stroke
        sizes[stroke.id] = bytes
        encodedBytes += bytes
        dirty = true
        return Add.OK
    }

    /** Drop [ids]; returns the undo action, or null when nothing of ours was in the set. */
    fun erase(ids: Collection<String>): ScratchAction.Erased? {
        if (ids.isEmpty()) return null
        val order = page.keys.toList()
        val entries = ArrayList<ScratchAction.Erased.Entry>(ids.size)
        for ((i, key) in order.withIndex()) {
            if (key !in ids) continue
            entries += ScratchAction.Erased.Entry(i, page.getValue(key))
        }
        if (entries.isEmpty()) return null
        for (e in entries) removeStroke(e.stroke.id)
        dirty = true
        return ScratchAction.Erased(currentPageId, entries)
    }

    /** Translate [ids] by ([dx], [dy]) — re-measuring each, because the same floats do not re-encode
     *  to the same number of bytes. Returns the undo action, or null when nothing moved. */
    fun move(ids: Collection<String>, dx: Float, dy: Float): ScratchAction.Moved? {
        val touched = translate(ids, dx, dy)
        if (touched.isEmpty()) return null
        dirty = true
        return ScratchAction.Moved(currentPageId, touched, dx, dy)
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Write the current page until it stays written. `dirty` is cleared *before* the IO hop, so a
     * stroke that commits during the write re-dirties the page and takes another pass; the guard
     * bounds a pathological writer, and what it leaves behind the next debounce picks up.
     *
     * Throws [StoreUnavailable] or [PageFullException] — the screen explains both and leaves.
     */
    suspend fun flushUntilClean() {
        var pass = 0
        while (dirty && !isUnreadable) {
            if (pass++ >= MAX_FLUSH_PASSES) {
                Slog.d(TAG) { "flush still dirty after $MAX_FLUSH_PASSES passes — leaving it to the next save" }
                return
            }
            val pageId = currentPageId
            val snapshot = page.values.toList()
            val w = pageWidth
            val h = pageHeight
            dirty = false
            withContext(Dispatchers.IO) { store.savePage(pageId, ScratchPageCodec.encode(w, h, snapshot)) }
        }
    }

    /** The page's size once the surface has been laid out — a page stored as `0 × 0` takes it. */
    fun adoptSurfaceSize() {
        if (pageWidth > 0f && pageHeight > 0f) return
        val (w, h) = surfaceSize()
        if (w <= 0f || h <= 0f) return
        pageWidth = w
        pageHeight = h
        if (page.isNotEmpty()) dirty = true
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
                if (removeStroke(a.stroke.id)) dirty = true
                flushUntilClean()
            }
            is ScratchAction.Erased -> {
                if (!goToLiving(a.pageId)) return
                restore(a.entries)
                flushUntilClean()
            }
            is ScratchAction.Moved -> {
                if (!goToLiving(a.pageId)) return
                if (translate(a.ids, -a.dx, -a.dy).isNotEmpty()) dirty = true
                flushUntilClean()
            }
            is ScratchAction.Page -> replayPages(a.before, a.beforeCurrent, a.pageId, a.blob)
        }
    }

    /** Re-apply [a] — [revert]'s mirror. */
    suspend fun reapply(a: ScratchAction) {
        when (a) {
            is ScratchAction.Drew -> {
                if (!goToLiving(a.pageId)) return
                if (addStroke(a.stroke) == Add.OK) flushUntilClean()
            }
            is ScratchAction.Erased -> {
                if (!goToLiving(a.pageId)) return
                for (e in a.entries) if (removeStroke(e.stroke.id)) dirty = true
                flushUntilClean()
            }
            is ScratchAction.Moved -> {
                if (!goToLiving(a.pageId)) return
                if (translate(a.ids, a.dx, a.dy).isNotEmpty()) dirty = true
                flushUntilClean()
            }
            // Redo always drops the blob: a re-inserted page is blank, and a re-deleted one has no
            // ink to keep. The `before`/`after` pair is what tells the two apart in the id list.
            is ScratchAction.Page -> replayPages(a.after, a.afterCurrent, a.pageId, blob = null)
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

    /** The one shape both directions of a [ScratchAction.Page] replay take. */
    private suspend fun replayPages(ids: List<String>, current: String, pageId: String, blob: ByteArray?) {
        // Anything typed since is part of the state being reversed — get it down first.
        flushUntilClean()
        withContext(Dispatchers.IO) {
            store.setPages(ids)
            if (blob != null) store.savePage(pageId, blob) else store.removePageBlob(pageId)
            store.setCurrent(current)
        }
        pageIds = ids
        // Force the reload: the landing page may be the one we are already on (a delete of the last
        // remaining page lands back on itself), and its ink has just changed underneath us.
        currentPageId = ""
        applyPage(current, withContext(Dispatchers.IO) { readPage(current) })
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Decoded, or null when the page has no ink yet. Throws only what the store throws. */
    private fun readPage(id: String): ScratchPageCodec.Page? {
        val blob = store.readPage(id) ?: return null
        return try {
            ScratchPageCodec.decode(blob)
        } catch (e: IllegalArgumentException) {
            Slog.d(TAG) { "page $id unreadable: ${e.message}" }
            unreadableIds += id
            null
        }
    }

    private fun applyPage(id: String, decoded: ScratchPageCodec.Page?) {
        page.clear()
        sizes.clear()
        currentPageId = id
        isUnreadable = id in unreadableIds
        dirty = false
        encodedBytes = ScratchPageCodec.HEADER_BYTES
        if (decoded == null) {
            val (w, h) = surfaceSize()
            pageWidth = w
            pageHeight = h
        } else {
            pageWidth = decoded.pageWidth
            pageHeight = decoded.pageHeight
            for (s in decoded.strokes) {
                val bytes = ScratchPageCodec.strokeBytes(s)
                page[s.id] = s
                sizes[s.id] = bytes
                encodedBytes += bytes
            }
            if (pageWidth <= 0f || pageHeight <= 0f) {
                val (w, h) = surfaceSize()
                pageWidth = w
                pageHeight = h
                // The size is part of the blob, so a page that only just learned it must be rewritten.
                if (w > 0f && h > 0f) dirty = true
            }
        }
    }

    private fun removeStroke(id: String): Boolean {
        if (page.remove(id) == null) return false
        encodedBytes -= sizes.remove(id) ?: 0
        return true
    }

    private fun restore(entries: List<ScratchAction.Erased.Entry>) {
        if (entries.isEmpty()) return
        val rebuilt = LinkedHashMap<String, Stroke>(page.size + entries.size)
        val byIndex = entries.sortedBy { it.index }
        val survivors = page.values.toList()
        var next = 0
        var at = 0
        for (e in byIndex) {
            while (at < survivors.size && next < e.index) { rebuilt[survivors[at].id] = survivors[at]; at++; next++ }
            rebuilt[e.stroke.id] = e.stroke
            next++
        }
        while (at < survivors.size) { rebuilt[survivors[at].id] = survivors[at]; at++ }
        page.clear()
        page.putAll(rebuilt)
        for (e in byIndex) {
            val bytes = ScratchPageCodec.strokeBytes(e.stroke)
            sizes[e.stroke.id] = bytes
            encodedBytes += bytes
        }
        dirty = true
    }

    /** Translate what of [ids] is on this page; returns the ids actually moved. */
    private fun translate(ids: Collection<String>, dx: Float, dy: Float): List<String> {
        val moved = ArrayList<String>(ids.size)
        for (id in ids) {
            val s = page[id] ?: continue
            val next = s.translated(dx, dy)
            page[id] = next
            // Re-measured, never assumed: zlib does not promise the same size for translated floats.
            encodedBytes -= sizes[id] ?: 0
            val bytes = ScratchPageCodec.strokeBytes(next)
            sizes[id] = bytes
            encodedBytes += bytes
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
