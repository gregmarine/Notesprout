package com.notesprout.android.data

import com.notesprout.android.core.Slog
import java.util.UUID

/** A page's stored document: its Markdown plus the page state it was last drafted from. */
data class PageDocument(
    val id: String,
    val text: String,
    /** `getMaxContentUpdatedAt(layerId)` at the last seed/refresh; null = authored by hand. */
    val srcUpdatedAt: Long?,
)

/**
 * DB glue for the page document — the authored Markdown behind
 * [com.notesprout.android.DocumentEditorActivity].
 *
 * Deliberately narrow: read, write, travel with the page, die with the page. The **only** writer of a
 * `document` row is the editor (through its host) plus the page copy/delete helpers below — no
 * recognition path ever writes one, which is what keeps an edited document safe from RTR. Compare
 * [com.notesprout.android.recognition.PageTextRepository], whose `page_text` rows are a cache with
 * three writers and no user content.
 *
 * All methods are `suspend` and expect to run on `Dispatchers.IO`, against the notebook's **own** open
 * connection (`SoilDatabase` is one-instance-per-notebook by design). See docs/documents.md.
 */
object DocumentRepository {

    private const val TAG = "DocumentRepo"

    /** The document for [pageId], or null if the page has none. */
    suspend fun get(dao: NotebookDao, pageId: String): PageDocument? {
        val row = dao.getDocumentRow(pageId) ?: return null
        return PageDocument(id = row.id, text = row.text.orEmpty(), srcUpdatedAt = row.srcUpdatedAt)
    }

    /**
     * Write [text] as [pageId]'s document, stamping [srcUpdatedAt] as the page state it came from.
     *
     * Callers pass the watermark they already hold rather than re-reading it, because a plain
     * keystroke must **not** move it: only a seed or an explicit refresh re-anchors a document to the
     * page.
     *
     * Blank text on a page that has no document row writes nothing — an untouched editor leaves no
     * trace, and the page stays eligible to be seeded on a later visit.
     */
    suspend fun save(dao: NotebookDao, pageId: String, text: String, srcUpdatedAt: Long?) {
        val now = System.currentTimeMillis()
        val existing = dao.getDocumentRow(pageId)
        if (existing != null) {
            if (existing.text == text && existing.srcUpdatedAt == srcUpdatedAt) return
            dao.updateDocument(existing.id, text, srcUpdatedAt, now)
        } else {
            if (text.isBlank()) return
            dao.insertObject(
                NotebookObject(
                    id = UUID.randomUUID().toString(),
                    parentId = pageId,
                    boundingBox = "",
                    sortOrder = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    type = TYPE_DOCUMENT,
                    data = "",
                    text = text,
                    srcUpdatedAt = srcUpdatedAt,
                )
            )
        }
        Slog.d(TAG) { "Saved document for page $pageId (${text.length} chars, src=$srcUpdatedAt)" }
    }

    /**
     * Copy [sourcePageId]'s document (if any) onto [newPageId] as a fresh row.
     *
     * Page copies go through `PageCopier`, which deep-copies the *layer* subtree — a page-parented row
     * is invisible to it. `page_text` may be dropped that way (it is a cache and regenerates), but a
     * document is the user's writing, so every page-copy path calls this. Must run inside the caller's
     * transaction.
     */
    suspend fun copyToPage(dao: NotebookDao, sourcePageId: String, newPageId: String, now: Long) {
        val row = dao.getDocumentRow(sourcePageId) ?: return
        dao.insertObject(
            row.copy(
                id = UUID.randomUUID().toString(),
                parentId = newPageId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        )
    }

    // Deleting a page needs nothing here: every delete path soft-deletes the page's children by
    // parentId (`softDeleteByParentId(pageId)` / `WHERE parentId = ?` in the raw paths), and undo
    // restores them the same way — a page-parented document is carried by both for free.

    // ── Notebook document ─────────────────────────────────────────────────────
    //
    // The notebook-level document is an ordinary `document` row whose parentId is the notebook
    // *root* object's id (type = 'notebook') instead of a page id — no schema change, and every
    // sweep/copy path is keyed on page/layer ids so none of them can see it. Its `srcUpdatedAt`
    // is `getNotebookMaxContentUpdatedAt(rootId)` at the last merge. See docs/documents.md.

    /**
     * The parent id a notebook-level document row uses: the notebook root object's id, falling
     * back to [MainActivity.NIL_UUID] for legacy notebooks with no root row — the same fallback
     * pages themselves use, and unambiguous because pages are not `type = 'document'`.
     */
    suspend fun notebookDocParentId(dao: NotebookDao): String =
        dao.getNotebookObject()?.id ?: com.notesprout.android.MainActivity.NIL_UUID

    /** The notebook-level document, or null if this notebook has never been merged. */
    suspend fun getNotebookDocument(dao: NotebookDao): PageDocument? =
        get(dao, notebookDocParentId(dao))

    /** Write [text] as the notebook-level document. Same watermark discipline as [save]. */
    suspend fun saveNotebookDocument(dao: NotebookDao, text: String, srcUpdatedAt: Long?) =
        save(dao, notebookDocParentId(dao), text, srcUpdatedAt)
}
