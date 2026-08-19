package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.SharedBytes
import java.util.UUID

/** The extension cannot reach its storage (any store exception — the host's rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** A page whose encoded ink would exceed `STORE_MAX_VALUE_BYTES` — the caller refuses the stroke that crossed it. */
class PageFullException(val bytes: Int) : Exception("scratch page full ($bytes bytes)")

/**
 * The scratch pad's key layout over the host's `IExtensionStore` (arc 6 / S0). **Blocking** — every
 * call runs on `Dispatchers.IO` (the screen) or the Binder thread (`begin`), never Main.
 *
 * Keys: [KEY_PAGES] = UTF-8, one page id per line, in order (a page id = a random UUID minted here);
 * [KEY_CURRENT] = the current page id; `page/<id>` = the page blob ([ScratchPageCodec]).
 *
 * Values ≤ `STORE_MAX_INLINE_BYTES` go through `put` / `get`; above that `putLarge` / `getLarge`
 * (the `SharedBytes` handshake — the region we create is closed after the call returns; the one the
 * host returns is closed in a `finally`). **The full rule:** a blob over `STORE_MAX_VALUE_BYTES` is
 * [PageFullException] — never split, never written elsewhere. Any store exception →
 * [StoreUnavailable]. A missing [KEY_PAGES] = first run → one blank page.
 */
class ScratchStore(private val store: IExtensionStore) {

    class Loaded(val ids: List<String>, val currentId: String)

    /** The page list + current id; creates the first blank page on first run. */
    fun load(): Loaded = guard {
        val raw = store.get(KEY_PAGES)
        val ids = raw?.toString(Charsets.UTF_8)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        if (ids.isEmpty()) {
            val id = newId()
            writeIds(listOf(id))
            store.put(KEY_CURRENT, id.toByteArray())
            return@guard Loaded(listOf(id), id)
        }
        val storedCurrent = store.get(KEY_CURRENT)?.toString(Charsets.UTF_8)
        val current = ScratchPages.clampCurrent(ids, storedCurrent)
        if (current != storedCurrent) store.put(KEY_CURRENT, current.toByteArray())
        Loaded(ids, current)
    }

    /** The page blob for [id], or null if the page has no ink yet (never written). */
    fun readPage(id: String): ByteArray? = guard {
        try {
            store.get(pageKey(id))
        } catch (e: IllegalStateException) {
            if (e.message != ExtensionContract.STORE_VALUE_LARGE) throw e
            val v = store.getLarge(pageKey(id)) ?: return@guard null
            SharedBytes.readAndClose(v)
        }
    }

    /** Write a page blob; [PageFullException] above the value cap (nothing written). */
    fun savePage(id: String, blob: ByteArray) {
        if (blob.size > ExtensionContract.STORE_MAX_VALUE_BYTES) throw PageFullException(blob.size)
        guard {
            if (blob.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) {
                store.put(pageKey(id), blob)
            } else {
                val v = SharedBytes.write(blob)
                try { store.putLarge(pageKey(id), v) } finally { v.memory.close() }
            }
        }
    }

    /** Insert a new blank page after [afterId]; returns (new id list, new id). */
    fun insertPage(ids: List<String>, afterId: String?): Pair<List<String>, String> = guard {
        val id = newId()
        val next = ScratchPages.insertAfter(ids, afterId, id)
        writeIds(next)
        next to id
    }

    /** Insert a new blank page before [beforeId]; returns (new id list, new id). */
    fun insertPageBefore(ids: List<String>, beforeId: String?): Pair<List<String>, String> = guard {
        val id = newId()
        val next = ScratchPages.insertBefore(ids, beforeId, id)
        writeIds(next)
        next to id
    }

    /**
     * Delete [id] and its blob; returns (new id list, landing id). Never below one page: a lone
     * page keeps its id and is emptied (its blob deleted).
     */
    fun deletePage(ids: List<String>, id: String): Pair<List<String>, String> = guard {
        val (rest, landing) = ScratchPages.delete(ids, id)
        store.delete(pageKey(id))
        if (rest != ids) writeIds(rest)
        rest to landing
    }

    fun setCurrent(id: String) = guard { store.put(KEY_CURRENT, id.toByteArray()) }

    /** Undo / redo of a page insert or delete (S1): write a whole page list as-is. */
    fun setPages(ids: List<String>) = guard { writeIds(ids) }

    /** Undo / redo of a page insert or delete (S1): drop one page's ink, keeping its id in the list. */
    fun removePageBlob(id: String) = guard { store.delete(pageKey(id)) }

    /** What [receive] placed: the page it landed on + the ids of the placed strokes (for "open selected"), and —
     *  so the screen can record the placement as one undo step — whether a page was inserted ([newPage]) and the
     *  page list + current page as they were before. */
    class Received(val pageId: String, val strokeIds: List<String>, val newPage: Boolean, val pagesBefore: List<String>, val currentBefore: String)

    /**
     * Notebook → pad (S2, the Binder thread): place [strokes] — on a **new page** inserted after the
     * current one (its size = the bundle's page size; `0 × 0` → the screen's surface size at first
     * layout) or appended to the **current page** (its own size kept; the bundle's if it has none
     * yet) — and make that page current, so the next screen launch opens on it. **The full rule:**
     * a result over `STORE_MAX_VALUE_BYTES` is [PageFullException] — nothing placed, nothing inserted.
     */
    fun receive(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float, newPage: Boolean): Received {
        val loaded = load()
        val ids = strokes.map { it.id }
        if (newPage) {
            val blob = ScratchPageCodec.encode(pageWidth, pageHeight, strokes)
            if (blob.size > ExtensionContract.STORE_MAX_VALUE_BYTES) throw PageFullException(blob.size)
            val (_, id) = insertPage(loaded.ids, loaded.currentId)
            savePage(id, blob)
            setCurrent(id)
            return Received(id, ids, true, loaded.ids, loaded.currentId)
        }
        val cur = loaded.currentId
        val existing = readPage(cur)?.let { ScratchPageCodec.decode(it) }
        val w = if (existing != null && existing.pageWidth > 0f) existing.pageWidth else pageWidth
        val h = if (existing != null && existing.pageHeight > 0f) existing.pageHeight else pageHeight
        val all = (existing?.strokes ?: emptyList()) + strokes
        val blob = ScratchPageCodec.encode(w, h, all)
        if (blob.size > ExtensionContract.STORE_MAX_VALUE_BYTES) throw PageFullException(blob.size)
        savePage(cur, blob)
        return Received(cur, ids, false, loaded.ids, loaded.currentId)
    }

    /** Debug: every key + the summed byte size of the page blobs (reads each page). */
    fun sizeSummary(): Pair<Int, Long> = guard {
        val keys = store.keys("")
        var bytes = 0L
        for (k in keys) if (k.startsWith(PAGE_PREFIX)) bytes += readPage(k.removePrefix(PAGE_PREFIX))?.size ?: 0
        keys.size to bytes
    }

    private fun writeIds(ids: List<String>) {
        store.put(KEY_PAGES, ids.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: PageFullException) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }

    companion object {
        const val KEY_PAGES = "pages"
        const val KEY_CURRENT = "current"
        const val PAGE_PREFIX = "page/"
        fun pageKey(id: String) = PAGE_PREFIX + id
        fun newId(): String = UUID.randomUUID().toString()

        /** Encode + size check without writing (the "would this stroke cross the cap" test). */
        fun encodedSize(pageWidth: Float, pageHeight: Float, strokes: List<Stroke>): Int =
            ScratchPageCodec.encode(pageWidth, pageHeight, strokes).size
    }
}
