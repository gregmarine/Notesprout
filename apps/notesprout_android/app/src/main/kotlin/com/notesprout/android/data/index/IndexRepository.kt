package com.notesprout.android.data.index

import com.notesprout.android.crypto.EncryptionInfo
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.data.ClipboardPayload
import com.notesprout.android.data.ClipboardStore
import com.notesprout.android.data.FolderRef
import com.notesprout.android.data.backup.BackupConfig
import com.notesprout.android.data.backup.BackupConfigStore
import com.notesprout.android.data.backup.BackupKind
import com.notesprout.android.data.backup.needsBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val lenientJson = Json { ignoreUnknownKeys = true }

class IndexRepository(private val dao: ObjectDao) {

    // region Folder operations

    suspend fun createFolder(name: String, parentId: String?): ObjectEntity {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.FOLDER,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = "",
        )
        dao.insert(entity)
        return entity
    }

    /**
     * Import helper: ensure a FOLDER with an exact [id] exists (for recreating the source
     * notebook's folder hierarchy with the same UUIDs). If the folder is present and live,
     * it is returned as-is. If soft-deleted, it is un-deleted and its name/parent updated.
     * If absent, a new row is inserted with the given [id].
     */
    suspend fun ensureFolderWithId(id: String, name: String, parentId: String?): ObjectEntity {
        val existing = dao.getById(id)
        val now = System.currentTimeMillis()
        if (existing != null) {
            if (existing.deletedAt == null) return existing
            val restored = existing.copy(name = name, parentId = parentId, deletedAt = null, updatedAt = now)
            dao.update(restored)
            return restored
        }
        val entity = ObjectEntity(
            id = id,
            type = ObjectType.FOLDER,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = "",
        )
        dao.insert(entity)
        return entity
    }

    /** Import helper: insert (or resurrect a soft-deleted) notebook row with an explicit [id]. */
    suspend fun importNotebookRow(
        id: String,
        name: String,
        parentId: String?,
        obj: NotebookObject,
        createdAt: Long,
        updatedAt: Long,
    ): ObjectEntity {
        val entity = ObjectEntity(
            id = id,
            type = ObjectType.NOTEBOOK,
            name = name,
            parentId = parentId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = null,
        ).withNotebookMeta(obj)
        val existing = dao.getById(id)
        if (existing != null) dao.update(entity) else dao.insert(entity)
        return entity
    }

    suspend fun renameFolder(id: String, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDeleteFolder(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun getFolder(id: String): ObjectEntity? = dao.getById(id)

    // endregion

    // region Notebook operations

    suspend fun createNotebook(name: String, parentId: String?): ObjectEntity {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.NOTEBOOK,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = "",
        )
        dao.insert(entity)
        return entity
    }

    suspend fun renameNotebook(id: String, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDeleteNotebook(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun updateNotebookSnapshot(id: String, snapshot: String?) {
        val entity = dao.getById(id) ?: return
        val obj = entity.notebookMeta()
        // Private (NOTEBOOK-scope) notebooks never cache page content into the index. GLOBAL-scope
        // covers are fine now that the index itself is encrypted at rest under the global key.
        if (obj.encrypted && obj.keyScope != KeyScope.GLOBAL) return
        dao.update(
            entity.withNotebookMeta(obj.copy(snapshot = snapshot))
                .copy(updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun updateNotebookPageCount(id: String, pageCount: Int) {
        val entity = dao.getById(id) ?: return
        val obj = entity.notebookMeta()
        dao.update(
            entity.withNotebookMeta(obj.copy(pageCount = pageCount))
                .copy(updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun getNotebook(id: String): ObjectEntity? = dao.getById(id)

    // endregion

    // region Template operations

    suspend fun createTemplateFolder(name: String, parentId: String?): ObjectEntity {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.TEMPLATE_FOLDER,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = "",
        )
        dao.insert(entity)
        return entity
    }

    suspend fun createTemplate(name: String, parentId: String?, width: Int, height: Int, imageBase64: String): ObjectEntity {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = ObjectType.TEMPLATE,
            name = name,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ).withTemplate(TemplateObject(width, height, imageBase64))
        dao.insert(entity)
        return entity
    }

    suspend fun renameTemplate(id: String, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun renameTemplateFolder(id: String, newName: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(name = newName, updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDeleteTemplate(id: String) {
        scrubTemplateFromPinned(id)
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun softDeleteTemplateFolder(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun getTemplate(id: String): ObjectEntity? = dao.getById(id)

    suspend fun getTemplates(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, ObjectType.TEMPLATE)

    suspend fun getTemplateFolders(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, ObjectType.TEMPLATE_FOLDER)

    suspend fun getAllTemplates(): List<ObjectEntity> =
        dao.getAllNotDeleted().filter { it.type == ObjectType.TEMPLATE }

    suspend fun getAllTemplateFolders(): List<ObjectEntity> =
        dao.getAllNotDeleted().filter { it.type == ObjectType.TEMPLATE_FOLDER }

    suspend fun copyTemplate(sourceId: String, destParentId: String?, newName: String? = null): ObjectEntity? {
        val source = dao.getById(sourceId) ?: return null
        val now = System.currentTimeMillis()
        // Carry every payload column (data / width / height / blob …) so the copy preserves the
        // source's storage format — columnar rows stay columnar, legacy JSON stays JSON.
        val entity = source.copy(
            id = UUID.randomUUID().toString(),
            name = newName ?: source.name,
            parentId = destParentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
        dao.insert(entity)
        return entity
    }

    suspend fun copyTemplateFolderRecursively(sourceFolderId: String, destParentId: String?) {
        val source = dao.getById(sourceFolderId) ?: return
        if (source.type != ObjectType.TEMPLATE_FOLDER) return
        val newFolder = createTemplateFolder(source.name, destParentId)
        val newFolderId = newFolder.id
        for (child in getTemplateFolders(sourceFolderId)) {
            copyTemplateFolderRecursively(child.id, newFolderId)
        }
        for (child in getTemplates(sourceFolderId)) {
            copyTemplate(child.id, newFolderId)
        }
    }

    suspend fun deleteTemplateFolderRecursively(folderId: String) {
        for (child in getTemplateFolders(folderId)) {
            deleteTemplateFolderRecursively(child.id)
        }
        for (child in getTemplates(folderId)) {
            softDeleteTemplate(child.id)
        }
        softDeleteTemplateFolder(folderId)
    }

    // endregion

    /**
     * Returns the ancestor chain from root → immediate parent of [startParentId], as
     * [FolderRef] entries. Returns an empty list when [startParentId] is null (notebook at root).
     * Capped at 50 hops as a cycle guard.
     */
    suspend fun getFolderAncestry(startParentId: String?): List<FolderRef> {
        if (startParentId == null) return emptyList()
        val chain = ArrayDeque<FolderRef>()
        var currentId: String? = startParentId
        var hops = 0
        while (currentId != null && hops++ < 50) {
            val entity = dao.getById(currentId) ?: break
            chain.addFirst(FolderRef(entity.id, entity.name, entity.parentId))
            currentId = entity.parentId
        }
        return chain.toList()
    }

    // region Navigation operations

    suspend fun getChildren(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, type = null)

    suspend fun getFolders(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, type = ObjectType.FOLDER)

    suspend fun getNotebooks(parentId: String?): List<ObjectEntity> =
        dao.getChildren(parentId, type = ObjectType.NOTEBOOK)

    suspend fun getAllNotebooks(): List<ObjectEntity> =
        dao.getAllNotDeleted().filter { it.type == ObjectType.NOTEBOOK }

    suspend fun getAllFolders(): List<ObjectEntity> =
        dao.getAllNotDeleted().filter { it.type == ObjectType.FOLDER }

    // endregion

    // region Object movement

    suspend fun moveObject(id: String, newParentId: String?) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(parentId = newParentId, updatedAt = System.currentTimeMillis()))
    }

    // endregion

    // region Timestamp

    suspend fun touchNotebook(id: String) {
        val entity = dao.getById(id) ?: return
        dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    // endregion

    // region List operations
    // A list's membership is modeled relationally as `list_item` child rows (Phase B): parentId = list
    // id, refId = member id, sortOrder = position. Reads are format-agnostic — a legacy list still holds
    // its inline `ListObject`/`TemplateListObject` JSON in `data` and is read from there until its first
    // write (or the ensure* bootstrap) converts it to child rows and clears `data`. Membership churn
    // hard-deletes the edge rows (no tombstones — not precious history).

    /** Decode a legacy inline-membership `data` JSON (either notebookIds or templateIds). */
    private fun legacyMemberIds(data: String): List<String> {
        if (data.isEmpty()) return emptyList()
        val nb = runCatching { lenientJson.decodeFromString<ListObject>(data).notebookIds }.getOrDefault(emptyList())
        if (nb.isNotEmpty()) return nb
        return runCatching { lenientJson.decodeFromString<TemplateListObject>(data).templateIds }.getOrDefault(emptyList())
    }

    /** Format-agnostic membership read: legacy inline JSON, else the ordered `list_item` child rows. */
    private suspend fun memberIdsOf(list: ObjectEntity): List<String> =
        if (list.data.isNotEmpty()) legacyMemberIds(list.data)
        else dao.getListItems(list.id).mapNotNull { it.refId }

    private fun listItemRow(listId: String, memberId: String, order: Int, now: Long) = ObjectEntity(
        id = UUID.randomUUID().toString(),
        type = ObjectType.LIST_ITEM,
        name = "",
        parentId = listId,
        createdAt = now,
        updatedAt = now,
        deletedAt = null,
        data = "",
        refId = memberId,
        sortOrder = order,
    )

    /**
     * Ensure [list] is in child-row form: if it still holds inline JSON members, convert them to
     * `list_item` rows (order preserved) and clear `data`. Idempotent — rebuilds children from the
     * still-present JSON on a retry. `updatedAt` is preserved (a format change, not a content edit).
     * Returns the (possibly refreshed) list row.
     */
    private suspend fun migrated(list: ObjectEntity): ObjectEntity {
        if (list.data.isEmpty()) return list
        val ids = legacyMemberIds(list.data)
        dao.deleteAllListItems(list.id)
        val now = System.currentTimeMillis()
        ids.forEachIndexed { i, mid -> dao.insert(listItemRow(list.id, mid, i, now)) }
        val updated = list.copy(data = "")
        dao.update(updated)
        return updated
    }

    private suspend fun ensureListRow(listId: String, name: String) {
        val existing = dao.getById(listId)
        if (existing == null || existing.deletedAt != null) {
            val now = System.currentTimeMillis()
            dao.insert(ObjectEntity(
                id = listId, type = ObjectType.LIST, name = name, parentId = null,
                createdAt = now, updatedAt = now, deletedAt = null, data = "",
            ))
            return
        }
        migrated(existing)
    }

    suspend fun ensurePinnedListExists() = ensureListRow(PINNED_LIST_ID, "Pinned")

    suspend fun getPinnedList(): ObjectEntity? = dao.getById(PINNED_LIST_ID)

    suspend fun addNotebookToList(listId: String, notebookId: String) {
        val list = migrated(dao.getById(listId) ?: return)
        if (dao.listContains(list.id, notebookId)) return
        dao.insert(listItemRow(list.id, notebookId, dao.maxListSortOrder(list.id) + 1, System.currentTimeMillis()))
    }

    suspend fun removeNotebookFromList(listId: String, notebookId: String) {
        val list = migrated(dao.getById(listId) ?: return)
        dao.deleteListItem(list.id, notebookId)
    }

    /** Rewrite membership order to match [newOrder] (assumed to be the complete member set). */
    suspend fun reorderList(listId: String, newOrder: List<String>) {
        val list = migrated(dao.getById(listId) ?: return)
        newOrder.forEachIndexed { i, memberId -> dao.updateListItemOrder(list.id, memberId, i) }
    }

    suspend fun getNotebooksInList(listId: String): List<ObjectEntity> {
        val list = dao.getById(listId) ?: return emptyList()
        return memberIdsOf(list).mapNotNull { id ->
            val e = dao.getById(id)
            if (e == null || e.deletedAt != null || e.type != ObjectType.NOTEBOOK) null else e
        }
    }

    suspend fun isNotebookPinned(notebookId: String): Boolean {
        val list = dao.getById(PINNED_LIST_ID) ?: return false
        return if (list.data.isNotEmpty()) notebookId in legacyMemberIds(list.data)
               else dao.listContains(PINNED_LIST_ID, notebookId)
    }

    /**
     * Toggles the pin state of a notebook. Returns true if now pinned, false if now unpinned.
     */
    suspend fun togglePin(notebookId: String): Boolean {
        val list = migrated(dao.getById(PINNED_LIST_ID) ?: return false)
        return if (dao.listContains(list.id, notebookId)) {
            dao.deleteListItem(list.id, notebookId); false
        } else {
            dao.insert(listItemRow(list.id, notebookId, dao.maxListSortOrder(list.id) + 1, System.currentTimeMillis())); true
        }
    }

    suspend fun scrubNotebookFromAllLists(notebookId: String) {
        // Migrated lists: drop the member's edge rows across every list in one query.
        dao.deleteListItemsForMember(notebookId)
        // Legacy inline lists (not yet migrated): strip the id from their JSON array.
        val legacyLists = dao.getAllNotDeleted().filter {
            it.type == ObjectType.LIST && it.id != PINNED_TEMPLATES_LIST_ID && it.data.isNotEmpty()
        }
        for (list in legacyLists) {
            val ids = try { lenientJson.decodeFromString<ListObject>(list.data).notebookIds } catch (_: Exception) { continue }
            if (notebookId in ids) {
                dao.update(list.copy(
                    data = Json.encodeToString(ListObject(ids - notebookId)),
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        }
    }

    // endregion

    // region Template pin operations

    suspend fun ensurePinnedTemplatesListExists() = ensureListRow(PINNED_TEMPLATES_LIST_ID, "Pinned Templates")

    suspend fun isTemplatePinned(templateId: String): Boolean {
        val list = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return false
        return if (list.data.isNotEmpty()) templateId in legacyMemberIds(list.data)
               else dao.listContains(PINNED_TEMPLATES_LIST_ID, templateId)
    }

    /**
     * Toggles the pin state of a template. Returns true if now pinned, false if now unpinned.
     */
    suspend fun toggleTemplatePin(templateId: String): Boolean {
        val list = migrated(dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return false)
        return if (dao.listContains(list.id, templateId)) {
            dao.deleteListItem(list.id, templateId); false
        } else {
            dao.insert(listItemRow(list.id, templateId, dao.maxListSortOrder(list.id) + 1, System.currentTimeMillis())); true
        }
    }

    suspend fun getPinnedTemplates(): List<ObjectEntity> {
        val list = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return emptyList()
        return memberIdsOf(list).mapNotNull { id ->
            val e = dao.getById(id)
            if (e == null || e.deletedAt != null || e.type != ObjectType.TEMPLATE) null else e
        }
    }

    suspend fun scrubTemplateFromPinned(templateId: String) {
        val list = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return
        if (list.data.isNotEmpty()) {
            val ids = try { lenientJson.decodeFromString<TemplateListObject>(list.data).templateIds } catch (_: Exception) { return }
            if (templateId in ids) {
                dao.update(list.copy(
                    data = Json.encodeToString(TemplateListObject(ids - templateId)),
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        } else {
            dao.deleteListItem(PINNED_TEMPLATES_LIST_ID, templateId)
        }
    }

    // endregion

    // region Clipboard

    suspend fun saveClipboard(payload: ClipboardPayload) = ClipboardStore.write(dao, payload)
    suspend fun loadClipboard(): ClipboardPayload? = ClipboardStore.read(dao)
    suspend fun clearClipboard() = ClipboardStore.clear(dao)

    // endregion

    // region Backup

    suspend fun getBackupConfig(): BackupConfig? = BackupConfigStore.read(dao)

    suspend fun ensureBackupConfig(defaultName: String): BackupConfig =
        BackupConfigStore.ensure(dao, defaultName)

    suspend fun saveBackupConfig(config: BackupConfig) = BackupConfigStore.write(dao, config)

    suspend fun setNotebookExcludedFromBackup(notebookId: String, excluded: Boolean) {
        val entity = dao.getById(notebookId) ?: return
        val obj = entity.notebookMeta()
        // Do NOT bump updatedAt — exclusion flag is not a content modification.
        dao.update(entity.withNotebookMeta(obj.copy(excludeFromBackup = excluded)))
    }

    suspend fun markNotebookBackedUp(notebookId: String, kind: BackupKind, timestamp: Long) {
        val entity = dao.getById(notebookId) ?: return
        val obj = entity.notebookMeta()
        val updated = when (kind) {
            BackupKind.LOCAL -> obj.copy(lastBackedUpLocal = timestamp)
            BackupKind.DRIVE -> obj.copy(lastBackedUpDrive = timestamp)
        }
        // Do NOT bump updatedAt — stamping backup time is not a content modification.
        dao.update(entity.withNotebookMeta(updated))
    }

    suspend fun notebooksNeedingBackup(kind: BackupKind): List<ObjectEntity> {
        return dao.getAllNotDeleted()
            .filter { it.type == ObjectType.NOTEBOOK }
            .filter { entity ->
                val obj = try {
                    entity.notebookMeta()
                } catch (_: Exception) { return@filter false }
                val lastBackedUp = when (kind) {
                    BackupKind.LOCAL -> obj.lastBackedUpLocal
                    BackupKind.DRIVE -> obj.lastBackedUpDrive
                }
                needsBackup(entity.updatedAt, lastBackedUp, obj.excludeFromBackup)
            }
    }

    // endregion

    // region Encryption metadata

    suspend fun countGlobalNotebooks(): Int =
        dao.getAllNotDeleted()
            .count { entity ->
                if (entity.type != ObjectType.NOTEBOOK) return@count false
                val obj = try { entity.notebookMeta() } catch (_: Exception) { return@count false }
                obj.encrypted && obj.keyScope == KeyScope.GLOBAL
            }

    suspend fun getGlobalNotebookIds(): List<String> =
        dao.getAllNotDeleted()
            .filter { entity ->
                if (entity.type != ObjectType.NOTEBOOK) return@filter false
                val obj = try { entity.notebookMeta() } catch (_: Exception) { return@filter false }
                obj.encrypted && obj.keyScope == KeyScope.GLOBAL
            }
            .map { it.id }

    suspend fun getEncryptionInfo(notebookId: String): EncryptionInfo {
        val entity = dao.getById(notebookId) ?: return EncryptionInfo.NONE
        val obj = entity.notebookMeta()
        return EncryptionInfo(obj.encrypted, obj.keyScope)
    }

    /**
     * Write encryption state to the index row. Clears the snapshot only when converting to
     * private (NOTEBOOK) scope — a GLOBAL-scope cover stays valid because the index is itself
     * encrypted under the global key.
     */
    suspend fun setEncryptionState(notebookId: String, encrypted: Boolean, keyScope: KeyScope?) {
        val entity = dao.getById(notebookId) ?: return
        val obj = entity.notebookMeta()
        val updated = obj.copy(
            encrypted = encrypted,
            keyScope = keyScope,
            snapshot = if (encrypted && keyScope != KeyScope.GLOBAL) null else obj.snapshot,
        )
        dao.update(entity.withNotebookMeta(updated).copy(updatedAt = System.currentTimeMillis()))
    }

    // endregion
}
