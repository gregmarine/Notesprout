package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.LinkRow
import com.symmetricalpalmtree.notesproutsn.extension.BibleNote
import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pure rules of the notebook's notes coalescer (arc 42 "Notes"), JVM-tested apart from the
 * screen: what is pending after a run of marks, and what one flush pushes.
 *
 * - **Latest per page wins**: a page marked twice in a burst (an undo then a redo) is one push,
 *   of its last state.
 * - **A structural mark supersedes**: once the page list changed, the flush pushes the whole
 *   notebook (every page's ordinal may have moved) and the page marks are folded into it — the
 *   notebook push reads the rows itself, so nothing marked is lost.
 * - **A notebook with no Bible link never binds**: a page push whose notes are empty is skipped
 *   while [hasBibleLinks] is false — the ordinary notebook, opened and closed a hundred times a
 *   day, pays nothing for a feature it does not use. Any non-empty push flips the flag.
 */
class BibleNoteSyncRules(
    /** Whether this notebook is known to hold at least one Bible link (read once at open). */
    var hasBibleLinks: Boolean,
) {
    /** A page's last marked state. */
    data class PageMark(val pageId: String, val pageNumber: Int, val notes: List<BibleNote>)

    private val pages = LinkedHashMap<String, PageMark>()
    private var structural = false

    val isEmpty: Boolean get() = pages.isEmpty() && !structural

    fun markPage(pageId: String, pageNumber: Int, notes: List<BibleNote>) {
        if (notes.isNotEmpty()) hasBibleLinks = true
        if (notes.isEmpty() && !hasBibleLinks) return   // nothing to clear, nothing to bind for
        pages[pageId] = PageMark(pageId, pageNumber, notes)
    }

    fun markStructural() {
        if (!hasBibleLinks) return   // no rows to renumber; the open's read said so
        structural = true
    }

    /** What one flush pushes, and the rules reset. */
    sealed interface Flush {
        object Nothing : Flush
        object Notebook : Flush
        data class Pages(val marks: List<PageMark>) : Flush
    }

    fun take(): Flush {
        val flush: Flush = when {
            structural -> Flush.Notebook
            pages.isEmpty() -> Flush.Nothing
            else -> Flush.Pages(pages.values.toList())
        }
        pages.clear()
        structural = false
        return flush
    }
}

/**
 * The notebook screen's notes coalescer (arc 42 "Notes"): marks arrive on Main after each act
 * that touched links ([markPage]) or the page list ([markStructural]); a flush runs after
 * [DEBOUNCE_MS] of quiet on [scope] — the notebook's application-scoped scope, so a Back right
 * after a delete still lands — and pushes through [BibleNoteIndex]. **Nothing is pushed at
 * close**: a cold store lease pays the KDF, and a close is every Back; [flushBeforeSeal] runs
 * only what is already pending, before the session's connection goes away (a structural flush
 * reads the rows through it).
 *
 * The screen supplies the reads: [linkRows] (every live link row, blob-free) and [livePageIds]
 * (the page list, in order) — both through the open session, never a second connection.
 */
class BibleNoteSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notebookId: () -> String,
    private val notebookName: () -> String,
    private val linkRows: suspend () -> List<LinkRow>,
    private val livePageIds: suspend () -> List<String>,
) {
    private val rules = BibleNoteSyncRules(hasBibleLinks = false)
    private var pending: Job? = null

    /** The open's one read: whether any live link is a Bible link. Off Main. */
    suspend fun prime() {
        val any = runCatching { linkRows().any { LinkPayload.referenceOf(it.text ?: "") != null } }.getOrDefault(false)
        withContext(Dispatchers.Main.immediate) { rules.hasBibleLinks = any }
        Slog.d(TAG) { "prime: bibleLinks=$any" }
    }

    /** Main. The page's links as they now are — the whole list, every kind. */
    fun markPage(pageId: String, pageNumber: Int, links: Collection<PageLink>) {
        if (pageNumber < 1) return
        rules.markPage(pageId, pageNumber, BibleNoteIndex.linkNotes(links, pageId, pageNumber))
        schedule()
    }

    /** Main. The page list changed (insert, delete, paste, receive, or their undo). */
    fun markStructural() {
        rules.markStructural()
        schedule()
    }

    private fun schedule() {
        if (rules.isEmpty) return
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            pending = null
            flush()
        }
    }

    /** Push whatever is pending now — from inside the close, before the session seals. */
    suspend fun flushBeforeSeal() {
        pending?.cancel()
        pending = null
        withContext(NonCancellable) { flush() }
    }

    private suspend fun flush() {
        when (val f = withContext(Dispatchers.Main.immediate) { rules.take() }) {
            BibleNoteSyncRules.Flush.Nothing -> Unit
            is BibleNoteSyncRules.Flush.Pages -> withContext(Dispatchers.IO) {
                for (m in f.marks) {
                    BibleNoteIndex.pushPage(context, notebookId(), notebookName(), m.pageId, m.pageNumber, m.notes)
                }
            }
            BibleNoteSyncRules.Flush.Notebook -> withContext(Dispatchers.IO) {
                val rows = runCatching { linkRows() }.getOrNull() ?: return@withContext
                val pages = runCatching { livePageIds() }.getOrNull() ?: return@withContext
                BibleNoteIndex.pushNotebook(
                    context, notebookId(), notebookName(), pages, BibleNoteIndex.notebookNotes(rows, pages),
                )
            }
        }
    }

    companion object {
        private const val TAG = "BibleNoteSync"

        /** An undo burst is one push; long enough that a run of taps coalesces, short enough
         *  that a Back after the last one still finds the push landed or in flight. */
        const val DEBOUNCE_MS = 750L
    }
}
