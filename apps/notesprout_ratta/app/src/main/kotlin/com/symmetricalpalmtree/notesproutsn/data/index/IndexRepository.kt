package com.symmetricalpalmtree.notesproutsn.data.index

import androidx.room.withTransaction
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
        // One transaction for the whole cascade: a process kill mid-walk must never strand an alive
        // subtree under a dead parent (unreachable in browse, un-deletable again, its .soil files
        // and cached keys never purged because the caller never learns those notebook ids).
        val notebookIds = mutableListOf<String>()
        SnIndex.db().withTransaction {
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
                // The folder's naming scheme goes with it, in the same transaction: a stranded
                // alive naming row under a dead folder would be invisible, un-clearable, and would
                // come back to life if that folder id were ever reused.
                dao.namingRowAny(fid)?.let { if (it.deletedAt == null) dao.softDelete(it.id, now) }
                dao.softDelete(fid, now)
            }
        }
        return notebookIds
    }

    // ── Naming schemes ───────────────────────────────────────────────────────

    /**
     * The scheme stored **on** [folderId] itself (null = the library root), or null if it has none.
     * No inheritance — that is [resolveScheme]'s job; this is what the edit dialog shows.
     */
    suspend fun scheme(folderId: String?): String? =
        dao.namingRowAny(folderId)?.takeIf { it.deletedAt == null }?.name

    /**
     * Store [scheme] on [folderId] — an upsert **in place**: the existing row keeps its id and
     * `createdAt`, and a soft-deleted one is revived (`deletedAt = null`) rather than replaced, so
     * a folder never accumulates naming rows however often its scheme is set and cleared.
     */
    suspend fun setScheme(folderId: String?, scheme: String, now: Long = System.currentTimeMillis()) {
        val existing = dao.namingRowAny(folderId)
        dao.upsert(
            ObjectEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                type = ObjectType.NAMING,
                name = scheme,
                parentId = folderId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                deletedAt = null,
            )
        )
    }

    /** Clear [folderId]'s scheme by soft-deleting its naming row. A no-op when there is none. */
    suspend fun clearScheme(folderId: String?, now: Long = System.currentTimeMillis()) {
        val existing = dao.namingRowAny(folderId) ?: return
        if (existing.deletedAt == null) dao.softDelete(existing.id, now)
    }

    /**
     * The scheme that governs new notebooks created in [folderId]: **nearest ancestor wins**. The
     * folder itself is asked first, then each folder up the chain, and finally the library root
     * (the `parentId = null` row) — the first alive scheme is the answer, null if there is none.
     *
     * The walk rides [ancestry], which is already cycle-guarded and hop-capped, so a corrupt parent
     * chain costs a bounded number of reads rather than a hang.
     */
    suspend fun resolveScheme(folderId: String?): String? {
        // ancestry() is root-first and includes folderId; nearest-first is that list reversed.
        for (ref in ancestry(folderId).asReversed()) scheme(ref.id)?.let { return it }
        return scheme(null)
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
