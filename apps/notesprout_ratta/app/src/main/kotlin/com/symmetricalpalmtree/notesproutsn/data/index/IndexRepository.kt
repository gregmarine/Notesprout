package com.symmetricalpalmtree.notesproutsn.data.index

import com.symmetricalpalmtree.notesproutsn.data.soil.FolderRef
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import java.util.UUID

/**
 * Every read and write against the global index. Suspend functions; Room dispatches to its own
 * executor, so callers may be on Main. Names never leave the index for prefs; covers are stored here
 * and nowhere else (SN has only the GLOBAL scope, so a cover is always allowed).
 */
class IndexRepository(private val dao: ObjectDao = SnIndex.dao()) {

    // ── Listing ──────────────────────────────────────────────────────────────

    suspend fun folders(parentId: String?): List<ObjectSummary> = dao.childrenOfType(parentId, ObjectType.FOLDER)

    suspend fun notebooks(parentId: String?): List<ObjectSummary> = dao.childrenOfType(parentId, ObjectType.NOTEBOOK)

    suspend fun get(id: String): ObjectEntity? = dao.byId(id)

    suspend fun summary(id: String): ObjectSummary? = dao.summaryById(id)

    /** Alive folder or notebook with this id, else null. */
    suspend fun alive(id: String): ObjectSummary? =
        dao.byId(id)?.takeIf { it.deletedAt == null }?.let {
            ObjectSummary(it.id, it.type, it.name, it.parentId, it.createdAt, it.updatedAt, it.pageCount, it.flags, it.templateKind)
        }

    /** True when an alive sibling of [type] named [name] exists under [parentId] (excluding [excludeId]). */
    suspend fun nameTaken(parentId: String?, type: String, name: String, excludeId: String = ""): Boolean =
        dao.countSiblingsNamed(parentId, type, name, excludeId) > 0

    // ── Create ───────────────────────────────────────────────────────────────

    suspend fun createFolder(name: String, parentId: String?, now: Long = System.currentTimeMillis()): ObjectEntity {
        val row = ObjectEntity(
            id = UUID.randomUUID().toString(), type = ObjectType.FOLDER, name = name, parentId = parentId,
            createdAt = now, updatedAt = now,
        )
        dao.upsert(row)
        return row
    }

    /** Insert the index row for a notebook whose `.soil` already exists (the caller minted [id]). */
    suspend fun createNotebook(
        id: String, name: String, parentId: String?, templateKind: String, pageCount: Int = 1,
        now: Long = System.currentTimeMillis(),
    ): ObjectEntity {
        val row = ObjectEntity(
            id = id, type = ObjectType.NOTEBOOK, name = name, parentId = parentId,
            createdAt = now, updatedAt = now, pageCount = pageCount,
            flags = NotebookFlags.ENCRYPTED, keyScope = KEY_SCOPE_GLOBAL, templateKind = templateKind,
        )
        dao.upsert(row)
        return row
    }

    // ── Edit ─────────────────────────────────────────────────────────────────

    suspend fun rename(id: String, name: String, now: Long = System.currentTimeMillis()) = dao.rename(id, name, now)

    suspend fun move(id: String, newParentId: String?, now: Long = System.currentTimeMillis()) = dao.move(id, newParentId, now)

    /** The `updatedAt` discipline: bumped only by real edits (ink, page insert/delete, rename, move). */
    suspend fun touch(id: String, now: Long = System.currentTimeMillis()) = dao.touch(id, now)

    suspend fun setPageCount(id: String, count: Int) = dao.setPageCount(id, count)

    suspend fun setCover(id: String, cover: ByteArray?) = dao.setCover(id, cover)

    suspend fun cover(id: String): ByteArray? = dao.cover(id)

    /** Soft-delete a notebook row and scrub its membership edges. File removal is the caller's job. */
    suspend fun deleteNotebook(id: String, now: Long = System.currentTimeMillis()) {
        dao.deleteEdgesTo(id)
        dao.softDelete(id, now)
    }

    /**
     * Soft-delete a folder and everything under it. Returns the ids of the notebooks that were
     * inside (so the caller can remove their files + key-cache entries). Cycle-guarded.
     */
    suspend fun deleteFolderRecursive(id: String, now: Long = System.currentTimeMillis()): List<String> {
        val notebookIds = mutableListOf<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { add(id) }
        while (stack.isNotEmpty()) {
            val fid = stack.removeLast()
            if (!seen.add(fid)) continue
            for (nb in dao.childrenOfType(fid, ObjectType.NOTEBOOK)) {
                dao.deleteEdgesTo(nb.id)
                dao.softDelete(nb.id, now)
                notebookIds += nb.id
            }
            for (sub in dao.childrenOfType(fid, ObjectType.FOLDER)) stack.add(sub.id)
            dao.softDelete(fid, now)
        }
        return notebookIds
    }

    // ── Ancestry ─────────────────────────────────────────────────────────────

    /** Root-first chain of folders from the root down to [folderId] (inclusive). Cycle-guarded, ≤ 50 hops. */
    suspend fun ancestry(folderId: String?): List<FolderRef> {
        val chain = ArrayList<FolderRef>()
        val seen = HashSet<String>()
        var cur = folderId
        var hops = 0
        while (cur != null && hops < MAX_ANCESTRY_HOPS && seen.add(cur)) {
            val row = dao.summaryById(cur) ?: break
            if (row.type != ObjectType.FOLDER) break
            chain.add(FolderRef(row.id, row.name, row.parentId))
            cur = row.parentId
            hops++
        }
        chain.reverse()
        return chain
    }

    /** True when [candidateAncestorId] is [folderId] itself or one of its ancestors. */
    suspend fun isSelfOrDescendant(folderId: String?, candidateAncestorId: String): Boolean =
        ancestry(folderId).any { it.id == candidateAncestorId }

    // ── Pinned list ──────────────────────────────────────────────────────────

    suspend fun ensurePinnedListExists(now: Long = System.currentTimeMillis()) {
        if (dao.byId(ListIds.PINNED_LIST_ID) == null) {
            dao.upsert(ObjectEntity(
                id = ListIds.PINNED_LIST_ID, type = ObjectType.LIST, name = "pinned", parentId = null,
                createdAt = now, updatedAt = now,
            ))
        }
    }

    suspend fun isPinned(notebookId: String): Boolean = dao.listItem(ListIds.PINNED_LIST_ID, notebookId) != null

    suspend fun pin(notebookId: String, now: Long = System.currentTimeMillis()) {
        ensurePinnedListExists(now)
        if (isPinned(notebookId)) return
        dao.upsert(ObjectEntity(
            id = UUID.randomUUID().toString(), type = ObjectType.LIST_ITEM, name = "",
            parentId = ListIds.PINNED_LIST_ID, createdAt = now, updatedAt = now,
            refId = notebookId, sortOrder = dao.maxSortOrder(ListIds.PINNED_LIST_ID) + 1,
        ))
    }

    suspend fun unpin(notebookId: String) = dao.deleteListItem(ListIds.PINNED_LIST_ID, notebookId)

    /** Pinned notebook ids in `sortOrder`, alive only. */
    suspend fun pinnedNotebookIds(): List<String> =
        dao.listMemberIds(ListIds.PINNED_LIST_ID).filter { alive(it)?.type == ObjectType.NOTEBOOK }

    private companion object {
        const val MAX_ANCESTRY_HOPS = 50
    }
}
