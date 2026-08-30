package com.symmetricalpalmtree.notesproutsn.data.soil

import androidx.room.Dao
import androidx.room.Query

/**
 * The `document` row's own queries (arc 19 / M2) — the read, the two writes, and the two staleness
 * sweeps the watermark is compared against. Kept apart from [SoilDao] because the pair of writes
 * carries an invariant that only reads as one rule when they sit together: **exactly one of them
 * moves `flags`**. Higher-level logic lives in [DocumentRepository].
 *
 * A `document` row's `flags` is the source watermark (see [SoilSchema.TYPE_DOCUMENT]) — the value
 * [maxContentUpdatedAt] (a page document) or [notebookMaxContentUpdatedAt] (the notebook document)
 * answered at the last seed or refresh. Adding this DAO does not move Room's identity hash: the
 * entity set is unchanged, and the hash is a statement about the schema, not about the interfaces
 * that query it.
 */
@Dao
interface DocumentDao {

    /**
     * The live `document` row of [parentId] — a page id (the page document) or the notebook root
     * row's id (the notebook document); one row shape, two parents, og's model. At most one live
     * row per parent by construction (the repository never inserts a second), so `LIMIT 1` is a
     * cap on damage rather than a choice between candidates.
     *
     * Full entity deliberately: `text` is the payload and `flags` the watermark, so a projection
     * would drop only the columns a document contractually leaves null.
     */
    @Query(
        """SELECT * FROM notebook
           WHERE type = 'document' AND parentId = :parentId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun documentFor(parentId: String): SoilObjectEntity?

    /**
     * The page's content watermark: `MAX(updatedAt)` over everything **on** [pageId] — its strokes,
     * headings and links, plus the links' own children ([SoilDao.liveDescendantIds]'s two-level
     * shape, so a wrapped selection counts exactly as it did before it was wrapped).
     *
     * **Soft-deleted rows count** (og's rule, and the reason there is no `deletedAt IS NULL` here or
     * in the join): a soft-delete sets `updatedAt` to the deletion time, so an *erase* raises this
     * value exactly as new ink does. Filtering to live rows would make erasing a page's ink invisible
     * to the draft written from it — the one change most likely to leave a document lying.
     *
     * `document` rows are **excluded** by the whitelist, at both levels, and that is the rule the
     * whole feature rests on (og's): a document is a *product* of the page, not content on it, so
     * writing one must never make the page look changed to the document drafted from it.
     *
     * 0 for a page with nothing on it — a blank page has no watermark to be stale against, and
     * `COALESCE` keeps the caller off a nullable it would only ever fold to 0 anyway.
     *
     * **The SN wrinkle og never had:** arc 17's close-time purge ([SoilCompactor]) hard-deletes the
     * soft-deleted rows, so staleness raised by an erase lasts until the notebook's next close.
     * After the purge the maximum honestly describes what is still in the file, and a draft that
     * read "stale" may read current again. That is the accepted consequence of the purge decision,
     * written down so nobody "fixes" it later: the alternative is keeping erased rows forever in an
     * encrypted file to preserve one boolean.
     */
    @Query(
        """SELECT COALESCE(MAX(updatedAt), 0) FROM notebook
           WHERE type IN ('stroke', 'heading', 'link') AND (
             parentId = :pageId
             OR parentId IN (SELECT id FROM notebook WHERE parentId = :pageId AND type = 'link'))"""
    )
    suspend fun maxContentUpdatedAt(pageId: String): Long

    /**
     * The notebook-wide watermark — everything a merge of [rootId]'s pages reads: the content rows
     * of every page (the same whitelist and the same two levels as [maxContentUpdatedAt]), **plus
     * every page-parented `document` row**. Both halves are the point: new ink *and* an edited page
     * document mean the pages have changed since the merge.
     *
     * **Soft-deleted rows count at every level here too** — content, the links joined through, and
     * the pages themselves: a deleted page is as much "the pages have changed" as a new stroke is,
     * and its children carry the deletion time in their `updatedAt`. See [maxContentUpdatedAt] for
     * the rule and for the purge wrinkle that bounds how long an erase stays visible.
     *
     * The notebook document itself is excluded structurally rather than by a clause — it is
     * parented to [rootId], and [rootId] is not one of its own pages — so it can never invalidate
     * itself. 0 for a notebook with no content at all.
     */
    @Query(
        """SELECT COALESCE(MAX(updatedAt), 0) FROM notebook
           WHERE (type IN ('stroke', 'heading', 'link', 'document')
                    AND parentId IN (SELECT id FROM notebook
                                     WHERE type = 'page' AND parentId = :rootId))
              OR (type IN ('stroke', 'heading', 'link')
                    AND parentId IN (SELECT l.id FROM notebook l
                                     WHERE l.type = 'link'
                                       AND l.parentId IN (SELECT p.id FROM notebook p
                                                          WHERE p.type = 'page' AND p.parentId = :rootId)))"""
    )
    suspend fun notebookMaxContentUpdatedAt(rootId: String): Long

    /**
     * A **hand edit**: new text, same watermark. `flags` is deliberately absent from the SET list —
     * a keystroke must not re-anchor the document to the page, or "the page has changed since this
     * draft" would answer false the moment the user typed. The other half of the pair is
     * [setDocumentDrafted].
     */
    @Query("UPDATE notebook SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun setDocumentText(id: String, text: String, at: Long)

    /**
     * A **seed or refresh**: new text *and* the page state it was drafted from. This is the only
     * write in the app that moves a document's watermark — structural, not incidental (see
     * [setDocumentText]).
     */
    @Query("UPDATE notebook SET text = :text, flags = :flags, updatedAt = :at WHERE id = :id")
    suspend fun setDocumentDrafted(id: String, text: String, flags: Long, at: Long)
}
