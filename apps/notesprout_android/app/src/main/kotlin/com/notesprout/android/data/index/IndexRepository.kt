package com.notesprout.android.data.index

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

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

    suspend fun scrubNotebookFromAllLists(notebookId: String) {
        val lists = dao.getAllNotDeleted().filter { it.type == ObjectType.LIST }
        for (listEntity in lists) {
            val listObj = Json.decodeFromString<ListObject>(listEntity.data)
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
}
