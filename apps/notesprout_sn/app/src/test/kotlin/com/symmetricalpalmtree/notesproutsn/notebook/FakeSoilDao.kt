package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.LinkPage
import com.symmetricalpalmtree.notesproutsn.data.soil.LinkRow
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.TemplateDigest
import kotlinx.coroutines.delay

/**
 * Minimal in-memory `notebook` table + an event log in apply order — the shared fake behind
 * [StrokeStoreTest] and [HeadingStoreTest]. The queue and ordering logic are what those suites
 * test, not Room.
 */
class FakeSoilDao : SoilDao {
    val rows = LinkedHashMap<String, SoilObjectEntity>()
    val events = mutableListOf<String>()
    var upsertDelayMs = 0L

    override suspend fun upsert(row: SoilObjectEntity) {
        if (upsertDelayMs > 0) delay(upsertDelayMs)
        rows[row.id] = row
        events += "upsert:${row.id}"
    }
    override suspend fun upsertAll(rows: List<SoilObjectEntity>) = rows.forEach { upsert(it) }
    override suspend fun byId(id: String) = rows[id]
    override suspend fun byIds(ids: List<String>) = ids.mapNotNull { rows[it] }
    override suspend fun childrenOfType(parentId: String, type: String) =
        rows.values.filter { it.parentId == parentId && it.type == type && it.deletedAt == null }
            .sortedBy { it.order }
    /** Mirrors `SoilDao.childrenOf`'s `type NOT IN ('sketch','sketch_graphite','sketch_ink')`
     *  exclusion (arc 43 / K3, all three names since arc 45 / G2) — a page-sized image never rides
     *  an untyped, blob-inclusive page read, and a leftover arc-43 row never surfaces as a child. */
    override suspend fun childrenOf(parentId: String) =
        rows.values.filter { it.parentId == parentId && it.type !in SKETCH_TYPES && it.deletedAt == null }
            .sortedBy { it.order }
    override suspend fun notebookRow() = rows.values.firstOrNull { it.type == "notebook" }
    override suspend fun templateDigests(notebookId: String) = rows.values
        .filter { it.type == "template" && it.parentId == notebookId && it.deletedAt == null }
        .map { TemplateDigest(it.id, it.text, it.width, it.height, it.blob?.size) }
    override suspend fun livePageCount() = rows.values.count { it.type == "page" && it.deletedAt == null }
    override suspend fun livePageIds(notebookId: String) = rows.values
        .filter { it.type == "page" && it.parentId == notebookId && it.deletedAt == null }
        .sortedBy { it.order }
        .map { it.id }
    override suspend fun softDelete(ids: List<String>, at: Long) {
        for (id in ids) rows[id]?.let { if (it.deletedAt == null) rows[id] = it.copy(deletedAt = at, updatedAt = at) }
        events += "softDelete:${ids.joinToString(",")}"
    }
    override suspend fun restore(ids: List<String>, at: Long) {
        for (id in ids) rows[id]?.let { if (it.deletedAt != null) rows[id] = it.copy(deletedAt = null, updatedAt = at) }
        events += "restore:${ids.joinToString(",")}"
    }
    override suspend fun liveStrokeIds(pageId: String) =
        rows.values.filter { it.parentId == pageId && it.type == "stroke" && it.deletedAt == null }.map { it.id }
    override suspend fun liveContentIds(pageId: String) =
        rows.values.filter { it.parentId == pageId && it.type in LOOSE_CONTENT && it.deletedAt == null }
            .map { it.id }
    override suspend fun linksOf(pageId: String) =
        rows.values.filter { it.parentId == pageId && it.type == "link" && it.deletedAt == null }
            .sortedBy { it.order }
    override suspend fun reparent(ids: List<String>, newParentId: String, at: Long) {
        for (id in ids) rows[id]?.let { rows[id] = it.copy(parentId = newParentId, updatedAt = at) }
        events += "reparent:${ids.joinToString(",")}->$newParentId"
    }
    override suspend fun liveDescendantIds(pageId: String) = descendantIds(pageId, withSketch = true)

    /** Mirrors `SoilDao.liveErasableIds` (arc 43 / K3): [liveDescendantIds] minus the page's
     *  **two** sketch rasters — Erase page is ink only (decision 11). */
    override suspend fun liveErasableIds(pageId: String) = descendantIds(pageId, withSketch = false)

