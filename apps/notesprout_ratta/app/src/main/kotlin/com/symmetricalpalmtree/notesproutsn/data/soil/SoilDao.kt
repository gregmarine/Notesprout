package com.symmetricalpalmtree.notesproutsn.data.soil

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Row-level access to the `notebook` table. Higher-level logic lives in `notebook/` (R3+). */
@Dao
interface SoilDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SoilObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SoilObjectEntity>)

    @Query("SELECT * FROM notebook WHERE id = :id")
    suspend fun byId(id: String): SoilObjectEntity?

    @Query("SELECT * FROM notebook WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SoilObjectEntity>

    @Query("SELECT * FROM notebook WHERE type = :type AND parentId = :parentId AND deletedAt IS NULL ORDER BY `order`")
    suspend fun childrenOfType(parentId: String, type: String): List<SoilObjectEntity>

    @Query("SELECT * FROM notebook WHERE type = 'notebook' AND parentId = '' LIMIT 1")
    suspend fun notebookRow(): SoilObjectEntity?

    /**
     * This notebook's live templates, **blob-free** (arc 7 / B2): the columns a cross-notebook
     * paste needs to shortlist a dedupe candidate. `length(blob)` is a cheap discriminator that
     * SQLite answers without materialising the WEBP, so only the rows that could actually match are
     * loaded whole for the byte compare — the `ClipHeader` discipline applied one level down.
     */
    @Query(
        """SELECT id, text, width, height, length(blob) AS blobLength FROM notebook
           WHERE type = 'template' AND parentId = :notebookId AND deletedAt IS NULL"""
    )
    suspend fun templateDigests(notebookId: String): List<TemplateDigest>

    @Query("SELECT count(*) FROM notebook WHERE type = 'page' AND deletedAt IS NULL")
    suspend fun livePageCount(): Int

    @Query("UPDATE notebook SET deletedAt = :at, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDelete(ids: List<String>, at: Long)

    /** Un-soft-delete rows **in place** (undo of an erase / a page delete) — writing order survives. */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun restore(ids: List<String>, at: Long)

    /** Live stroke ids of a page — cheap (no blobs). */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type = 'stroke' AND deletedAt IS NULL")
    suspend fun liveStrokeIds(pageId: String): List<String>

    /** Live stroke + heading ids of a page — what a page delete soft-deletes along with it.
     *  Superseded by [liveDescendantIds] for page delete since arc 6 (links wrap grandchildren);
     *  still the right call for anything that wants the page's own loose content only. */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type IN ('stroke','heading') AND deletedAt IS NULL")
    suspend fun liveContentIds(pageId: String): List<String>

    /** Live link rows of a page in z-order (arc 6 / K1). */
    @Query("SELECT * FROM notebook WHERE parentId = :pageId AND type = 'link' AND deletedAt IS NULL ORDER BY `order`")
    suspend fun linksOf(pageId: String): List<SoilObjectEntity>

    /** Re-parent rows (arc 6 / K1 — a wrap flips page → link, an unlink flips back; ids untouched). */
    @Query("UPDATE notebook SET parentId = :newParentId, updatedAt = :at WHERE id IN (:ids)")
    suspend fun reparent(ids: List<String>, newParentId: String, at: Long)

    /** Live content ids of a page **one level deeper than [liveContentIds]** (arc 6 / K1): strokes,
     *  headings, links and the page's `document` (arc 19), plus the links' own children (the page's
     *  grandchildren) — what a page delete / undo must carry so a wrapped selection rides its page.
     *  Paper's `liveDescendantIds` with `'heading'` in place of `'object'` (the SN child types).
     *
     *  A `document` is a *product* of the page, not content on it (it is excluded from every
     *  staleness whitelist — [DocumentDao.maxContentUpdatedAt]), but it is still the user's writing
     *  and it belongs to that page: a delete, its undo, and a page copy must all carry it. Only the
     *  page level gains it — a link never wraps a document, so the grandchild branch is unchanged. */
    @Query(
        """SELECT id FROM notebook WHERE deletedAt IS NULL AND (
             (parentId = :pageId AND type IN ('stroke', 'heading', 'link', 'document'))
             OR parentId IN (SELECT id FROM notebook WHERE parentId = :pageId AND type = 'link' AND deletedAt IS NULL))""",
    )
    suspend fun liveDescendantIds(pageId: String): List<String>

    /** Shift live rows by a delta — a link drag's row + heading children (stroke geometry lives in
     *  the blob, so strokes go through their codec instead; see `LinkStore.move`). */
    @Query("UPDATE notebook SET x = x + :dx, y = y + :dy, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun moveBy(ids: List<String>, dx: Float, dy: Float, at: Long)

    /** Every live heading row in the notebook — the Contents gather (arc 4), the one cross-page
     *  read. Blob-free in effect (heading writes never set `blob`), and full-entity deliberately:
     *  `HeadingRows.toHeading` — the tested mapper — takes the entity, and a projection would buy
     *  nothing for a contractually-null column. */
    @Query("SELECT * FROM notebook WHERE type = 'heading' AND deletedAt IS NULL")
    suspend fun liveHeadingsAll(): List<SoilObjectEntity>

    /** Does **any** live heading sit on a live page? — the Contents availability gate (arc 4),
     *  asked on every page flip: EXISTS over ids only, so nothing is materialized and the scan
     *  stops at the first hit. A **wrapped** heading counts too: its `parentId` is a live `link`
     *  whose own parent is a live page (the outline reaches through a link — see [liveLinkPages]),
     *  and the gate must reach exactly as far as the gather does or the button would hide an
     *  outline that has entries. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM notebook h WHERE h.type = 'heading' AND h.deletedAt IS NULL " +
            "AND (h.parentId IN (SELECT p.id FROM notebook p WHERE p.type = 'page' AND p.deletedAt IS NULL) " +
            "OR h.parentId IN (SELECT l.id FROM notebook l WHERE l.type = 'link' AND l.deletedAt IS NULL " +
            "AND l.parentId IN (SELECT p.id FROM notebook p WHERE p.type = 'page' AND p.deletedAt IS NULL))))",
    )
    suspend fun anyLiveHeadingOnLivePage(): Boolean

    /**
     * Every live link row as `id → its page`: the Contents gather's one link → page hop. A wrap
     * re-parents its children page → link but leaves their **coordinates page-absolute**, so this
     * pair is all the outline needs to place a wrapped heading — no payload, no bounds, no blob.
     * Projection-only (two id columns) and small: links per notebook are counted in dozens.
     */
    @Query("SELECT id, parentId FROM notebook WHERE type = 'link' AND deletedAt IS NULL")
    suspend fun liveLinkPages(): List<LinkPage>

    /** Reposition an object (a heading drag) — geometry only, size untouched. */
    @Query("UPDATE notebook SET x = :x, y = :y, updatedAt = :at WHERE id = :id")
    suspend fun setPosition(id: String, x: Float, y: Float, at: Long)

    /** Rewrite a heading's content: text + authoritative level + the re-measured box size. */
    @Query("UPDATE notebook SET text = :text, flags = :flags, width = :width, height = :height, updatedAt = :at WHERE id = :id")
    suspend fun setHeadingContent(id: String, text: String, flags: Int, width: Float, height: Float, at: Long)

    @Query("UPDATE notebook SET refId = :refId, updatedAt = :at WHERE id = :id")
    suspend fun setRefId(id: String, refId: String?, at: Long)

    @Query("UPDATE notebook SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun setText(id: String, text: String?, at: Long)

    @Query("UPDATE notebook SET `order` = :order, updatedAt = :at WHERE id = :id")
    suspend fun setOrder(id: String, order: Int, at: Long)

    @Query("UPDATE notebook SET blob = :blob, updatedAt = :at WHERE id = :id")
    suspend fun setBlob(id: String, blob: ByteArray?, at: Long)

    /** Highest `"order"` among [parentId]'s children of [type], or -1 when there are none. Counts
     *  live **and** soft-deleted rows, so `"order"` stays monotonic across erase → restore (a
     *  stroke un-deleted in place never ties with one committed after its erase). */
    @Query("SELECT COALESCE(MAX(`order`), -1) FROM notebook WHERE parentId = :parentId AND type = :type")
    suspend fun maxOrder(parentId: String, type: String): Int
}

/** A live link's page — [SoilDao.liveLinkPages]. `parentId` is the page the link sits on. */
data class LinkPage(val id: String, val parentId: String)

/** A template row without its pixels — [SoilDao.templateDigests]. */
data class TemplateDigest(
    val id: String,
    val text: String?,
    val width: Float?,
    val height: Float?,
    val blobLength: Int?,
)
