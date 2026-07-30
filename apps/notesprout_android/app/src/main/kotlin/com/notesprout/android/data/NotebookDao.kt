package com.notesprout.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the `notebook` table.
 *
 * All methods are suspend functions — call them from Dispatchers.IO.
 * `order` is a reserved SQL word; queries that touch it use backtick or double-quote quoting.
 */
@Dao
interface NotebookDao {

    // ── Insert ───────────────────────────────────────────────────────────────

    /** Insert a single object (page, layer, stroke, …). Fails on conflict. */
    @Insert
    suspend fun insertObject(obj: NotebookObject)

    /** Insert many objects at once (e.g. a composite parent + its child-row subtree). */
    @Insert
    suspend fun insertObjects(objs: List<NotebookObject>)

    /**
     * Insert a single object, silently ignoring it if the same [NotebookObject.id]
     * already exists.  Used for incremental stroke saves — already-persisted strokes
     * are skipped without re-writing or deleting them.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(obj: NotebookObject)

    // ── Select ───────────────────────────────────────────────────────────────

    /**
     * All non-deleted rows of a given type.
     * Result order is undefined — callers sort as needed.
     */
    @Query("SELECT * FROM notebook WHERE type = :type AND deletedAt IS NULL")
    suspend fun getObjectsByType(type: String): List<NotebookObject>

