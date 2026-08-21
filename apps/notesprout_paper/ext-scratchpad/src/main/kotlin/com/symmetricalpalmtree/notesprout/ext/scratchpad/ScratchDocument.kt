package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.ext.scratchpad.ScratchUndo.Action
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The scratch pad's pages in memory + their persistence (arc 6 / S1): the page list, the current
 * page's strokes (insertion-ordered = writing order), its page size and its **running encoded size**
 * (the full rule — `HEADER_BYTES + Σ strokeBytes`, exact because every stroke is encoded on its own and
 * deterministically; a move re-measures the moved strokes), and every store round trip on `Dispatchers.IO` through [ScratchStore]. The screen owns the
 * paper and the history; this class owns the data — mutations on the caller's (Main) thread, IO only
 * inside `withContext(Dispatchers.IO)`, so the screen's `pageOps` mutex serialises everything.
 *
 * A page's size is `0 × 0` until the screen supplies the surface size ([ensurePageSize]) — written
 * into the blob on the first save. Any store failure surfaces as [StoreUnavailable] (the screen shows
 * `scratch_store_unavailable` and finishes); a stroke that would cross `STORE_MAX_VALUE_BYTES` is
 * refused by [add] (false) — never written, never split.
 */
class ScratchDocument(private val store: ScratchStore) {

    var ids: List<String> = emptyList()
        private set
    var currentId: String = ""
        private set
    val currentIndex: Int get() = ids.indexOf(currentId)

    /** The current page's strokes, insertion-ordered = writing order. */
    val strokes: LinkedHashMap<String, Stroke> = LinkedHashMap()
    var pageWidth: Float = 0f
        private set
    var pageHeight: Float = 0f
        private set
    /** The current page's encoded size if saved now. */
    var pageBytes: Int = ScratchPageCodec.HEADER_BYTES
        private set
    /** Unsaved edits on the current page. */
    var dirty: Boolean = false
        private set

    // ── Load / navigate ────────────────────────────────────────────────────────

