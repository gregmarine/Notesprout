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

    /** Every live child of [parentId] in `order`, whatever its type (arc 34 / L16) — for a reader
     *  that wants most of the kinds anyway and would otherwise ask the same index once per type.
     *  Filtering the answer in Kotlin keeps each kind's own order: one `ORDER BY` over the whole
     *  set, and a filter never reorders what it keeps. */
    @Query("SELECT * FROM notebook WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY `order`")
    suspend fun childrenOf(parentId: String): List<SoilObjectEntity>

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

    /**
     * This notebook's live page ids **in page order**, blob-free (arc 21 / W4).
     *
     * The library's search merge asks this to turn a tagged page id into "Page 3" and to tell a
     * page that still exists from one that was deleted under its tag. Ids only: a search shelf
     * reading page rows whole would pull every template and cover blob in the file with them.
     */
    @Query(
        """SELECT id FROM notebook WHERE type = 'page' AND parentId = :notebookId
           AND deletedAt IS NULL ORDER BY `order`"""
    )
    suspend fun livePageIds(notebookId: String): List<String>

    @Query("UPDATE notebook SET deletedAt = :at, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDelete(ids: List<String>, at: Long)

    /** Un-soft-delete rows **in place** (undo of an erase / a page delete) — writing order survives. */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :at WHERE id IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun restore(ids: List<String>, at: Long)

    /** Live stroke ids of a page — cheap (no blobs). */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type = 'stroke' AND deletedAt IS NULL")
    suspend fun liveStrokeIds(pageId: String): List<String>

    /** Live loose content ids of a page — strokes, headings and (arc 28) texts, shapes and sticky
     *  icons. Superseded by [liveDescendantIds] for page delete since arc 6 (links wrap
     *  grandchildren, stickies hold children); still the right call for anything that wants the
     *  page's own loose content only. */
    @Query("SELECT id FROM notebook WHERE parentId = :pageId AND type IN ('stroke','heading','text','shape','sticky_note') AND deletedAt IS NULL")
    suspend fun liveContentIds(pageId: String): List<String>

    /** Live link rows of a page in z-order (arc 6 / K1). */
    @Query("SELECT * FROM notebook WHERE parentId = :pageId AND type = 'link' AND deletedAt IS NULL ORDER BY `order`")
    suspend fun linksOf(pageId: String): List<SoilObjectEntity>

    /** Re-parent rows (arc 6 / K1 — a wrap flips page → link, an unlink flips back; ids untouched). */
    @Query("UPDATE notebook SET parentId = :newParentId, updatedAt = :at WHERE id IN (:ids)")
    suspend fun reparent(ids: List<String>, newParentId: String, at: Long)

    /** Live content ids of a page **two levels deeper than [liveContentIds]** (arc 6 / K1, grown
     *  arc 28 / H1): strokes, headings, links, texts, shapes, stickies and the page's `document`
     *  (arc 19); plus the links' own children (the page's grandchildren — which since arc 28 may
     *  include a sticky icon); plus **every sticky's content strokes**, whether the sticky sits on
     *  the page or inside a link (a great-grandchild branch) — what a page delete / undo must
     *  carry so a wrapped selection and a note's content both ride their page.
     *
     *  A `document` is a *product* of the page, not content on it (it is excluded from every
     *  staleness whitelist — [DocumentDao.maxContentUpdatedAt]), but it is still the user's writing
     *  and it belongs to that page: a delete, its undo, and a page copy must all carry it. Only the
     *  page level gains it — a link never wraps a document. */
    @Query(
        """SELECT id FROM notebook WHERE deletedAt IS NULL AND (
             (parentId = :pageId AND type IN ('stroke', 'heading', 'link', 'document', 'text', 'shape', 'sticky_note'))
             OR parentId IN (SELECT id FROM notebook WHERE parentId = :pageId AND type = 'link' AND deletedAt IS NULL)
             OR parentId IN (SELECT s.id FROM notebook s WHERE s.type = 'sticky_note' AND s.deletedAt IS NULL AND (
                   s.parentId = :pageId
                   OR s.parentId IN (SELECT l.id FROM notebook l WHERE l.parentId = :pageId AND l.type = 'link' AND l.deletedAt IS NULL))))""",
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

    /** Does this notebook hold **any** live document with real text? — the Export screen's gate
     *  (arc 19 / M9): a `SOURCE_DOCUMENT` exporter is listed, and the PDF Source row shown, only
     *  when this answers true. Blank text counts as absent (the repository's blank-means-absent
     *  rule, asked in SQL so a foreign-written blank row cannot open a chooser entry that can
     *  only refuse). The reader that follows is Kotlin `isBlank()`, so the trim set here is
     *  [SoilSql.BLANK_CHARS] rather than SQLite's default space-only `TRIM` — a row holding one
     *  newline is exactly the foreign-written shape this gate exists to catch. Deliberately
     *  parent-agnostic: a notebook document or any page document both make the notebook
     *  exportable as text. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM notebook WHERE type = 'document' AND deletedAt IS NULL " +
            "AND TRIM(COALESCE(text, ''), " + SoilSql.BLANK_CHARS + ") != '')",
    )
    suspend fun hasLiveDocument(): Boolean

    /** Ids of every live sticky note that holds at least one live stroke — the notes the PDF
     *  endnotes (arc 28 / D7) render; an empty note has nothing to show and gets no page. Asked
     *  once per bake, notebook-wide, so the page walk never opens a note's blobs to find out. */
    @Query(
        "SELECT DISTINCT s.id FROM notebook s JOIN notebook c ON c.parentId = s.id " +
            "WHERE s.type = 'sticky_note' AND s.deletedAt IS NULL " +
            "AND c.type = 'stroke' AND c.deletedAt IS NULL",
    )
    suspend fun stickyIdsWithContent(): List<String>

    /**
     * Every live link row as `id → its page`: the Contents gather's one link → page hop. A wrap
     * re-parents its children page → link but leaves their **coordinates page-absolute**, so this
     * pair is all the outline needs to place a wrapped heading — no payload, no bounds, no blob.
     * Projection-only (two id columns) and small: links per notebook are counted in dozens.
     */
    @Query("SELECT id, parentId FROM notebook WHERE type = 'link' AND deletedAt IS NULL")
    suspend fun liveLinkPages(): List<LinkPage>

    /** Every live link with its payload and stamp, blob-free — the Bible notes index's read
     *  (arc 42): which of a notebook's links are Bible references, on which page, and since when.
     *  `text` is the payload (`LinkPayload`); the caller decides what is a Bible link. */
    @Query("SELECT id, parentId, text, createdAt FROM notebook WHERE type = 'link' AND deletedAt IS NULL")
    suspend fun liveLinkRows(): List<LinkRow>

    /** Reposition an object (a heading drag) — geometry only, size untouched. */
    @Query("UPDATE notebook SET x = :x, y = :y, updatedAt = :at WHERE id = :id")
    suspend fun setPosition(id: String, x: Float, y: Float, at: Long)

    /** Rewrite a heading's content: text + authoritative level + the re-measured box size. */
    @Query("UPDATE notebook SET text = :text, flags = :flags, width = :width, height = :height, updatedAt = :at WHERE id = :id")
    suspend fun setHeadingContent(id: String, text: String, flags: Int, width: Float, height: Float, at: Long)

    /** Rewrite a text object's content (arc 28): the Markdown source + the re-measured box size.
     *  Top-left is kept — the box grows from its anchor. */
    @Query("UPDATE notebook SET text = :text, width = :width, height = :height, updatedAt = :at WHERE id = :id")
    suspend fun setTextContent(id: String, text: String, width: Float, height: Float, at: Long)

    /** Rewrite a shape's whole geometry (arc 28, a transform): centre, un-rotated extents and the
     *  packed `flags` (aspect · points · rotation) — `style` and `strokeWidth` untouched. */
    @Query("UPDATE notebook SET x = :x, y = :y, width = :width, height = :height, flags = :flags, updatedAt = :at WHERE id = :id")
    suspend fun setShapeGeometry(id: String, x: Float, y: Float, width: Float, height: Float, flags: Long, at: Long)

    /** Rewrite a row's whole box — top-left **and** size (arc 38 / R3). The one mutation a link's
     *  bounds have besides a move: a Bible link wraps one text object, and re-writing that text
     *  re-measures it, so the link's box has to follow it. `flags` and `text` are untouched, which
     *  is what keeps it off [setShapeGeometry]'s toes. */
    @Query("UPDATE notebook SET x = :x, y = :y, width = :width, height = :height, updatedAt = :at WHERE id = :id")
    suspend fun setBox(id: String, x: Float, y: Float, width: Float, height: Float, at: Long)

    /** Live sticky rows of a page in z-order (arc 28) — icons only; content is read per note. */
    @Query("SELECT * FROM notebook WHERE parentId = :pageId AND type = 'sticky_note' AND deletedAt IS NULL ORDER BY `order`")
    suspend fun stickiesOf(pageId: String): List<SoilObjectEntity>

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

/** Literals spliced into [SoilDao]'s queries. `const` because an annotation argument has to be
 *  one — the value is folded into the SQL at compile time, so what Room sees is still a string. */
object SoilSql {

    /**
     * The character set a `TRIM(x, …)` needs to agree with Kotlin `isBlank()`: space, tab, newline,
     * vertical tab, form feed, carriage return. SQLite's one-argument `TRIM` strips only spaces,
     * which would let a row holding `"\n"` read as non-blank in SQL and blank in Kotlin.
     *
     * Accepted residual mismatch: Kotlin also calls the ASCII separators `U+001C`–`U+001F` and the
     * Unicode spaces (`U+00A0`, `U+2000`…) blank, and this does not. Nothing in this app writes
     * one; a foreign row that does is answered true here and refused by the reader — the same
     * outcome as before this trim set existed, for a far narrower set of inputs.
     */
    const val BLANK_CHARS =
        "' ' || char(9) || char(10) || char(11) || char(12) || char(13)"
}

/** A live link's page — [SoilDao.liveLinkPages]. `parentId` is the page the link sits on. */
data class LinkPage(val id: String, val parentId: String)

/** A live link's payload and stamp — [SoilDao.liveLinkRows] (arc 42 "Notes"). */
data class LinkRow(val id: String, val parentId: String, val text: String?, val createdAt: Long)

/** A template row without its pixels — [SoilDao.templateDigests]. */
data class TemplateDigest(
    val id: String,
    val text: String?,
    val width: Float?,
    val height: Float?,
    val blobLength: Int?,
)
