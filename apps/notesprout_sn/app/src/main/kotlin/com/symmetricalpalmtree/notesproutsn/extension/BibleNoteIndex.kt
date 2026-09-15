package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.LinkRow
import com.symmetricalpalmtree.notesproutsn.notebook.LinkPayload
import com.symmetricalpalmtree.notesproutsn.notebook.NotebookUndo.Action
import com.symmetricalpalmtree.notesproutsn.notebook.PageLink

/**
 * The host's side of the reader's notes index (arc 42 "Notes") — **the one place the host pushes
 * where a Bible reference sits**. The reader (`NSE · Bible`) keeps `note_ref` in its own store
 * and lists, while a chapter is read, the notebook pages holding a reference into it; the host
 * feeds that index at the moment of each act and never reads it back.
 *
 * **Pure half** (JVM-tested): which of a page's links are Bible links ([linkNotes] — the two
 * Bible kinds of [LinkPayload], both carrying a wire in the notebookId slot), the same over a
 * notebook's raw rows ([notebookNotes]), and which undo kinds can change a page's links
 * ([mayTouchLinks]) — a table over every `Action`, so a kind added later fails the test that
 * pins it rather than silently going unindexed.
 *
 * **Effects** (suspend, never throw, log counts only): each discovers the one trusted reader
 * ([ExtensionRegistry.bible]) and returns silently when there is none or it is too old for the
 * tails ([ExtensionContract.MIN_API_VERSION_FOR_BIBLE_NOTES]) — the Rebuild door is the answer
 * for anything that happened while no reader was there. The push itself is
 * [BibleClient.withNotes]: store leased on IO first, bind-per-call, revoked in `finally`.
 *
 * **Per-page reconcile, not per-link add/remove.** Every act that touches links ends with the
 * screen's working copy of the page's links reflecting the rows, so one idempotent
 * [pushPage] with that whole list covers create, edit, delete, the three erases, unlink, paste,
 * and every undo/redo of them with no per-kind code — and a missed push heals on the next.
 *
 * A notebook name is user content and a wire is where the user has read: **neither is logged**.
 */
object BibleNoteIndex {

    private const val TAG = "BibleNoteIndex"

    // ── The pure half ──────────────────────────────────────────────────────────────────────

    /** The notes of one page: every link among [links] whose payload carries a Bible reference
     *  (both kinds), as the reader's parcel. A link whose row cannot become a parcel (a wire the
     *  host's own check refuses) is skipped, never thrown. */
    fun linkNotes(
        links: Collection<PageLink>, pageId: String, pageNumber: Int, now: Long = System.currentTimeMillis(),
    ): List<BibleNote> =
        links.mapNotNull { link ->
            val wire = LinkPayload.referenceOf(link.payload) ?: return@mapNotNull null
            // A link just landed is still the in-memory object built before its row (`createdAt`
            // 0) — it was created now; the row's own stamp replaces it on the next full push.
            val at = if (link.createdAt > 0L) link.createdAt else now
            runCatching { BibleNote(link.id, pageId, pageNumber, wire, at) }.getOrNull()
        }

    /** The notes of a whole notebook from its raw link rows: a link on a page not in
     *  [livePageIds] is dropped; the page's ordinal is its index in that list plus one. */
    fun notebookNotes(rows: List<LinkRow>, livePageIds: List<String>): List<BibleNote> {
        val ordinal = HashMap<String, Int>(livePageIds.size * 2)
        livePageIds.forEachIndexed { ix, id -> ordinal[id] = ix + 1 }
        return rows.mapNotNull { row ->
            val n = ordinal[row.parentId] ?: return@mapNotNull null
            val wire = LinkPayload.referenceOf(row.text ?: return@mapNotNull null) ?: return@mapNotNull null
            runCatching { BibleNote(row.id, row.parentId, n, wire, maxOf(0L, row.createdAt)) }.getOrNull()
        }
    }

