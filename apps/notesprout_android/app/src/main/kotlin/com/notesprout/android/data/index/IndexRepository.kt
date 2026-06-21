package com.notesprout.android.data.index

import com.notesprout.android.crypto.EncryptionInfo
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.data.FolderRef
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
            data = Json.encodeToString(FolderObject())
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
            data = Json.encodeToString(FolderObject()),
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
            data = Json.encodeToString(obj),
        )
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
            data = Json.encodeToString(NotebookObject())
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
        val obj = Json.decodeFromString<NotebookObject>(entity.data)
        // Never cache plaintext page content for encrypted notebooks (leak hygiene).
        if (obj.encrypted) return
        dao.update(
            entity.copy(
                data = Json.encodeToString(obj.copy(snapshot = snapshot)),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateNotebookPageCount(id: String, pageCount: Int) {
        val entity = dao.getById(id) ?: return
        val obj = Json.decodeFromString<NotebookObject>(entity.data)
        dao.update(
            entity.copy(
                data = Json.encodeToString(obj.copy(pageCount = pageCount)),
                updatedAt = System.currentTimeMillis()
            )
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
            data = Json.encodeToString(FolderObject())
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
            data = TemplateObject(width, height, imageBase64).toJson()
        )
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
        val entity = ObjectEntity(
            id = UUID.randomUUID().toString(),
            type = source.type,
            name = newName ?: source.name,
            parentId = destParentId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = source.data
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

    suspend fun ensurePinnedListExists() {
        // TODO: This only checks for the single known Pinned list. When
        // user-defined lists are introduced, revisit this bootstrap to handle
        // multiple lists / a general "ensure system lists exist" pass.
        val existing = dao.getById(PINNED_LIST_ID)
        if (existing == null || existing.deletedAt != null) {
            val now = System.currentTimeMillis()
            dao.insert(
                ObjectEntity(
                    id = PINNED_LIST_ID,
                    type = ObjectType.LIST,
                    name = "Pinned",
                    parentId = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    data = Json.encodeToString(ListObject())
                )
            )
        }
    }

    suspend fun getPinnedList(): ObjectEntity? = dao.getById(PINNED_LIST_ID)

    suspend fun addNotebookToList(listId: String, notebookId: String) {
        val entity = dao.getById(listId) ?: return
        val listObj = Json.decodeFromString<ListObject>(entity.data)
        if (notebookId in listObj.notebookIds) return
        dao.update(
            entity.copy(
                data = Json.encodeToString(listObj.copy(notebookIds = listObj.notebookIds + notebookId)),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeNotebookFromList(listId: String, notebookId: String) {
        val entity = dao.getById(listId) ?: return
        val listObj = Json.decodeFromString<ListObject>(entity.data)
        if (notebookId !in listObj.notebookIds) return
        dao.update(
            entity.copy(
                data = Json.encodeToString(listObj.copy(notebookIds = listObj.notebookIds - notebookId)),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun reorderList(listId: String, newOrder: List<String>) {
        val entity = dao.getById(listId) ?: return
        val listObj = Json.decodeFromString<ListObject>(entity.data)
        dao.update(
            entity.copy(
                data = Json.encodeToString(listObj.copy(notebookIds = newOrder)),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getNotebooksInList(listId: String): List<ObjectEntity> {
        val entity = dao.getById(listId) ?: return emptyList()
        val listObj = Json.decodeFromString<ListObject>(entity.data)
        return listObj.notebookIds.mapNotNull { id ->
            val e = dao.getById(id)
            if (e == null || e.deletedAt != null || e.type != ObjectType.NOTEBOOK) null else e
        }
    }

    suspend fun isNotebookPinned(notebookId: String): Boolean {
        val entity = dao.getById(PINNED_LIST_ID) ?: return false
        val listObj = Json.decodeFromString<ListObject>(entity.data)
        return notebookId in listObj.notebookIds
    }

    /**
     * Toggles the pin state of a notebook in a single DB round-trip.
     * Returns true if the notebook is now pinned, false if now unpinned.
     */
    suspend fun togglePin(notebookId: String): Boolean {
        val entity = dao.getById(PINNED_LIST_ID) ?: return false
        val listObj = Json.decodeFromString<ListObject>(entity.data)
        val nowPinned = notebookId !in listObj.notebookIds
        val newIds = if (nowPinned) listObj.notebookIds + notebookId
                     else listObj.notebookIds - notebookId
        dao.update(
            entity.copy(
                data = Json.encodeToString(listObj.copy(notebookIds = newIds)),
                updatedAt = System.currentTimeMillis()
            )
        )
        return nowPinned
    }

    suspend fun scrubNotebookFromAllLists(notebookId: String) {
        val lists = dao.getAllNotDeleted().filter {
            it.type == ObjectType.LIST && it.id != PINNED_TEMPLATES_LIST_ID
        }
        for (listEntity in lists) {
            val listObj = try {
                lenientJson.decodeFromString<ListObject>(listEntity.data)
            } catch (_: Exception) { continue }
            if (notebookId in listObj.notebookIds) {
                dao.update(
                    listEntity.copy(
                        data = Json.encodeToString(listObj.copy(notebookIds = listObj.notebookIds - notebookId)),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // endregion

    // region Template pin operations

    suspend fun ensurePinnedTemplatesListExists() {
        val existing = dao.getById(PINNED_TEMPLATES_LIST_ID)
        if (existing == null || existing.deletedAt != null) {
            val now = System.currentTimeMillis()
            dao.insert(
                ObjectEntity(
                    id = PINNED_TEMPLATES_LIST_ID,
                    type = ObjectType.LIST,
                    name = "Pinned Templates",
                    parentId = null,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                    data = Json.encodeToString(TemplateListObject())
                )
            )
        }
    }

    suspend fun isTemplatePinned(templateId: String): Boolean {
        val entity = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return false
        val listObj = Json.decodeFromString<TemplateListObject>(entity.data)
        return templateId in listObj.templateIds
    }

    /**
     * Toggles the pin state of a template in a single DB round-trip.
     * Returns true if the template is now pinned, false if now unpinned.
     */
    suspend fun toggleTemplatePin(templateId: String): Boolean {
        val entity = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return false
        val listObj = Json.decodeFromString<TemplateListObject>(entity.data)
        val nowPinned = templateId !in listObj.templateIds
        val newIds = if (nowPinned) listObj.templateIds + templateId
                     else listObj.templateIds - templateId
        dao.update(
            entity.copy(
                data = Json.encodeToString(listObj.copy(templateIds = newIds)),
                updatedAt = System.currentTimeMillis()
            )
        )
        return nowPinned
    }

    suspend fun getPinnedTemplates(): List<ObjectEntity> {
        val entity = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return emptyList()
        val listObj = Json.decodeFromString<TemplateListObject>(entity.data)
        return listObj.templateIds.mapNotNull { id ->
            val e = dao.getById(id)
            if (e == null || e.deletedAt != null || e.type != ObjectType.TEMPLATE) null else e
        }
    }

    suspend fun scrubTemplateFromPinned(templateId: String) {
        val entity = dao.getById(PINNED_TEMPLATES_LIST_ID) ?: return
        val listObj = Json.decodeFromString<TemplateListObject>(entity.data)
        if (templateId in listObj.templateIds) {
            dao.update(
                entity.copy(
                    data = Json.encodeToString(listObj.copy(templateIds = listObj.templateIds - templateId)),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // endregion

    // region Encryption metadata

    suspend fun countGlobalNotebooks(): Int =
        dao.getAllNotDeleted()
            .count { entity ->
                if (entity.type != ObjectType.NOTEBOOK) return@count false
                val obj = try { Json.decodeFromString<NotebookObject>(entity.data) } catch (_: Exception) { return@count false }
                obj.encrypted && obj.keyScope == KeyScope.GLOBAL
            }

    suspend fun getGlobalNotebookIds(): List<String> =
        dao.getAllNotDeleted()
            .filter { entity ->
                if (entity.type != ObjectType.NOTEBOOK) return@filter false
                val obj = try { Json.decodeFromString<NotebookObject>(entity.data) } catch (_: Exception) { return@filter false }
                obj.encrypted && obj.keyScope == KeyScope.GLOBAL
            }
            .map { it.id }

    suspend fun getEncryptionInfo(notebookId: String): EncryptionInfo {
        val entity = dao.getById(notebookId) ?: return EncryptionInfo.NONE
        val obj = Json.decodeFromString<NotebookObject>(entity.data)
        return EncryptionInfo(obj.encrypted, obj.keyScope)
    }

    /**
     * Write encryption state to the index row. When [encrypted] is true, also clears the snapshot
     * to prevent plaintext page content from leaking into the global (unencrypted) index.
     */
    suspend fun setEncryptionState(notebookId: String, encrypted: Boolean, keyScope: KeyScope?) {
        val entity = dao.getById(notebookId) ?: return
        val obj = Json.decodeFromString<NotebookObject>(entity.data)
        val updated = obj.copy(
            encrypted = encrypted,
            keyScope = keyScope,
            snapshot = if (encrypted) null else obj.snapshot,
        )
        dao.update(entity.copy(data = Json.encodeToString(updated), updatedAt = System.currentTimeMillis()))
    }

    // endregion
}