    /** First run creates one blank page. Loads the remembered current page. */
    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        ids = loaded.ids
        loadPage(loaded.currentId)
    }

    /** Flush the current page, then make [id] current (persisted) and load its ink. */
    suspend fun goTo(id: String) {
        if (id !in ids) return
        flushUntilClean()
        withContext(Dispatchers.IO) { store.setCurrent(id) }
        loadPage(id)
    }

    /** A stroke committed during a flush's IO hop lands on the page being left — flush again until
     *  nothing is pending, so a page turn never drops ink (bounded by the drawing rate). */
    private suspend fun flushUntilClean() {
        do flush() while (dirty)
    }

    private suspend fun loadPage(id: String) {
        val page = withContext(Dispatchers.IO) {
            store.readPage(id)?.let { blob ->
                // An undecodable blob (a newer codec version, a truncated value) is "unreadable", never a
                // crash and never silently blank — a blank page would be saved over it on the next stroke.
                val decoded = try { ScratchPageCodec.decode(blob) } catch (e: Exception) { throw StoreUnavailable(IllegalStateException("page $id unreadable: ${e.message}", e)) }
                decoded to blob.size
            }
        }
        // Ink committed on the page being left while that read was in flight landed in `strokes` (the
        // old page's map): write it to the old page before the swap drops the map — unless that page
        // is being removed (delete / undo), in which case it goes with the page. Bounded by the drawing rate.
        while (dirty && currentId != id && currentId in ids) flush()
        currentId = id
        strokes.clear()
        if (page == null) {
            pageWidth = 0f; pageHeight = 0f
            pageBytes = ScratchPageCodec.HEADER_BYTES
        } else {
            val (decoded, size) = page
            pageWidth = decoded.pageWidth; pageHeight = decoded.pageHeight
            for (s in decoded.strokes) strokes[s.id] = s
            pageBytes = size
        }
        dirty = false
    }

    /** The screen's surface size becomes the page size of a page that has none yet. */
    fun ensurePageSize(width: Int, height: Int) {
        if (pageWidth > 0f && pageHeight > 0f) return
        if (width <= 0 || height <= 0) return
        pageWidth = width.toFloat(); pageHeight = height.toFloat()
        dirty = true
    }

    // ── Mutations (caller thread) ─────────────────────────────────────────────

    /** Add a committed stroke; false when it would push the page past the value cap (nothing changes). */
    fun add(stroke: Stroke): Boolean {
        val bytes = ScratchPageCodec.strokeBytes(stroke)
        if (pageBytes + bytes > ExtensionContract.STORE_MAX_VALUE_BYTES) return false
        addUnchecked(stroke, bytes)
        return true
    }

    /** Undo / redo replay: the stroke was on the page before, so the cap is not re-checked. */
    private fun addUnchecked(stroke: Stroke, bytes: Int = ScratchPageCodec.strokeBytes(stroke)) {
        if (strokes.put(stroke.id, stroke) == null) pageBytes += bytes
        dirty = true
    }

    /** Remove [removeIds]; returns what was removed, in writing order. */
    fun remove(removeIds: Collection<String>): List<Stroke> {
        val set = removeIds.toHashSet()
        val taken = strokes.values.filter { it.id in set }
        for (s in taken) { strokes.remove(s.id); pageBytes -= ScratchPageCodec.strokeBytes(s) }
        if (taken.isNotEmpty()) dirty = true
        return taken
    }

    fun translate(moveIds: Collection<String>, dx: Float, dy: Float) {
        var any = false
        for (id in moveIds) {
            val old = strokes[id] ?: continue
            val moved = old.translated(dx, dy)
            strokes[id] = moved
            // The geometry is zlib-compressed per stroke, so a move changes its encoded size.
            pageBytes += ScratchPageCodec.strokeBytes(moved) - ScratchPageCodec.strokeBytes(old)
            any = true
        }
        if (any) dirty = true
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Save the current page if dirty (snapshot here, encode + write on IO). [PageFullException] only
     *  if a replay pushed it over the cap (kept in memory; the caller says so). */
    suspend fun flush() {
        if (!dirty) return
        val id = currentId
        val snapshot = strokes.values.toList()
        val w = pageWidth; val h = pageHeight
        dirty = false
        val t0 = System.currentTimeMillis()
        val size = try {
            withContext(Dispatchers.IO) {
                val blob = ScratchPageCodec.encode(w, h, snapshot)
                store.savePage(id, blob)
                blob.size
            }
        } catch (e: Exception) {
            if (currentId == id) dirty = true   // nothing landed — the next flush retries
            throw e
        }
        Slog.d(TAG) { "savePage ${snapshot.size} strokes, $size bytes in ${System.currentTimeMillis() - t0} ms" }
    }

    // ── Page structure ────────────────────────────────────────────────────────

    /** Insert a blank page after / before the current one and go to it. */
    suspend fun insert(after: Boolean): Action.Page {
        flush()
        val before = ids; val beforeCurrent = currentId
        val (next, newId) = withContext(Dispatchers.IO) {
            if (after) store.insertPage(before, beforeCurrent) else store.insertPageBefore(before, beforeCurrent)
        }
        ids = next
        goTo(newId)
        return Action.Page(before, beforeCurrent, next, newId, newId, null)
    }

    /** Delete the current page (a lone page is emptied); lands on the previous page (or the first). */
    suspend fun deleteCurrent(): Action.Page {
        flush()
        val before = ids; val beforeCurrent = currentId
        val blob = withContext(Dispatchers.IO) { store.readPage(beforeCurrent) }
        val (rest, landing) = withContext(Dispatchers.IO) { store.deletePage(before, beforeCurrent) }
        ids = rest
        if (landing == beforeCurrent) loadPage(landing)   // the lone page, now empty
        else { withContext(Dispatchers.IO) { store.setCurrent(landing) }; loadPage(landing) }
        return Action.Page(before, beforeCurrent, rest, landing, beforeCurrent, blob)
    }

    // ── Undo / redo replay ────────────────────────────────────────────────────

    /** Undo [a]. Returns false if it no longer applies (its page is gone) — the caller drops it. */
    suspend fun revert(a: Action): Boolean = when (a) {
        is Action.Drew -> onPage(a.pageId) { remove(listOf(a.stroke.id)) }
        is Action.Erased -> onPage(a.pageId) { for (s in a.strokes) addUnchecked(s) }
        is Action.Moved -> onPage(a.pageId) { translate(a.ids, -a.dx, -a.dy) }
        is Action.Pasted -> onPage(a.pageId) { remove(a.strokes.map { it.id }) }
        is Action.Page -> { applyStructure(a, redo = false); true }
    }

    /** Redo [a]. Same contract as [revert]. */
    suspend fun reapply(a: Action): Boolean = when (a) {
        is Action.Drew -> onPage(a.pageId) { addUnchecked(a.stroke) }
        is Action.Erased -> onPage(a.pageId) { remove(a.strokes.map { it.id }) }
        is Action.Moved -> onPage(a.pageId) { translate(a.ids, a.dx, a.dy) }
        is Action.Pasted -> onPage(a.pageId) { for (s in a.strokes) addUnchecked(s) }
        is Action.Page -> { applyStructure(a, redo = true); true }
    }

    private suspend inline fun onPage(pageId: String, block: () -> Unit): Boolean {
        if (pageId !in ids) return false
        if (pageId != currentId) goTo(pageId)
        block()
        return true
    }

    /**
     * Move the page list to the action's `after` ([redo]) or `before` state: the changed page is re-added (its captured
     * ink restored) or removed (its ink captured first, then dropped); a lone-page "delete" toggles
     * the ink only. Then the target current page is loaded.
     */
    private suspend fun applyStructure(a: Action.Page, redo: Boolean) {
        flushUntilClean()
        val target = if (redo) a.after else a.before
        val targetCurrent = if (redo) a.afterCurrent else a.beforeCurrent
        val id = a.changedId
        val had = id in ids
        val wants = id in target
        withContext(Dispatchers.IO) {
            when {
                had && !wants -> { a.blob = store.readPage(id); store.removePageBlob(id); store.setPages(target) }
                !had && wants -> { store.setPages(target); a.blob?.let { store.savePage(id, it) } }
                a.before == a.after -> {   // the lone page: emptied (redo) or refilled (undo)
                    if (redo) { a.blob = store.readPage(id); store.removePageBlob(id) }
                    else a.blob?.let { store.savePage(id, it) }
                }
            }
            store.setCurrent(targetCurrent)
        }
        ids = target
        loadPage(targetCurrent)
    }

    private companion object {
        const val TAG = "ScratchDocument"
    }
}
