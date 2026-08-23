package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDao
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
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
    override suspend fun liveHeadingsAll() =
        rows.values.filter { it.type == "heading" && it.deletedAt == null }
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
