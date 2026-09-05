package com.symmetricalpalmtree.notesproutsn.data.index

import com.symmetricalpalmtree.notesproutsn.data.clip.ClipHeader

/**
 * Minimal in-memory `objects` table — Room is not what is under test. Shared by the clipboard
 * store's tests and arc 13's template-shelf tests, which is why it sits beside [ObjectDao] rather
 * than inside either feature's package.
 */
class FakeObjectDao : ObjectDao {

    val rows = LinkedHashMap<String, ObjectEntity>()

    /** Stands in for SQLCipher refusing a row that overflows the cursor window (B3 review). */
    var clipBlobThrows = false

    override suspend fun upsert(row: ObjectEntity) { rows[row.id] = row }

    override suspend fun byId(id: String) = rows[id]

    override suspend fun summaryById(id: String) = rows[id]?.toSummary()

    override suspend fun childrenOfType(parentId: String?, type: String) =
        rows.values.filter { it.type == type && it.deletedAt == null && it.parentId == parentId }.map { it.toSummary() }

    override suspend fun aliveNotebooks(ids: List<String>) =
        ids.mapNotNull { rows[it] }
            .filter { it.type == ObjectType.NOTEBOOK && it.deletedAt == null }
            .map { it.toSummary() }

    override suspend fun allAliveOfType(type: String): List<ObjectSummary> =
        rows.values.filter { it.type == type && it.deletedAt == null }.map { it.toSummary() }

    override suspend fun countAliveNotebooksByScope(scope: String): Int =
        rows.values.count { it.type == ObjectType.NOTEBOOK && it.deletedAt == null && it.keyScope == scope }

    override suspend fun aliveNotebookIdsByScope(scope: String): List<String> =
        rows.values.filter { it.type == ObjectType.NOTEBOOK && it.deletedAt == null && it.keyScope == scope }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id })).map { it.id }

    override suspend fun keyScopeOf(id: String): String? = rows[id]?.takeIf { it.deletedAt == null }?.keyScope

    override suspend fun setKeyScope(id: String, scope: String) {
        rows[id]?.let { rows[id] = it.copy(keyScope = scope) }
    }

    override suspend fun aliveOfType(ids: List<String>, type: String): List<ObjectSummary> =
        ids.mapNotNull { rows[it] }.filter { it.type == type && it.deletedAt == null }.map { it.toSummary() }

    override suspend fun countSiblingsNamed(parentId: String?, type: String, name: String, excludeId: String) =
        rows.values.count {
            it.type == type && it.deletedAt == null && it.name == name && it.parentId == parentId && it.id != excludeId
        }

    override suspend fun rename(id: String, name: String, at: Long) {
        rows[id]?.let { rows[id] = it.copy(name = name, updatedAt = at) }
    }

    override suspend fun move(id: String, parentId: String?, at: Long) {
        rows[id]?.let { rows[id] = it.copy(parentId = parentId, updatedAt = at) }
    }

    override suspend fun softDelete(id: String, at: Long) {
        rows[id]?.let { if (it.deletedAt == null) rows[id] = it.copy(deletedAt = at) }
    }

    override suspend fun touch(id: String, at: Long) {
        rows[id]?.let { rows[id] = it.copy(updatedAt = at) }
    }

    override suspend fun setFlags(id: String, flags: Int) {
        // Deliberately no updatedAt — mirrors the real query (arc 17 / K2).
        rows[id]?.let { rows[id] = it.copy(flags = flags) }
    }

    override suspend fun setPageCount(id: String, count: Int) {
        rows[id]?.let { rows[id] = it.copy(pageCount = count) }
    }

    override suspend fun setCover(id: String, cover: ByteArray?) {
        rows[id]?.let { rows[id] = it.copy(blob = cover) }
    }

    override suspend fun cover(id: String) = rows[id]?.blob

    override suspend fun clearBlob(id: String) {
        rows[id]?.let { rows[id] = it.copy(blob = null) }
    }

    override suspend fun listItem(listId: String, memberId: String) =
        rows.values.firstOrNull { it.type == "list_item" && it.parentId == listId && it.refId == memberId }

    override suspend fun listMemberIds(listId: String) =
        rows.values.filter { it.type == "list_item" && it.parentId == listId }
            .sortedBy { it.sortOrder ?: 0 }.mapNotNull { it.refId }

    override suspend fun maxSortOrder(listId: String) =
        rows.values.filter { it.type == "list_item" && it.parentId == listId }
            .maxOfOrNull { it.sortOrder ?: -1 } ?: -1

    override suspend fun deleteListItem(listId: String, memberId: String) {
        rows.values.filter { it.type == "list_item" && it.parentId == listId && it.refId == memberId }
            .forEach { rows.remove(it.id) }
    }

    override suspend fun deleteEdgesTo(memberId: String) {
        rows.values.filter { it.type == "list_item" && it.refId == memberId }.forEach { rows.remove(it.id) }
    }

    override suspend fun namingRowAny(parentId: String?) =
        rows.values.firstOrNull { it.type == "naming" && it.parentId == parentId }

    override suspend fun clipHeader(id: String): ClipHeader? =
        rows[id]?.takeIf { it.type == "clipboard" && it.deletedAt == null }
            ?.let { ClipHeader(it.name, it.refId, it.updatedAt, it.flags) }

    override suspend fun clipBlob(id: String): ByteArray? {
        if (clipBlobThrows) throw IllegalStateException("Row too big to fit into CursorWindow")
        return rows[id]?.takeIf { it.type == "clipboard" && it.deletedAt == null }?.blob
    }

    override suspend fun clipClear(id: String, at: Long) {
        rows[id]?.takeIf { it.type == "clipboard" }?.let { rows[id] = it.copy(deletedAt = at, blob = null) }
    }

    override suspend fun backupBlob(id: String): ByteArray? =
        rows[id]?.takeIf { it.type == "backup" }?.blob

    private fun ObjectEntity.toSummary() =
        ObjectSummary(id, type, name, parentId, createdAt, updatedAt, pageCount, flags, templateKind, keyScope)
}