    private fun descendantIds(pageId: String, withSketch: Boolean): List<String> {
        val linkIds = rows.values
            .filter { it.parentId == pageId && it.type == "link" && it.deletedAt == null }
            .map { it.id }
            .toSet()
        val stickyIds = rows.values
            .filter { it.type == "sticky_note" && it.deletedAt == null && (it.parentId == pageId || it.parentId in linkIds) }
            .map { it.id }
            .toSet()
        // The dead arc-43 `sketch` name is in neither list: a row nothing can read is a row nothing
        // should copy (decision 4).
        val pageLevel = LOOSE_CONTENT + setOf("link", "document") +
            if (withSketch) setOf("sketch_graphite", "sketch_ink") else emptySet()
        return rows.values.filter {
            it.deletedAt == null && (
                (it.parentId == pageId && it.type in pageLevel) ||
                    it.parentId in linkIds ||
                    it.parentId in stickyIds
                )
        }.map { it.id }
    }
    override suspend fun moveBy(ids: List<String>, dx: Float, dy: Float, at: Long) {
        for (id in ids) rows[id]?.let {
            if (it.deletedAt == null) {
                rows[id] = it.copy(x = (it.x ?: 0f) + dx, y = (it.y ?: 0f) + dy, updatedAt = at)
            }
        }
        events += "moveBy:${ids.joinToString(",")}"
    }
    override suspend fun liveHeadingsAll() =
        rows.values.filter { it.type == "heading" && it.deletedAt == null }
    override suspend fun anyLiveHeadingOnLivePage() =
        rows.values.any { h ->
            h.type == "heading" && h.deletedAt == null &&
                rows[h.parentId]?.let { p ->
                    p.deletedAt == null && (
                        p.type == "page" ||
                            (p.type == "link" &&
                                rows[p.parentId]?.let { it.type == "page" && it.deletedAt == null } == true)
                        )
                } == true
        }
    override suspend fun hasLiveDocument() =
        rows.values.any { it.type == "document" && it.deletedAt == null && !it.text.isNullOrBlank() }
    override suspend fun hasLiveSketch(pageId: String) =
        rows.values.any {
            it.parentId == pageId && it.type in LIVE_SKETCH_TYPES &&
                it.deletedAt == null && (it.blob?.size ?: 0) > 0
        }
    override suspend fun stickyIdsWithContent(): List<String> =
        rows.values.filter { it.type == "sticky_note" && it.deletedAt == null }
            .filter { s -> rows.values.any { it.parentId == s.id && it.type == "stroke" && it.deletedAt == null } }
            .map { it.id }
    override suspend fun liveLinkPages() =
        rows.values.filter { it.type == "link" && it.deletedAt == null }
            .map { LinkPage(it.id, it.parentId) }
    override suspend fun liveLinkRows() =
        rows.values.filter { it.type == "link" && it.deletedAt == null }
            .map { LinkRow(it.id, it.parentId, it.text, it.createdAt) }
    override suspend fun setRefId(id: String, refId: String?, at: Long) {
        rows[id]?.let { rows[id] = it.copy(refId = refId, updatedAt = at) }
    }
    override suspend fun setText(id: String, text: String?, at: Long) {
        rows[id]?.let { rows[id] = it.copy(text = text, updatedAt = at) }
    }
    override suspend fun setOrder(id: String, order: Int, at: Long) {
        rows[id]?.let { rows[id] = it.copy(order = order, updatedAt = at) }
    }
    override suspend fun setBlob(id: String, blob: ByteArray?, at: Long) {
        rows[id]?.let { rows[id] = it.copy(blob = blob, updatedAt = at) }
    }
    override suspend fun setPosition(id: String, x: Float, y: Float, at: Long) {
        rows[id]?.let { rows[id] = it.copy(x = x, y = y, updatedAt = at) }
        events += "setPosition:$id"
    }
    override suspend fun setTextContent(id: String, text: String, width: Float, height: Float, at: Long) {
        rows[id]?.let { rows[id] = it.copy(text = text, width = width, height = height, updatedAt = at) }
        events += "setTextContent:$id"
    }
    override suspend fun setBox(id: String, x: Float, y: Float, width: Float, height: Float, at: Long) {
        rows[id]?.let { rows[id] = it.copy(x = x, y = y, width = width, height = height, updatedAt = at) }
        events += "setBox:$id"
    }
    override suspend fun setShapeGeometry(id: String, x: Float, y: Float, width: Float, height: Float, flags: Long, at: Long) {
        rows[id]?.let { rows[id] = it.copy(x = x, y = y, width = width, height = height, flags = flags, updatedAt = at) }
        events += "setShapeGeometry:$id"
    }
    override suspend fun stickiesOf(pageId: String) =
        rows.values.filter { it.parentId == pageId && it.type == "sticky_note" && it.deletedAt == null }
            .sortedBy { it.order }
    override suspend fun setHeadingContent(id: String, text: String, flags: Int, width: Float, height: Float, at: Long) {
        // The DAO takes a heading level as an `Int`; the column is the family's 64-bit `flags`
        // (arc 19 retype) — SQLite widens it on the way in, and so does the fake.
        rows[id]?.let { rows[id] = it.copy(text = text, flags = flags.toLong(), width = width, height = height, updatedAt = at) }
        events += "setHeadingContent:$id"
    }
    override suspend fun maxOrder(parentId: String, type: String) =
        rows.values.filter { it.parentId == parentId && it.type == type }.maxOfOrNull { it.order } ?: -1

    private companion object {
        /** Mirrors `SoilDao.liveContentIds`' kind list — the page's own loose content. */
        val LOOSE_CONTENT = setOf("stroke", "heading", "text", "shape", "sticky_note")

        /** The two live raster row names (arc 45 / G2) — what `hasLiveSketch` asks about. */
        val LIVE_SKETCH_TYPES = setOf("sketch_graphite", "sketch_ink")

        /** Those two **and** the dead arc-43 name — what `childrenOf` excludes. */
        val SKETCH_TYPES = LIVE_SKETCH_TYPES + "sketch"
    }
}