    /**
     * Whether replaying [action] (either direction) can change which links are live on its page
     * — the kinds whose undo/redo end in a page push. Structural page kinds ([isStructural])
     * are not here: they change the page *list*, and take the notebook push instead.
     */
    fun mayTouchLinks(action: Action): Boolean = when (action) {
        is Action.Deleted, is Action.ScribbleErased, is Action.LassoErased,
        is Action.ObjectsPasted, is Action.PageErased,
        is Action.LinkCreated, is Action.LinkUnlinked, is Action.LinkEdited,
        is Action.BibleRefCreated, is Action.BibleRefEdited,
        -> true
        is Action.Drew, is Action.Erased, is Action.Moved,
        is Action.HeadingCreated, is Action.HeadingDeleted, is Action.HeadingTextEdited, is Action.HeadingLevelChanged,
        is Action.TextCreated, is Action.TextEdited,
        is Action.ShapeInserted, is Action.ShapeTransformed,
        is Action.StickyInserted, is Action.StickyContentEdited,
        is Action.TemplateChanged,
        is Action.Page, is Action.PagePasted, is Action.PageReceived, is Action.PagesReceived,
        -> false
    }

    /** Whether [action] changes the page list (ordinals, or a page's very existence) — the kinds
     *  whose replay takes a whole-notebook push. */
    fun isStructural(action: Action): Boolean = when (action) {
        is Action.Page, is Action.PagePasted, is Action.PageReceived, is Action.PagesReceived -> true
        else -> false
    }

    // ── The effects ────────────────────────────────────────────────────────────────────────

    /** The reader, if it is one that keeps the index; null is "nothing to push to" (logged once
     *  per call as such — the Rebuild door covers it). */
    private suspend fun reader(context: Context, what: String): ProviderRef? {
        val ref = ExtensionRegistry.bible(context)
        if (ref == null || ref.apiVersion < ExtensionContract.MIN_API_VERSION_FOR_BIBLE_NOTES) {
            Slog.d(TAG) { "$what: no reader with a notes index" }
            return null
        }
        return ref
    }

    /** One page's links, replaced whole. */
    suspend fun pushPage(
        context: Context, notebookId: String, notebookName: String,
        pageId: String, pageNumber: Int, notes: List<BibleNote>,
    ) {
        val ref = reader(context, "pushPage") ?: return
        BibleClient.withNotes(context, ref, "pushPage(${notes.size})") { iface, store ->
            iface.replacePageNotes(store, notebookId, capName(notebookName), pageId, pageNumber, notes)
        }
    }

    /** A whole notebook's links, replaced whole, with its live page list. */
    suspend fun pushNotebook(
        context: Context, notebookId: String, notebookName: String,
        livePageIds: List<String>, notes: List<BibleNote>,
    ) {
        val ref = reader(context, "pushNotebook") ?: return
        BibleClient.withNotes(context, ref, "pushNotebook(${livePageIds.size} pages, ${notes.size})") { iface, store ->
            iface.replaceNotebookNotes(store, notebookId, capName(notebookName), livePageIds, notes)
        }
    }

    /** The notebook was renamed. */
    suspend fun rename(context: Context, notebookId: String, notebookName: String) {
        val ref = reader(context, "rename") ?: return
        BibleClient.withNotes(context, ref, "rename") { iface, store ->
            iface.renameNotebookNotes(store, notebookId, capName(notebookName))
        }
    }

    /** The notebook is gone. */
    suspend fun delete(context: Context, notebookId: String) {
        val ref = reader(context, "delete") ?: return
        BibleClient.withNotes(context, ref, "delete") { iface, store -> iface.deleteNotebookNotes(store, notebookId) }
    }

    /** Drop every notebook the index knows that is not in [aliveNotebookIds]. */
    suspend fun prune(context: Context, aliveNotebookIds: List<String>) {
        val ref = reader(context, "prune") ?: return
        BibleClient.withNotes(context, ref, "prune(${aliveNotebookIds.size})") { iface, store ->
            iface.pruneNotes(store, aliveNotebookIds)
        }
    }

    /** A reference looked up from a document (arc 39's Lookup): [pageId] empty and [pageNumber]
     *  0 for a text-document notebook or the notebook document. */
    suspend fun noteDocument(
        context: Context, notebookId: String, notebookName: String, pageId: String, pageNumber: Int, wire: String,
    ) {
        val ref = reader(context, "noteDocument") ?: return
        BibleClient.withNotes(context, ref, "noteDocument") { iface, store ->
            iface.noteDocumentReference(store, notebookId, capName(notebookName), pageId, pageNumber, wire)
        }
    }

    /** The seam's cap on a name, applied here so a long name is trimmed rather than refused. */
    private fun capName(name: String): String =
        if (name.length <= ExtensionContract.BIBLE_NOTE_MAX_NAME_CHARS) name
        else name.substring(0, ExtensionContract.BIBLE_NOTE_MAX_NAME_CHARS)
}
