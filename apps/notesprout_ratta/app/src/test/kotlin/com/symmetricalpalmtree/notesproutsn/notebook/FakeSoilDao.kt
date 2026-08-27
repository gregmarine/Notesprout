package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.LinkPage
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
    override suspend fun notebookRow() = rows.values.firstOrNull { it.type == "notebook" }
    override suspend fun templateDigests(notebookId: String) = rows.values
        .filter { it.type == "template" && it.parentId == notebookId && it.deletedAt == null }
        .map { TemplateDigest(it.id, it.text, it.width, it.height, it.blob?.size) }
    override suspend fun livePageCount() = rows.values.count { it.type == "page" && it.deletedAt == null }
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
        rows.values.filter { it.parentId == pageId && (it.type == "stroke" || it.type == "heading") && it.deletedAt == null }
            .map { it.id }
    override suspend fun linksOf(pageId: String) =
        rows.values.filter { it.parentId == pageId && it.type == "link" && it.deletedAt == null }
            .sortedBy { it.order }
    override suspend fun reparent(ids: List<String>, newParentId: String, at: Long) {
        for (id in ids) rows[id]?.let { rows[id] = it.copy(parentId = newParentId, updatedAt = at) }
        events += "reparent:${ids.joinToString(",")}->$newParentId"
    }
    override suspend fun liveDescendantIds(pageId: String): List<String> {
        val linkIds = rows.values
            .filter { it.parentId == pageId && it.type == "link" && it.deletedAt == null }
            .map { it.id }
            .toSet()
        return rows.values.filter {
            it.deletedAt == null && (
                (it.parentId == pageId && (it.type == "stroke" || it.type == "heading" || it.type == "link")) ||
                    it.parentId in linkIds
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
    override suspend fun liveLinkPages() =
        rows.values.filter { it.type == "link" && it.deletedAt == null }
            .map { LinkPage(it.id, it.parentId) }
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
    override suspend fun setHeadingContent(id: String, text: String, flags: Int, width: Float, height: Float, at: Long) {
        rows[id]?.let { rows[id] = it.copy(text = text, flags = flags, width = width, height = height, updatedAt = at) }
        events += "setHeadingContent:$id"
    }
    override suspend fun maxOrder(parentId: String, type: String) =
        rows.values.filter { it.parentId == parentId && it.type == type }.maxOfOrNull { it.order } ?: -1
}
