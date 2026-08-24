package com.symmetricalpalmtree.notesproutsn.data.clip

import com.symmetricalpalmtree.notesproutsn.data.index.ObjectDao
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary

/** Minimal in-memory `objects` table — enough for [ClipStoreTest]; Room is not what is under test. */
class FakeObjectDao : ObjectDao {

    val rows = LinkedHashMap<String, ObjectEntity>()

    /** Stands in for SQLCipher refusing a row that overflows the cursor window (B3 review). */
    var clipBlobThrows = false

    override suspend fun upsert(row: ObjectEntity) { rows[row.id] = row }

    override suspend fun byId(id: String) = rows[id]

    override suspend fun summaryById(id: String) = rows[id]?.toSummary()

    override suspend fun childrenOfType(parentId: String?, type: String) =
        rows.values.filter { it.type == type && it.deletedAt == null && it.parentId == parentId }.map { it.toSummary() }

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

    override suspend fun setPageCount(id: String, count: Int) {
        rows[id]?.let { rows[id] = it.copy(pageCount = count) }
    }

    override suspend fun setCover(id: String, cover: ByteArray?) {
        rows[id]?.let { rows[id] = it.copy(blob = cover) }
    }

    override suspend fun cover(id: String) = rows[id]?.blob

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

    private fun ObjectEntity.toSummary() =
        ObjectSummary(id, type, name, parentId, createdAt, updatedAt, pageCount, flags, templateKind)
}
