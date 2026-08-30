package com.symmetricalpalmtree.notesproutsn.data.index

import androidx.room.withTransaction
import com.symmetricalpalmtree.notesproutsn.data.soil.FolderRef
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateSearch
import java.util.UUID

/**
 * Every read and write against the global index. Suspend functions; Room dispatches to its own
 * executor, so callers may be on Main. Names never leave the index for prefs; covers are stored here
 * and nowhere else (SN has only the GLOBAL scope, so a cover is always allowed).
 */
class IndexRepository(private val dao: ObjectDao = SnIndex.dao()) {

    // ── Listing ──────────────────────────────────────────────────────────────

    /** Alive child folders of [parentId], blob-free. [type] picks the hierarchy (arc 13). */
    suspend fun folders(parentId: String?, type: String = ObjectType.FOLDER): List<ObjectSummary> =
        dao.childrenOfType(parentId, type)

    suspend fun notebooks(parentId: String?): List<ObjectSummary> = dao.childrenOfType(parentId, ObjectType.NOTEBOOK)

    /** Every alive notebook, anywhere in the tree — the backup work list (arc 17 / K2). */
    suspend fun allNotebooks(): List<ObjectSummary> = dao.allAliveNotebooks()

    /**
     * Set or clear the exclude-from-backup bit (arc 17 / K2). Deliberately **not** an edit:
     * `updatedAt` is untouched, so toggling never re-flags the notebook for backup or moves it in
     * the library's Last-modified sort.
     */
    suspend fun setExcludeFromBackup(id: String, excluded: Boolean) {
        val row = dao.summaryById(id) ?: return
        val flags = row.flags ?: 0
        val next =
            if (excluded) flags or NotebookFlags.EXCLUDE_FROM_BACKUP
            else flags and NotebookFlags.EXCLUDE_FROM_BACKUP.inv()
        if (next != flags) dao.setFlags(id, next)
    }

    suspend fun get(id: String): ObjectEntity? = dao.byId(id)

    suspend fun summary(id: String): ObjectSummary? = dao.summaryById(id)

    /** Alive folder or notebook with this id, else null. */
    suspend fun alive(id: String): ObjectSummary? =
        dao.byId(id)?.takeIf { it.deletedAt == null }?.let {
            ObjectSummary(it.id, it.type, it.name, it.parentId, it.createdAt, it.updatedAt, it.pageCount, it.flags, it.templateKind)
        }

    /** The alive notebooks among [ids], blob-free, keyed by id (arc 10 — one read for a whole
     *  recents list; [alive] would drag a cover blob per row). Empty [ids] never hits the database. */
    suspend fun aliveNotebooks(ids: List<String>): Map<String, ObjectSummary> =
        if (ids.isEmpty()) emptyMap() else dao.aliveNotebooks(ids).associateBy { it.id }

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

    // ── Import (arc 16 / I1) ─────────────────────────────────────────────────

    /**
     * Create a folder **under an id the caller chose** — the "Notebook's folders" pass, and the one
     * write in this class that is *create-only by construction*: if anything at all already holds
     * [id] — a live folder, a soft-deleted one, a notebook, a list — nothing is written and this
     * answers false. An imported ancestry can therefore never resurrect, rename or move the user's
     * own folders; the planner ([com.symmetricalpalmtree.notesproutsn.importing.AncestryPlan]) has
     * already decided the same thing, and this is the backstop that makes it true even if it had
     * not.
     */
    suspend fun createFolderWithId(
        id: String,
        name: String,
        parentId: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (dao.byId(id) != null) return false
        dao.upsert(
            ObjectEntity(
                id = id, type = ObjectType.FOLDER, name = name, parentId = parentId,
                createdAt = now, updatedAt = now,
            )
        )
        return true
    }