    /**
     * All non-deleted children of [parentId], sorted by their `order` column ascending.
     * Returns every type under that parent — filter by [NotebookObject.type] in Kotlin
     * when only a specific subtype is needed.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :parentId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getObjectsByParent(parentId: String): List<NotebookObject>

    /**
     * All non-deleted children of any parent in [parentIds], sorted by `order` ascending. Used to
     * batch-load composite subtrees (Phase 2c) in one query instead of N `getObjectsByParent` calls.
     * Callers group by [NotebookObject.parentId] to reconstruct each subtree.
     */
    @Query("SELECT * FROM notebook WHERE parentId IN (:parentIds) AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getObjectsByParents(parentIds: List<String>): List<NotebookObject>

    /** Every direct child id of [parentId], INCLUDING soft-deleted rows (for a hard-delete cascade). */
    @Query("SELECT id FROM notebook WHERE parentId = :parentId")
    suspend fun childIdsIncludingDeleted(parentId: String): List<String>

    /** Hard-delete the given rows by id (used to replace a composite's child subtree in place). */
    @Query("DELETE FROM notebook WHERE id IN (:ids)")
    suspend fun hardDeleteByIds(ids: List<String>)

    /**
     * Hard-delete CONTENT rows orphaned by a purged parent — the child subtree of a deleted composite
     * (sticky/link/heading/text) whose parent was removed by [hardDeleteOldSoftDeleted]. Restricted to
     * content types so structural rows (page/layer) and the refId-referenced `template` library are
     * never touched (templates are linked via page.refId, not the parentId hierarchy). A row whose
     * parent is only *soft*-deleted still has its parent row present, so it is NOT swept (current-session
     * composite deletes stay restorable). Removes one level; the caller loops to cascade through nesting.
     */
    @Query("DELETE FROM notebook WHERE type IN ('stroke','heading','text','line','shape','link','sticky_note') AND parentId NOT IN (SELECT id FROM notebook)")
    suspend fun hardDeleteOrphansOnce(): Int

    /**
     * All non-deleted pages, sorted by `order` ascending.
     * Use this for multi-page navigation — it reflects the canonical page order.
     */
    @Query("SELECT * FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getPagesSorted(): List<NotebookObject>

    /**
     * The single non-deleted layer belonging to [pageId], or null if none exists.
     * Each page has exactly one content layer.
     */
    @Query("SELECT * FROM notebook WHERE type = 'layer' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1")
    suspend fun getLayerForPage(pageId: String): NotebookObject?

    /**
     * All non-deleted strokes belonging to [layerId], sorted by `order` ascending.
     * Used when loading a page's strokes into the drawing view.
     */
    @Query("SELECT * FROM notebook WHERE type = 'stroke' AND parentId = :layerId AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getStrokesForLayer(layerId: String): List<NotebookObject>

    /**
     * All non-deleted heading objects belonging to [layerId], sorted by `order` ascending.
     * Headings embed their strokes in the `data` JSON — they are NOT separate rows.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'heading' AND deletedAt IS NULL ORDER BY \"order\" ASC")
    suspend fun getHeadingsForLayer(layerId: String): List<NotebookObject>

    /**
     * All non-deleted text objects belonging to [layerId], sorted by `order` ascending.
     * Each row's [NotebookObject.data] is a serialized [com.notesprout.android.data.TextObject]
     * carrying the markdown source.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'text' AND deletedAt IS NULL ORDER BY \"order\" ASC")
    suspend fun getTextObjectsForLayer(layerId: String): List<NotebookObject>

    /**
     * All non-deleted line objects belonging to [layerId], sorted by `order` ascending.
     * Each row's [NotebookObject.data] is a serialized [com.notesprout.android.data.LineObject]
     * carrying the line style and orientation.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'line' AND deletedAt IS NULL ORDER BY \"order\" ASC")
    suspend fun getLineObjectsForLayer(layerId: String): List<NotebookObject>

    /**
     * All non-deleted link objects belonging to [layerId], sorted by `order` ascending.
     * Each row's [NotebookObject.data] is a serialized [com.notesprout.android.data.LinkObject]
     * carrying the target, chrome, and the embedded (held) objects. Like headings, the held
     * objects are NOT separate rows — they live in the link's `data` JSON.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'link' AND deletedAt IS NULL ORDER BY \"order\" ASC")
    suspend fun getLinkObjectsForLayer(layerId: String): List<NotebookObject>

    /**
     * All non-deleted sticky note objects belonging to [layerId], sorted by `order` ascending.
     * Each row's [NotebookObject.data] is a serialized [com.notesprout.android.data.StickyNoteObject]
     * carrying the embedded content (strokes/headings/text/lines) in the note's own coordinate space.
     */
    @Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'sticky_note' AND deletedAt IS NULL ORDER BY \"order\" ASC")
    suspend fun getStickyNotesForLayer(layerId: String): List<NotebookObject>

    /**
     * All non-deleted shape objects belonging to [layerId], sorted by `order` ascending.
     * Each row's [NotebookObject.data] is a serialized [com.notesprout.android.data.ShapeObject]
     * carrying the geometry (type, center, extents, rotation, strokeWidth, aspectLocked).
     */
    @Query("SELECT * FROM notebook WHERE parentId = :layerId AND type = 'shape' AND deletedAt IS NULL ORDER BY \"order\" ASC")
    suspend fun getShapeObjectsForLayer(layerId: String): List<NotebookObject>

    /**
     * First non-deleted row of [type], or null if none exist.
     * Useful for retrieving the single page or layer in a fresh notebook.
     */
    @Query("SELECT * FROM notebook WHERE type = :type AND deletedAt IS NULL LIMIT 1")
    suspend fun getFirstByType(type: String): NotebookObject?

    // ── Update ───────────────────────────────────────────────────────────────

    /**
     * Soft-delete a single object by [id].
     * Sets both [NotebookObject.deletedAt] and [NotebookObject.updatedAt] to [deletedAt].
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteById(id: String, deletedAt: Long)

    /**
     * Soft-delete all non-deleted children of [parentId].
     * Used to cascade soft-deletes: e.g. delete all strokes under a layer, or a
     * layer under a page, in a single query.
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun softDeleteByParentId(parentId: String, deletedAt: Long)

    /**
     * Legacy single-row soft-delete; kept for compatibility.
     * Prefer [softDeleteById] in new code.
     */
    @Query("UPDATE notebook SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * Update the sort [order] of a single object.
     * Used when re-sequencing pages after an insertion or deletion.
     */
    @Query("UPDATE notebook SET `order` = :order WHERE id = :id")
    suspend fun updateOrder(id: String, order: Int)

    // ── Notebook metadata ─────────────────────────────────────────────────────

    /**
     * The single notebook metadata row (type = 'notebook', parentId = ''), or null if
     * the notebook pre-dates the metadata row introduction.
     */
    @Query("SELECT * FROM notebook WHERE type = 'notebook' LIMIT 1")
    suspend fun getNotebookObject(): NotebookObject?

    /**
     * Insert or replace the notebook metadata row.
     * Used by [saveLastOpenedPage] to persist the last-viewed page UUID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotebookObject(obj: NotebookObject)

    // ── Generic single-row lookup ─────────────────────────────────────────────

    /**
     * Fetch any single row by its [id], regardless of type or soft-delete status.
     * Used by template loading to look up the page row and read its data JSON.
     */
    @Query("SELECT * FROM notebook WHERE id = :id LIMIT 1")
    suspend fun getObjectById(id: String): NotebookObject?

    // ── Template rows ─────────────────────────────────────────────────────────

    /**
     * All non-deleted template rows in this notebook, ordered by creation time ascending.
     * Used by the template dialog's "Notebook" tab.
     */
    @Query("SELECT * FROM notebook WHERE type = 'template' AND deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getTemplatesSorted(): List<NotebookObject>

    /**
     * A single non-deleted template row by [id], or null if not found.
     * Used when loading a page's stored template bitmap for rendering.
     */
    @Query("SELECT * FROM notebook WHERE type = 'template' AND id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getTemplateById(id: String): NotebookObject?

    // ── Data column update ────────────────────────────────────────────────────

    /**
     * Overwrite the [data] column for the row with [id] and update [updatedAt].
     * Used to persist the page's `template` property after the user picks a template,
     * and to persist page snapshots after non-writing transitions.
     */
    @Query("UPDATE notebook SET data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateData(id: String, data: String, updatedAt: Long)

    /**
     * Set a columnar page row's template (refId), clearing the legacy `data` JSON. The page's size
     * stays in its `boundingBox` column (untouched). Phase 2b — see [pageData].
     */
    @Query("UPDATE notebook SET refId = :templateId, data = '', updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageTemplate(id: String, templateId: String, updatedAt: Long)

    /**
     * Overwrite the serialized point array for a single stroke row.
     * Used by the lasso-move commit path to persist translated stroke coordinates
     * without re-inserting the row or changing any other columns.
     */
    @Query("UPDATE notebook SET data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStrokeData(id: String, data: String, updatedAt: Long)

    /**
     * Overwrite a stroke row's binary [NotebookObject.blob] plus its colour/width columns, clearing
     * the legacy `data`/`boundingBox` (data-model-optimization Phase 1). Colour/width are written too
     * so a lasso-move that lands on a still-legacy row converts it fully to columnar form without
     * losing a non-default colour. Used by the lasso-move commit and undo/redo re-persist paths.
     */
    @Query("UPDATE notebook SET blob = :blob, color = :color, strokeWidth = :strokeWidth, data = '', boundingBox = '', updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStrokeBlob(id: String, blob: ByteArray, color: String, strokeWidth: Float, updatedAt: Long)

    /**
     * Overwrite both [boundingBox] and [data] for a heading row.
     * Used by the lasso-move commit path to persist translated heading position
     * and embedded stroke coordinates together in one SQL statement.
     */
    @Query("UPDATE notebook SET boundingBox = :boundingBox, data = :data, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateHeadingData(id: String, boundingBox: String, data: String, updatedAt: Long)

    // ── Undo/redo restore operations ──────────────────────────────────────────

    /**
     * Restore a soft-deleted row by clearing its [NotebookObject.deletedAt].
     * Updates [NotebookObject.updatedAt] so snapshot-staleness checks detect the change.
     * Used by undo/redo to un-erase strokes and un-delete pages.
     */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreById(id: String, updatedAt: Long)

    /**
     * Restore all child rows of [parentId] whose [NotebookObject.deletedAt] is >= [since].
     *
     * This is the cascade-restore counterpart to [softDeleteByParentId]: when a page was
     * deleted at timestamp T, all its children (layers, strokes) also have deletedAt = T.
     * Passing [since] = T restores exactly those rows without touching children that were
     * independently soft-deleted before the page deletion.
     *
     * Updates [NotebookObject.updatedAt] so snapshot-staleness checks detect the restore.
     */
    @Query("UPDATE notebook SET deletedAt = NULL, updatedAt = :updatedAt WHERE parentId = :parentId AND deletedAt >= :since")
    suspend fun restoreChildrenDeletedSince(parentId: String, since: Long, updatedAt: Long)

    /**
     * The layer row belonging to [pageId], regardless of soft-delete status.
     * Used by undo/redo when restoring or cascade-deleting layers whose page has been
     * soft-deleted and is not returned by [getLayerForPage].
     */
    @Query("SELECT * FROM notebook WHERE type = 'layer' AND parentId = :pageId LIMIT 1")
    suspend fun getLayerForPageAny(pageId: String): NotebookObject?

    // ── TOC queries ───────────────────────────────────────────────────────────

    /**
     * All non-deleted heading rows across the entire notebook, in insertion order.
     * Each heading's parentId is a layer id; callers resolve layer→page via [getObjectsByType].
     * Used by [TocRepository] to build the table of contents.
     */
    @Query("SELECT * FROM notebook WHERE type = 'heading' AND deletedAt IS NULL")
    suspend fun getAllHeadingObjects(): List<NotebookObject>

    /**
     * All non-deleted page rows sorted by `order` ascending.
     * Identical to [getPagesSorted] — exposed under this name for clarity in TOC code.
     */
    @Query("SELECT * FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY `order` ASC")
    suspend fun getAllPages(): List<NotebookObject>

    // ── Page text (recognized-text cache) ─────────────────────────────────────

    /**
     * The single cached [PageText] row for [pageId] (parentId = pageId), or null if the page
     * has never been recognized. Not filtered on soft-delete: the cache is upserted in place,
     * never soft-deleted. Deliberately excluded from [getMaxContentUpdatedAt] — page_text is a
     * derived product, not source content, so it must not invalidate itself.
     */
    @Query("SELECT * FROM notebook WHERE type = 'page_text' AND parentId = :pageId LIMIT 1")
    suspend fun getPageTextRow(pageId: String): NotebookObject?

    // ── Documents (the page's authored Markdown) ──────────────────────────────

    /**
     * The single `document` row for [pageId] (parentId = pageId), or null if the page has none.
     *
     * Filtered on soft-delete, unlike [getPageTextRow]: a document is user content that travels with
     * its page, so it is soft-deleted when the page is and restored when the page is (which is also
     * why it must never be resurrected by a plain upsert). See docs/documents.md.
     */
    @Query("SELECT * FROM notebook WHERE type = 'document' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1")
    suspend fun getDocumentRow(pageId: String): NotebookObject?

    /**
     * Overwrite a document row's Markdown and its source watermark. Clears the legacy `data` JSON —
     * documents are columnar-only (text in the `text` column) and were never written any other way.
     */
    @Query("UPDATE notebook SET data = '', text = :text, srcUpdatedAt = :srcUpdatedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDocument(id: String, text: String, srcUpdatedAt: Long?, updatedAt: Long)

    // ── Snapshot staleness check ──────────────────────────────────────────────

    /**
     * The maximum [updatedAt] across ALL stroke and heading rows (including soft-deleted)
     * under [layerId].  Soft-deleted rows have [NotebookObject.updatedAt] set to their
     * deletion timestamp, so this query detects new content, erased strokes, and mutated
     * headings that occurred after the last snapshot.
     *
     * Returns null if no content rows exist for the layer (blank page).
     *
     * Used in stale-snapshot detection: if the result exceeds the page row's
     * [NotebookObject.updatedAt], the stored snapshot pre-dates a content change and
     * must be discarded in favour of a full re-render.
     */
    @Query("SELECT MAX(updatedAt) FROM notebook WHERE type IN ('stroke', 'heading', 'text', 'line', 'link', 'sticky_note', 'shape') AND parentId = :layerId")
    suspend fun getMaxContentUpdatedAt(layerId: String): Long?

    /**
     * Count of content rows (any layer) whose [NotebookObject.updatedAt] is at or after [since].
     * Soft-deleted rows carry `updatedAt = deletedAt`, so erases count as edits too. Used at seal
     * time to decide whether this session should log an EDITED activity event.
     */
    @Query("SELECT COUNT(*) FROM notebook WHERE type IN ('stroke', 'heading', 'text', 'line', 'link', 'sticky_note', 'shape') AND updatedAt >= :since")
    suspend fun countContentModifiedSince(since: Long): Int

    /**
     * Hard-delete all soft-deleted rows whose [NotebookObject.deletedAt] predates [before].
     * Called at seal time with the session-start timestamp so only rows soft-deleted in
     * previous sessions are purged — current-session deletes (still undoable) are left intact.
     */
    @Query("DELETE FROM notebook WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun hardDeleteOldSoftDeleted(before: Long)

    // ── Transitional compaction (legacy-ts strip + PNG→WEBP transcode) ─────────

    /**
     * Every stroke row (regardless of soft-delete status) whose `data` still contains a
     * legacy per-point `"ts"`. The `LIKE` filter means already-compacted notebooks return
     * an empty list cheaply, so [NotebookCompactor] can run on every seal without cost.
     */
    @Query("SELECT id, data FROM notebook WHERE type = 'stroke' AND data LIKE '%\"ts\":%'")
    suspend fun strokeRowsWithLegacyTimestamp(): List<StrokeRowData>

    /**
     * Cheap header projection for every embedded-template row: id, type, and only the first 4000
     * base64 chars of `data` (enough to read the PNG/WEBP magic + codec chunk past a VP8X header +
     * ICC profile). [NotebookCompactor] decides from the head whether the row needs a WEBP re-encode,
     * then pulls the full [imageDataForId] only for those, so already-converted notebooks do no heavy
     * work. Page snapshots and cover objects no longer carry images (snapshots are stripped and cover
     * rows deleted by the compactor), so only `template` rows are scanned.
     */
    @Query("SELECT id, type, substr(data, 1, 4000) AS head FROM notebook WHERE type = 'template'")
    suspend fun imageRowHeads(): List<ImageRowHead>

    /**
     * Every page row (regardless of soft-delete status) whose `data` still carries a legacy
     * `snapshot` field. Per-page snapshots are no longer stored; [NotebookCompactor] strips them by
     * re-serializing through [PageData] (which drops the now-unknown key). The `LIKE` means
     * already-stripped notebooks return an empty list cheaply, so this can run on every seal.
     */
    @Query("SELECT id, data FROM notebook WHERE type = 'page' AND data LIKE '%\"snapshot\":%'")
    suspend fun pageRowsWithSnapshot(): List<StrokeRowData>

    /**
     * Hard-delete every legacy `type='cover'` row (the removed custom-cover feature). Returns the
     * number of rows deleted. Transitional cleanup — cover objects can never be recreated, so a
     * hard delete (no undo) is correct.
     */
    @Query("DELETE FROM notebook WHERE type = 'cover'")
    suspend fun deleteCoverRows(): Int

    /** Full `data` for a single row — pulled only for rows [NotebookCompactor] decides to re-encode. */
    @Query("SELECT data FROM notebook WHERE id = :id")
    suspend fun imageDataForId(id: String): String?

    /**
     * Every heading/text row (regardless of soft-delete status) whose `data` still carries a
     * non-empty embedded `strokes` array. Recognized headings and text objects no longer retain
     * their strokes (they render from text and never revert), so [NotebookCompactor] strips the
     * dead strokes from these. Unrecognized fallbacks — which legitimately keep strokes — are
     * filtered out in the compactor by decoding. The `LIKE` means already-stripped notebooks return
     * an empty list cheaply, so this can run on every seal.
     */
    @Query("SELECT id, type, data FROM notebook WHERE type IN ('heading', 'text') AND data LIKE '%\"strokes\":[{%'")
    suspend fun headingTextRowsWithStrokes(): List<ObjectRowData>

    /**
     * Overwrite a row's [data] WITHOUT touching [NotebookObject.updatedAt].
     * Used only by [NotebookCompactor]: stripping dead `ts` and re-encoding a snapshot to WEBP are
     * not content edits, and bumping `updatedAt` would falsely invalidate the page snapshot
     * (see [getMaxContentUpdatedAt]).
     */
    @Query("UPDATE notebook SET data = :data WHERE id = :id")
    suspend fun rewriteObjectDataKeepingTimestamp(id: String, data: String)

    /**
     * Generic columnar update (data-model-optimization Phase 1, step 4): overwrite every payload
     * column + `updatedAt` for the row [id], clearing the legacy `data`/`boundingBox`. Does NOT touch
     * `createdAt`/`parentId`/`order` (structural), so it is the in-place counterpart to the per-type
     * `toRow()` builders — used by every non-stroke lasso-move / edit re-persist path. Prefer the
     * [com.notesprout.android.data.updateColumns] extension, which feeds this from a `toRow()` row.
     */
    @Query(
        "UPDATE notebook SET boundingBox = '', data = '', x = :x, y = :y, width = :width, " +
        "height = :height, text = :text, color = :color, strokeWidth = :strokeWidth, refId = :refId, " +
        "level = :level, lineStyle = :lineStyle, orientation = :orientation, dotSpacing = :dotSpacing, " +
        "shapeType = :shapeType, centerX = :centerX, centerY = :centerY, rotationDeg = :rotationDeg, " +
        "pointCount = :pointCount, contentW = :contentW, contentH = :contentH, linkTarget = :linkTarget, " +
        "chrome = :chrome, flags = :flags, blob = :blob, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateObjectColumns(
        id: String, x: Float?, y: Float?, width: Float?, height: Float?, text: String?, color: String?,
        strokeWidth: Float?, refId: String?, level: Int?, lineStyle: String?, orientation: String?,
        dotSpacing: Float?, shapeType: String?, centerX: Float?, centerY: Float?, rotationDeg: Float?,
        pointCount: Int?, contentW: Float?, contentH: Float?, linkTarget: String?, chrome: String?,
        flags: Int?, blob: ByteArray?, updatedAt: Long,
    )

    // ── Lazy stroke-format conversion (data-model-optimization Phase 1) ─────────

    /**
     * Legacy stroke rows still stored as JSON (no binary [NotebookObject.blob] yet). Self-limiting:
     * once converted, `blob` is non-null and the row drops out of the scan, so [NotebookCompactor]
     * can run this on every seal. The `LIKE` keeps an already-converted notebook's scan cheap.
     */
    @Query("SELECT id, data FROM notebook WHERE type = 'stroke' AND blob IS NULL AND data LIKE '%\"points\":%'")
    suspend fun legacyStrokeRowsToConvert(): List<StrokeRowData>

    /**
     * Convert a stroke row to the binary format — write [blob] + colour/width columns, clear the
     * legacy `data`/`boundingBox` — WITHOUT touching [NotebookObject.updatedAt]. A format change, not a
     * content edit, so (like the other compactor passes) the file is not re-flagged for backup.
     */
    @Query("UPDATE notebook SET blob = :blob, color = :color, strokeWidth = :strokeWidth, data = '', boundingBox = '' WHERE id = :id")
    suspend fun convertStrokeToBlobKeepingTimestamp(id: String, blob: ByteArray, color: String, strokeWidth: Float)

    // ── Lazy composite→child-row conversion (data-model-optimization Phase 2c) ──

    /**
     * Legacy composite rows (heading/text/link/sticky) that still carry their nested content inline —
     * either a pre-columnar `data` JSON or the Phase-1/2b `zlib(JSON)` blob. [NotebookCompactor]
     * converts these to child-row subtrees on seal. Self-limiting: once converted a composite has
     * data = '' AND blob = NULL, so it drops out of this scan.
     */
    @Query("SELECT * FROM notebook WHERE type IN ('heading','text','link','sticky_note') AND (data <> '' OR blob IS NOT NULL) AND deletedAt IS NULL")
    suspend fun legacyBlobCompositeRows(): List<NotebookObject>

    /**
     * Recognized heading/text parents (text != null) that still have child rows — the orphan strokes
     * a fallback→recognized transition leaves behind (see [com.notesprout.android.data.replaceHeadingSubtree]).
     * [NotebookCompactor] hard-deletes their descendants. Returns empty for a clean notebook.
     */
    @Query("SELECT DISTINCT p.id FROM notebook p JOIN notebook c ON c.parentId = p.id WHERE p.type IN ('heading','text') AND p.text IS NOT NULL AND p.deletedAt IS NULL")
    suspend fun recognizedCompositeParentIdsWithChildren(): List<String>

    /**
     * Legacy structural/leaf rows (page/layer/notebook/template/shape/line) still storing their
     * payload as `data` JSON. [NotebookCompactor] converts these to the Phase 2b/1 columnar form on
     * seal. Self-limiting: once converted a row has data = '' and drops out of the scan.
     */
    @Query("SELECT * FROM notebook WHERE type IN ('page','layer','notebook','template','shape','line') AND data <> '' AND deletedAt IS NULL")
    suspend fun legacyStructuralRows(): List<NotebookObject>

    /** Columnar layer write keeping [NotebookObject.updatedAt] + boundingBox: label→text, flags. */
    @Query("UPDATE notebook SET data = '', text = :text, flags = :flags, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLayerColumnarKeepingTimestamp(id: String, text: String, flags: Int, updatedAt: Long)

    /** Columnar template write keeping [NotebookObject.updatedAt] + boundingBox: name→text, image→blob. */
    @Query("UPDATE notebook SET data = '', text = :text, blob = :blob, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTemplateColumnarKeepingTimestamp(id: String, text: String, blob: ByteArray?, updatedAt: Long)
}

/** Minimal id/data projection for [NotebookDao.strokeRowsWithLegacyTimestamp]. */
data class StrokeRowData(val id: String, val data: String)

/** id/type/data projection for [NotebookDao.headingTextRowsWithStrokes] (type selects heading vs text decode). */
data class ObjectRowData(val id: String, val type: String, val data: String)

/** id/type/base64-head projection for [NotebookDao.imageRowHeads] (type selects the image field). */
data class ImageRowHead(val id: String, val type: String, val head: String)
