package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.export.ExportOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * "Which page is this?", answered for the library (arc 21 / W4).
 *
 * A tagged page is a page id, and the shelf has to show it as **Page 3**. Nothing outside the
 * notebook's own `.soil` knows that: the global index holds folders and notebooks, and a page's
 * position is its `order` among that file's live page rows. So the number costs a read of the file —
 * which is why this exists at all, and why it is careful about how often it does one.
 *
 * **The cache is keyed on the notebook's `updatedAt`.** Every page operation bumps it (the index row
 * is touched when the notebook is written), so a cached list is used only while it still describes
 * the file it came from, and adding a page invalidates the entry that would have numbered the pages
 * after it wrongly. That is cheaper and more honest than a timeout: a stale page number is not a
 * stale *cache*, it is a wrong answer on the glass.
 *
 * **Cost.** A `.soil` open is a SQLCipher open, and the raw key is derived per file — but
 * `KeyMaterial` persists derived keys in the Keystore, so a notebook that has ever been opened
 * reopens without a key derivation. A notebook holding a tagged page has necessarily been opened
 * (that is where the tag was applied from), so in practice this reads warm files. It is blocking
 * either way, so this switches to IO itself rather than trusting every caller to: its callers are
 * listing code on the main dispatcher, which is precisely where the mistake would be made.
 *
 * **A notebook that will not open contributes nothing** — null, not an empty list, and the shelf
 * drops its page cards while leaving its notebook card exactly as it was. A file that is open in
 * this process is refused by [ExportOpen] rather than read under a live writer; from the library
 * nothing is open, so that guard is a backstop rather than a normal path.
 */
object PageNumbers {

    private const val TAG = "PageNumbers"

    private class Entry(val updatedAt: Long, val pageIds: List<String>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * [notebookId]'s live page ids in page order, or **null** when the file could not be read.
     *
     * [updatedAt] is the notebook's index row stamp — the cache key's other half, and the caller
     * already holds it.
     */
    suspend fun pagesOf(context: Context, notebookId: String, updatedAt: Long): List<String>? {
        cache[notebookId]?.let { if (it.updatedAt == updatedAt) return it.pageIds }
        val opened = withContext(Dispatchers.IO) {
            ExportOpen.readOnly(context, notebookId, "page numbers") { db ->
                db.dao().livePageIds(notebookId)
            }
        }
        return when (opened) {
            is ExportOpen.Opened.Read -> opened.value.also {
                cache[notebookId] = Entry(updatedAt, it)
                Slog.d(TAG) { "read ${it.size} page ids for $notebookId" }
            }
            is ExportOpen.Opened.Blocked -> {
                // Not cached: the next query should try again rather than remember a failure that
                // may have been a passing one (no key session yet, a file briefly open elsewhere).
                Slog.d(TAG) { "no page list for $notebookId: ${opened.guard}" }
                null
            }
        }
    }

    // No `clear()`. Nothing calls one, and a cache-invalidation door that no path takes is worse
    // than none: it reads as the answer to "what about a key reset?" while doing nothing. The cache
    // is keyed on the notebook's `updatedAt`, so any edit that moves pages already invalidates its
    // own entry; a whole-library event that moved files underneath without moving a stamp would
    // need a caller here, and that is the arc that adds one.
}