    /**
     * Write the index row for a notebook whose `.soil` is **already in the Garden and verified** —
     * the last step of an import, and deliberately the last: a crash before it leaves the library
     * exactly as it was (og's step 9 → 10 ordering).
     *
     * Under a fresh id this inserts; under an id the user already had (an id-collision *Replace*)
     * it rewrites that row in place, keeping its `createdAt` and reviving it if it had been
     * soft-deleted. The cover always goes to null, and [templateKind] is the **imported file's**
     * (read from its first page's template row) — the pixels and kind in the old row describe the
     * notebook that used to be behind this id, not the one now arriving (the I2 review's finding);
     * the first open/close cycle seeds a true cover.
     */
    suspend fun importNotebookRow(
        id: String,
        name: String,
        parentId: String?,
        pageCount: Int,
        createdAt: Long,
        updatedAt: Long,
        templateKind: String?,
    ): ObjectEntity {
        val existing = dao.byId(id)
        val row = ObjectEntity(
            id = id, type = ObjectType.NOTEBOOK, name = name, parentId = parentId,
            createdAt = existing?.createdAt ?: createdAt, updatedAt = updatedAt, deletedAt = null,
            pageCount = pageCount,
            // An id-collision Replace lands on a row the user may have flagged "Exclude from
            // backup" — a wholesale flags rewrite would silently drop that policy (K3 review).
            flags = NotebookFlags.ENCRYPTED or
                ((existing?.flags ?: 0) and NotebookFlags.EXCLUDE_FROM_BACKUP),
            keyScope = KEY_SCOPE_GLOBAL,
            templateKind = templateKind, blob = null,
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
                // The folder's naming scheme goes with it — clearScheme's own semantics, inside
                // this same transaction (Room's suspending withTransaction rides the coroutine
                // context): a stranded alive naming row under a dead folder would be invisible,
                // un-clearable, and would come back to life if that folder id were ever reused.
                clearScheme(fid, now)
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

    /**
     * Root-first chain of folders from the root down to [folderId] (inclusive). Cycle-guarded,
     * ≤ 50 hops. [type] says which hierarchy is being walked — the library's [ObjectType.FOLDER]
     * or arc 13's [ObjectType.TEMPLATE_FOLDER]; a row of the wrong type ends the walk, so the two
     * trees can never be spliced together by a corrupt `parentId`.
     */
    suspend fun ancestry(folderId: String?, type: String = ObjectType.FOLDER): List<FolderRef> {
        val chain = ArrayList<FolderRef>()
        val seen = HashSet<String>()
        var cur = folderId
        var hops = 0
        while (cur != null && hops < MAX_ANCESTRY_HOPS && seen.add(cur)) {
            val row = dao.summaryById(cur) ?: break
            if (row.type != type) break
            chain.add(FolderRef(row.id, row.name, row.parentId))
            cur = row.parentId
            hops++
        }
        chain.reverse()
        return chain
    }

    /** True when [candidateAncestorId] is [folderId] itself or one of its ancestors. */
    suspend fun isSelfOrDescendant(
        folderId: String?,
        candidateAncestorId: String,
        type: String = ObjectType.FOLDER,
    ): Boolean = ancestry(folderId, type).any { it.id == candidateAncestorId }

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

    // ── Template library (arc 13) ────────────────────────────────────────────
    //
    // Two additive row types, no schema change, no migration, and nothing on the filesystem. The
    // sentinel cards (Blank, Default, the three built-in papers) live nowhere in here: they are
    // hardcoded ids composed by the screen, so none of these calls can ever see or touch one.

    /** Alive static templates directly inside [parentId] (null = the templates root), blob-free. */
    suspend fun templates(parentId: String?): List<ObjectSummary> =
        dao.childrenOfType(parentId, ObjectType.TEMPLATE)

    /** Alive template folders directly inside [parentId] (null = the templates root), blob-free. */
    suspend fun templateFolders(parentId: String?): List<ObjectSummary> =
        dao.childrenOfType(parentId, ObjectType.TEMPLATE_FOLDER)

    suspend fun createTemplateFolder(
        name: String,
        parentId: String?,
        now: Long = System.currentTimeMillis(),
    ): ObjectEntity {
        val row = ObjectEntity(
            id = UUID.randomUUID().toString(), type = ObjectType.TEMPLATE_FOLDER, name = name,
            parentId = parentId, createdAt = now, updatedAt = now,
        )
        dao.upsert(row)
        return row
    }

    /**
     * Mint a static template row. [kind] is the base kind's name (or `IMAGE` for an imported
     * picture), [fit] the fit mode (G4; 0 until then), [image] the **original** pixels — the
     * page-sized render happens on use, so one row lands correctly on a Nomad page and a Manta one.
     */
    suspend fun createTemplate(
        name: String,
        parentId: String?,
        kind: String,
        fit: Int,
        image: ByteArray?,
        now: Long = System.currentTimeMillis(),
    ): ObjectEntity {
        val row = ObjectEntity(
            id = UUID.randomUUID().toString(), type = ObjectType.TEMPLATE, name = name,
            parentId = parentId, createdAt = now, updatedAt = now,
            flags = fit, templateKind = kind, blob = image,
        )
        dao.upsert(row)
        return row
    }

    /** An alive static template row, whole — pixels included. The one read that costs bytes; never
     *  in a listing, and never where a digest would do. */
    suspend fun templateRow(id: String): ObjectEntity? = dao.byId(id)?.takeIf {
        it.type == ObjectType.TEMPLATE && it.deletedAt == null
    }

    /** A static template's stored image bytes. The one read that costs pixels — never in a listing. */
    suspend fun templateImage(id: String): ByteArray? = templateRow(id)?.blob

    /**
     * Copy an alive static template into its own folder under [newName]. Returns the new row, or
     * null when the source is gone — a duplicate of nothing is not an error worth throwing for, it
     * is a listing that moved under the user's finger.
     */
    suspend fun duplicateTemplate(
        id: String,
        newName: String,
        now: Long = System.currentTimeMillis(),
    ): ObjectEntity? {
        val src = dao.byId(id)?.takeIf { it.type == ObjectType.TEMPLATE && it.deletedAt == null } ?: return null
        return createTemplate(
            name = newName, parentId = src.parentId, kind = src.templateKind.orEmpty(),
            fit = src.flags ?: 0, image = src.blob, now = now,
        )
    }

    /**
     * Change a static template's fit mode (G4's **Fit…** row) and bump `updatedAt`.
     *
     * The bump is not bookkeeping — it is what the change *is* on screen. A card's miniature is
     * cached on `id:updatedAt`, and fit is the whole difference between the same picture centred on
     * white and pulled to the corners, so a silent write would leave every card showing the old
     * arrangement until the cache happened to be evicted.
     *
     * Notebooks already papered with the old fit are untouched, like every other edit here: their
     * pixels were copied into the `.soil` when it was applied.
     */
    suspend fun setTemplateFit(id: String, fit: Int, now: Long = System.currentTimeMillis()): Boolean {
        val row = templateRow(id) ?: return false
        if (row.flags == fit) return true
        dao.upsert(row.copy(flags = fit, updatedAt = now))
        return true
    }

    /**
     * Soft-delete a static template, scrub its membership edges (G5's Pinned list) and drop its
     * stored pixels. Every notebook that used it is untouched: those pixels were copied into the
     * `.soil` at apply time, which is og's rule and the whole reason a template can be deleted at
     * all.
     *
     * **The blob goes with it** (G6). The row stays — soft deletes are the family's rule and what
     * keeps a restore honest — but an imported template's blob is up to 6 MiB that nothing can ever
     * read again (`templateRow` filters on `deletedAt`), and a delete the dialog calls permanent
     * should not leave the largest thing about it behind. Deleting twenty templates would otherwise
     * carry ~120 MB of unreachable bytes in an encrypted index forever.
     *
     * **The order is the atomicity.** `softDelete` lands *before* `clearBlob`, so no interruption
     * can leave an alive row with no pixels — a template that lists but draws nothing. The worst a
     * kill between the two can do is leave the blob behind on a row already gone, which is exactly
     * the state this call was written to improve on. That is why there is no transaction here: one
     * would need Android, and these three writes are already JVM-tested against the real repository.
     */
    suspend fun deleteTemplate(id: String, now: Long = System.currentTimeMillis()) {
        dao.deleteEdgesTo(id)
        dao.softDelete(id, now)
        dao.clearBlob(id)
    }

    /**
     * Soft-delete a template folder and everything under it, in one transaction — the same
     * never-strand-a-subtree rule as [deleteFolderRecursive], for the same reason: a half-walked
     * cascade leaves alive rows under a dead parent, invisible in browse and un-deletable again.
     * Returns how many templates went with it. Cycle-guarded.
     */
    suspend fun deleteTemplateFolderRecursive(id: String, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        SnIndex.db().withTransaction {
            val seen = HashSet<String>()
            val stack = ArrayDeque<String>().apply { add(id) }
            while (stack.isNotEmpty()) {
                val fid = stack.removeLast()
                if (!seen.add(fid)) continue
                for (t in dao.childrenOfType(fid, ObjectType.TEMPLATE)) {
                    dao.deleteEdgesTo(t.id)
                    dao.softDelete(t.id, now)
                    dao.clearBlob(t.id)
                    removed++
                }
                for (sub in dao.childrenOfType(fid, ObjectType.TEMPLATE_FOLDER)) stack.add(sub.id)
                dao.deleteEdgesTo(fid)
                dao.softDelete(fid, now)
            }
        }
        return removed
    }

    // ── The template shelves (arc 13 / G5) ───────────────────────────────────
    //
    // Pinned is a second LIST, in its own sentinel, holding list_item edges. Its members are not
    // all rows: the three built-in papers are pinned by their hardcoded ids, and there is nothing
    // in the database for them to be. So every read here answers with ids and lets the screen
    // resolve them — `templates/TemplateShelves` is where "a sentinel is always alive" lives, and
    // it lives there rather than here because it is a rule about cards, not about storage.

    suspend fun ensureTemplatePinnedListExists(now: Long = System.currentTimeMillis()) {
        if (dao.byId(ListIds.TEMPLATE_PINNED_LIST_ID) == null) {
            dao.upsert(ObjectEntity(
                id = ListIds.TEMPLATE_PINNED_LIST_ID, type = ObjectType.LIST, name = "pinned_templates",
                parentId = null, createdAt = now, updatedAt = now,
            ))
        }
    }

    suspend fun isTemplatePinned(templateId: String): Boolean =
        dao.listItem(ListIds.TEMPLATE_PINNED_LIST_ID, templateId) != null

    suspend fun pinTemplate(templateId: String, now: Long = System.currentTimeMillis()) {
        ensureTemplatePinnedListExists(now)
        if (isTemplatePinned(templateId)) return
        dao.upsert(ObjectEntity(
            id = UUID.randomUUID().toString(), type = ObjectType.LIST_ITEM, name = "",
            parentId = ListIds.TEMPLATE_PINNED_LIST_ID, createdAt = now, updatedAt = now,
            refId = templateId, sortOrder = dao.maxSortOrder(ListIds.TEMPLATE_PINNED_LIST_ID) + 1,
        ))
    }

    suspend fun unpinTemplate(templateId: String) =
        dao.deleteListItem(ListIds.TEMPLATE_PINNED_LIST_ID, templateId)

    /**
     * Every pinned id in `sortOrder`, **unfiltered** — sentinels and rows alike, dead rows included.
     * Filtering is the caller's, and it needs [aliveTemplates] to do it: a `deleteTemplate` scrubs
     * the edge itself ([deleteTemplate] / [deleteTemplateFolderRecursive] both call `deleteEdgesTo`),
     * so a dead id here means an index restored from a backup or a row deleted by a build that did
     * not scrub — neither of which is worth a per-id read on every refresh.
     */
    suspend fun pinnedTemplateIds(): List<String> = dao.listMemberIds(ListIds.TEMPLATE_PINNED_LIST_ID)

    /** The alive static templates among [ids], blob-free, keyed by id. One read for a whole shelf —
     *  never `templateRow`, which drags every pinned template's pixels along with it. */
    suspend fun aliveTemplates(ids: List<String>): Map<String, ObjectSummary> =
        if (ids.isEmpty()) emptyMap() else dao.aliveOfType(ids, ObjectType.TEMPLATE).associateBy { it.id }

    /**
     * Every alive static template whose name matches [query], anywhere in the tree, blob-free.
     * A blank query reads nothing at all rather than the whole library.
     */
    suspend fun searchTemplates(query: String): List<ObjectSummary> =
        if (!TemplateSearch.isRunnable(query)) emptyList()
        else dao.searchOfType(ObjectType.TEMPLATE, TemplateSearch.likePattern(query))

    private companion object {
        const val MAX_ANCESTRY_HOPS = 50
    }
}
